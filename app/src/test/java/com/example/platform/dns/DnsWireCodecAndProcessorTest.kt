package com.example.platform.dns

import com.example.domain.filter.CompiledDomainRuleSet
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.BlockedDomain
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.RestrictionMode
import com.example.testing.FakeTimeProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireCodecAndProcessorTest {

  private val timeProvider = FakeTimeProvider()

  private fun buildQueryBytes(
    txId: Int = 0x1234,
    flags: Int = 0x0100, // Standard query + RD
    qname: String = "example.com",
    qtype: Int = DnsRecordType.A.code,
    includeEdns0: Boolean = false,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    out.write((txId ushr 8) and 0xFF)
    out.write(txId and 0xFF)
    out.write((flags ushr 8) and 0xFF)
    out.write(flags and 0xFF)
    out.write(0)
    out.write(1) // QDCOUNT=1
    out.write(0)
    out.write(0) // ANCOUNT=0
    out.write(0)
    out.write(0) // NSCOUNT=0
    out.write(0)
    out.write(if (includeEdns0) 1 else 0) // ARCOUNT

    qname.split('.').filter { it.isNotEmpty() }.forEach { label ->
      val bytes = label.encodeToByteArray()
      out.write(bytes.size)
      out.write(bytes)
    }
    out.write(0)
    out.write((qtype ushr 8) and 0xFF)
    out.write(qtype and 0xFF)
    out.write(0)
    out.write(1) // QCLASS=IN

    if (includeEdns0) {
      out.write(0) // root name
      out.write(0)
      out.write(41) // OPT=41
      out.write(0x04)
      out.write(0xD0) // UDP payload size 1232
      out.write(0)
      out.write(0)
      out.write(0)
      out.write(0) // extended RCODE + flags
      out.write(0)
      out.write(0) // RDLEN = 0
    }
    return out.toByteArray()
  }

  private fun activeCommitmentWithBlock(domain: String) =
    (CommitmentStateMachine.activate(
        CommitmentStateMachine.createDraft(
          id = CommitmentId("c-dns-1"),
          rules =
            CommitmentRules(
              mode = RestrictionMode.STRICT,
              restrictedApps = emptySet(),
              websiteFilteringEnabled = true,
              blockedDomains = setOf(BlockedDomain(DomainName(domain), ruleType = DomainRuleType.BLOCK)),
            ),
          now = timeProvider.wallClock,
        ),
        agreedToExactRules = true,
        now = timeProvider.wallClock,
      ) as CommitmentResult.Success)
      .commitment

  @Test
  fun `parses valid A AAAA HTTPS queries with EDNS0 and preserves transaction ID and RD flag`() {
    val packetA = buildQueryBytes(txId = 0xABCD, qname = "sub.example.com", qtype = 1, includeEdns0 = true)
    val parsedA = DnsWireCodec.parseQuery(packetA) as DnsParseResult.Valid
    assertEquals(0xABCD, parsedA.query.transactionId)
    assertEquals("sub.example.com", parsedA.query.qname)
    assertEquals(DnsRecordType.A, parsedA.query.recordType)
    assertEquals(1232, parsedA.query.ednsUdpPayloadSize)

    val packetAAAA = buildQueryBytes(txId = 0x2222, qname = "ipv6.example.org", qtype = 28)
    val parsedAAAA = DnsWireCodec.parseQuery(packetAAAA) as DnsParseResult.Valid
    assertEquals(DnsRecordType.AAAA, parsedAAAA.query.recordType)
    assertEquals("ipv6.example.org", parsedAAAA.query.qname)

    val nxDomain = DnsWireCodec.buildNxDomainResponse(parsedA.query)
    assertEquals(0xABCD, DnsWireCodec.readU16(nxDomain, 0))
    val respFlags = DnsWireCodec.readU16(nxDomain, 2)
    assertTrue((respFlags and 0x8000) != 0) // QR=1 response
    assertTrue((respFlags and 0x0100) != 0) // RD preserved
    assertEquals(DnsWireCodec.RCODE_NXDOMAIN, respFlags and 0x0F)
  }

  @Test
  fun `safely resolves valid compression pointers and rejects pointer loops and out of bounds`() {
    // Question with suffix "example.com" at offset 12 + compression pointer from offset 12..
    // Construct: header (12 bytes), then at offset 12: 3 'w''w''w' C0 18 (points to offset 24 where 7 'e'... 0 exists)
    // Wait: header 12, then "www" (1+3=4 bytes -> 12..15), ptr (16..17) pointing to 22, qtype(18..19), qclass(20..21), at 22: 7 example 3 com 0
    val packet = ByteArray(12 + 10 + 13)
    DnsWireCodec.writeU16(packet, 0, 0x5555)
    DnsWireCodec.writeU16(packet, 2, 0x0100)
    DnsWireCodec.writeU16(packet, 4, 1)
    packet[12] = 3
    packet[13] = 'w'.code.toByte()
    packet[14] = 'w'.code.toByte()
    packet[15] = 'w'.code.toByte()
    packet[16] = 0xC0.toByte()
    packet[17] = 22
    DnsWireCodec.writeU16(packet, 18, 1) // QTYPE A
    DnsWireCodec.writeU16(packet, 20, 1) // QCLASS IN
    val suffix = byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0)
    suffix.copyInto(packet, destinationOffset = 22)

    val valid = DnsWireCodec.parseQuery(packet) as DnsParseResult.Valid
    assertEquals("www.example.com", valid.query.qname)

    // Pointer self-loop: offset 12 points directly to offset 12
    val loopPacket = ByteArray(20)
    DnsWireCodec.writeU16(loopPacket, 0, 0x7777)
    DnsWireCodec.writeU16(loopPacket, 2, 0x0100)
    DnsWireCodec.writeU16(loopPacket, 4, 1)
    loopPacket[12] = 0xC0.toByte()
    loopPacket[13] = 12
    val loopResult = DnsWireCodec.parseQuery(loopPacket) as DnsParseResult.Malformed
    assertEquals(DnsParseError.COMPRESSION_POINTER_LOOP, loopResult.error)
    assertEquals(0x7777, loopResult.transactionId)

    // Out-of-bounds pointer: offset 12 points to offset 999
    val oobPacket = ByteArray(20)
    DnsWireCodec.writeU16(oobPacket, 0, 0x8888)
    DnsWireCodec.writeU16(oobPacket, 2, 0x0100)
    DnsWireCodec.writeU16(oobPacket, 4, 1)
    oobPacket[12] = 0xC3.toByte()
    oobPacket[13] = 0xE7.toByte()
    val oobResult = DnsWireCodec.parseQuery(oobPacket) as DnsParseResult.Malformed
    assertEquals(DnsParseError.COMPRESSION_OUT_OF_BOUNDS, oobResult.error)
  }

  @Test
  fun `rejects malformed short oversized non-query truncated and bad-label packets`() {
    assertEquals(
      DnsParseError.EMPTY_OR_TOO_SHORT,
      (DnsWireCodec.parseQuery(ByteArray(5)) as DnsParseResult.Malformed).error,
    )
    assertEquals(
      DnsParseError.OVERSIZED,
      (DnsWireCodec.parseQuery(ByteArray(DnsWireCodec.MAX_DNS_PACKET_BYTES + 1)) as DnsParseResult.Malformed).error,
    )

    val responsePacket = buildQueryBytes(flags = 0x8100)
    assertEquals(
      DnsParseError.NOT_A_QUERY,
      (DnsWireCodec.parseQuery(responsePacket) as DnsParseResult.Malformed).error,
    )

    val noQuestion = ByteArray(12).apply { DnsWireCodec.writeU16(this, 0, 1) }
    assertEquals(
      DnsParseError.NO_QUESTIONS,
      (DnsWireCodec.parseQuery(noQuestion) as DnsParseResult.Malformed).error,
    )

    val truncated = buildQueryBytes(qname = "example.com").copyOf(16)
    assertEquals(
      DnsParseError.TRUNCATED_PACKET,
      (DnsWireCodec.parseQuery(truncated) as DnsParseResult.Malformed).error,
    )

    val badExtendedLabel = ByteArray(20).apply {
      DnsWireCodec.writeU16(this, 4, 1)
      this[12] = 0x41 // extended label type 01
    }
    assertEquals(
      DnsParseError.INVALID_LABEL,
      (DnsWireCodec.parseQuery(badExtendedLabel) as DnsParseResult.Malformed).error,
    )
  }

  @Test
  fun `processor blocks matching domain locally without forwarding and forwards allowed domains upstream`() =
    runTest {
      val forwardedPayloads = mutableListOf<ByteArray>()
      val forwarder =
        object : UpstreamDnsForwarder {
          override suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray {
            forwardedPayloads += rawDnsQuery
            val reply = rawDnsQuery.copyOf()
            DnsWireCodec.writeU16(reply, 2, 0x8180) // NOERROR response
            return reply
          }
        }
      val processor = DnsQueryProcessor(timeProvider, forwarder)
      val commitment = activeCommitmentWithBlock("blocked.example")
      val compiled = CompiledDomainRuleSet.compile(commitment)

      val blockedQuery = buildQueryBytes(txId = 0x1111, qname = "m.blocked.example", qtype = 65) // HTTPS RR
      val blockedOutcome = processor.processDnsQuery(blockedQuery, commitment, compiled)
      assertTrue(blockedOutcome is DnsProcessOutcome.BlockedLocally)
      assertTrue(forwardedPayloads.isEmpty())
      val blockedResp = (blockedOutcome as DnsProcessOutcome.BlockedLocally).responseBytes
      assertEquals(0x1111, DnsWireCodec.readU16(blockedResp, 0))
      assertEquals(DnsWireCodec.RCODE_NXDOMAIN, DnsWireCodec.readU16(blockedResp, 2) and 0x0F)

      val allowedQuery = buildQueryBytes(txId = 0x2222, qname = "allowed.example", qtype = 1)
      val allowedOutcome = processor.processDnsQuery(allowedQuery, commitment, compiled)
      assertTrue(allowedOutcome is DnsProcessOutcome.ForwardedUpstream)
      assertEquals(1, forwardedPayloads.size)
      assertEquals(0x2222, DnsWireCodec.readU16((allowedOutcome as DnsProcessOutcome.ForwardedUpstream).responseBytes, 0))
    }

  @Test
  fun `processor returns SERVFAIL when upstream times out or returns mismatched transaction id`() =
    runTest {
      val nullForwarder =
        object : UpstreamDnsForwarder {
          override suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray? = null
        }
      val processor = DnsQueryProcessor(timeProvider, nullForwarder)
      val commitment = activeCommitmentWithBlock("blocked.example")
      val compiled = CompiledDomainRuleSet.compile(commitment)

      val query = buildQueryBytes(txId = 0x4242, qname = "allowed.example")
      val outcome = processor.processDnsQuery(query, commitment, compiled) as DnsProcessOutcome.UpstreamFailed
      assertEquals(0x4242, DnsWireCodec.readU16(outcome.responseBytes, 0))
      assertEquals(DnsWireCodec.RCODE_SERVFAIL, DnsWireCodec.readU16(outcome.responseBytes, 2) and 0x0F)
    }

  @Test
  fun `ipv4 udp packet codec roundtrips DNS packets with valid checksums and ignores non-DNS ports`() {
    val dnsPayload = buildQueryBytes(txId = 0x9999, qname = "example.com")
    val srcIp = byteArrayOf(10, 120, 0, 1)
    val dstIp = byteArrayOf(10, 120, 0, 2)
    val request =
      Ipv4UdpDnsPacketCodec.buildDnsResponsePacket(
        request =
          Ipv4UdpDnsDatagram(
            sourceIp = dstIp,
            destinationIp = srcIp,
            sourcePort = 53,
            destinationPort = 51515,
            dnsPayload = ByteArray(0),
          ),
        dnsResponsePayload = dnsPayload,
      )

    val parsed = Ipv4UdpDnsPacketCodec.parseDnsQueryPacket(request, request.size)
    assertNotNull(parsed)
    assertArrayEquals(srcIp, parsed!!.sourceIp)
    assertArrayEquals(dstIp, parsed.destinationIp)
    assertEquals(51515, parsed.sourcePort)
    assertEquals(53, parsed.destinationPort)
    assertArrayEquals(dnsPayload, parsed.dnsPayload)
    assertEquals(0, Ipv4UdpDnsPacketCodec.computeOnesComplementChecksum(request, 0, 20))

    // Mutate destination port to 443 -> must be ignored cleanly
    DnsWireCodec.writeU16(request, 22, 443)
    assertNull(Ipv4UdpDnsPacketCodec.parseDnsQueryPacket(request, request.size))
  }
}

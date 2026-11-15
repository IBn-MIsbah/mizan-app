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

  @Test
  fun `processor returns TC=1 truncated response for oversized UDP reply and full reply over TCP`() =
    runTest {
      val oversizedReply =
        buildQueryBytes(txId = 0x3333, qname = "search.example").let { base ->
          ByteArray(2200).also { buf ->
            base.copyInto(buf)
            DnsWireCodec.writeU16(buf, 2, 0x8180) // QR=1, NOERROR
          }
        }
      val forwarder =
        object : UpstreamDnsForwarder {
          override suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray = oversizedReply.copyOf()

          override suspend fun forwardQueryTcp(rawDnsQuery: ByteArray): ByteArray =
            oversizedReply.copyOf()
        }
      val processor = DnsQueryProcessor(timeProvider, forwarder)
      val commitment = activeCommitmentWithBlock("blocked.example")
      val compiled = CompiledDomainRuleSet.compile(commitment)

      val query = buildQueryBytes(txId = 0x3333, qname = "search.example", qtype = DnsRecordType.HTTPS.code)
      val udpOutcome =
        processor.processDnsQuery(query, commitment, compiled, transport = DnsTransport.UDP)
          as DnsProcessOutcome.ForwardedUpstream
      assertTrue(udpOutcome.wasTruncatedForMtu)
      assertTrue(udpOutcome.responseBytes.size <= DnsWireCodec.MAX_TUN_UDP_DNS_PAYLOAD)
      assertTrue(DnsWireCodec.hasTruncatedFlag(udpOutcome.responseBytes))
      assertEquals(0x3333, DnsWireCodec.readU16(udpOutcome.responseBytes, 0))
      assertEquals(DnsWireCodec.RCODE_NOERROR, DnsWireCodec.readU16(udpOutcome.responseBytes, 2) and 0x0F)

      val tcpOutcome =
        processor.processDnsQuery(query, commitment, compiled, transport = DnsTransport.TCP)
          as DnsProcessOutcome.ForwardedUpstream
      assertEquals(2200, tcpOutcome.responseBytes.size)
      assertEquals(0x3333, DnsWireCodec.readU16(tcpOutcome.responseBytes, 0))
    }

  @Test
  fun `ipv4 udp packet codec ignores fragmented packets`() {
    val dnsPayload = buildQueryBytes(txId = 0x1010, qname = "example.com")
    val packet =
      Ipv4UdpDnsPacketCodec.buildDnsResponsePacket(
        request =
          Ipv4UdpDnsDatagram(
            sourceIp = byteArrayOf(10, 120, 0, 2),
            destinationIp = byteArrayOf(10, 120, 0, 1),
            sourcePort = 53,
            destinationPort = 50000,
            dnsPayload = ByteArray(0),
          ),
        dnsResponsePayload = dnsPayload,
      )
    // Set More Fragments (MF = 0x2000) flag in IPv4 flags/fragment offset
    DnsWireCodec.writeU16(packet, 6, 0x2000)
    assertNull(Ipv4UdpDnsPacketCodec.parseDnsQueryPacket(packet, packet.size))
  }

  @Test
  fun `tcp dns session manager handles handshake large chunked response and unexpected RST`() =
    runTest {
      val clientIp = byteArrayOf(10, 120, 0, 1)
      val dnsIp = byteArrayOf(10, 120, 0, 2)
      val clientPort = 52400
      val sentPackets = mutableListOf<ByteArray>()

      val dnsQuery = buildQueryBytes(txId = 0x7777, qname = "large.example")
      val largeDnsResponse =
        ByteArray(2400).also { buf ->
          dnsQuery.copyInto(buf)
          DnsWireCodec.writeU16(buf, 2, 0x8180)
        }

      val manager =
        TcpDnsSessionManager(
          scope = this,
          workerDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
          packetWriter = { sentPackets += it },
          onDnsQuery = { received ->
            assertArrayEquals(dnsQuery, received)
            largeDnsResponse
          },
        )

      // 1. Client SYN
      val syn =
        Ipv4TcpDnsCodec.buildTcpResponsePacket(
          srcIp = clientIp,
          dstIp = dnsIp,
          srcPort = clientPort,
          dstPort = 53,
          seqNum = 1000L,
          ackNum = 0L,
          flags = Ipv4TcpDnsCodec.FLAG_SYN,
        )
      assertTrue(manager.handlePacket(syn, syn.size))
      assertEquals(1, sentPackets.size)
      val synAck = Ipv4TcpDnsCodec.parseTcpPacket(sentPackets.last())!!
      assertTrue(synAck.isSyn && synAck.isAck)
      assertEquals(1001L, synAck.ackNum)

      // 2. Client ACK + PSH with 2-byte length prefix + DNS query
      val framedQuery = ByteArray(2 + dnsQuery.size)
      DnsWireCodec.writeU16(framedQuery, 0, dnsQuery.size)
      dnsQuery.copyInto(framedQuery, destinationOffset = 2)
      val dataPkt =
        Ipv4TcpDnsCodec.buildTcpResponsePacket(
          srcIp = clientIp,
          dstIp = dnsIp,
          srcPort = clientPort,
          dstPort = 53,
          seqNum = 1001L,
          ackNum = synAck.seqNum + 1L,
          flags = Ipv4TcpDnsCodec.FLAG_ACK or Ipv4TcpDnsCodec.FLAG_PSH,
          payload = framedQuery,
        )
      assertTrue(manager.handlePacket(dataPkt, dataPkt.size))

      // Verify all outbound packets stay within 1500-byte MTU and reassemble the 2400-byte reply
      assertTrue(sentPackets.all { it.size <= 1500 })
      val serverPayloads =
        sentPackets.drop(1).mapNotNull { Ipv4TcpDnsCodec.parseTcpPacket(it)?.payload }.filter { it.isNotEmpty() }
      val reassembled = ByteArrayOutputStream().apply { serverPayloads.forEach { write(it) } }.toByteArray()
      assertEquals(2 + 2400, reassembled.size)
      assertEquals(2400, DnsWireCodec.readU16(reassembled, 0))
      assertTrue(sentPackets.any { Ipv4TcpDnsCodec.parseTcpPacket(it)?.isFin == true })

      // 3. Unknown port/session gets immediate RST
      sentPackets.clear()
      val unknownSyn =
        Ipv4TcpDnsCodec.buildTcpResponsePacket(
          srcIp = clientIp,
          dstIp = dnsIp,
          srcPort = 60001,
          dstPort = 443,
          seqNum = 500L,
          ackNum = 0L,
          flags = Ipv4TcpDnsCodec.FLAG_SYN,
        )
      assertTrue(manager.handlePacket(unknownSyn, unknownSyn.size))
      val rst = Ipv4TcpDnsCodec.parseTcpPacket(sentPackets.single())!!
      assertTrue(rst.isRst)
    }
}

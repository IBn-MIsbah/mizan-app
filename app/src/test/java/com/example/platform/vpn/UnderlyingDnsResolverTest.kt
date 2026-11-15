package com.example.platform.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.platform.dns.DnsTransport
import com.example.platform.dns.DnsWireCodec
import com.example.testing.FakeDispatcherProvider
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnderlyingDnsResolverTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun buildQuery(txId: Int, qname: String = "example.com"): ByteArray {
    val out = ByteArrayOutputStream()
    out.write((txId ushr 8) and 0xFF)
    out.write(txId and 0xFF)
    out.write(0x01)
    out.write(0x00)
    out.write(0)
    out.write(1)
    out.write(0)
    out.write(0)
    out.write(0)
    out.write(0)
    out.write(0)
    out.write(0)
    qname.split('.').forEach { label ->
      val bytes = label.encodeToByteArray()
      out.write(bytes.size)
      out.write(bytes)
    }
    out.write(0)
    out.write(0)
    out.write(1)
    out.write(0)
    out.write(1)
    return out.toByteArray()
  }

  @Test
  fun `sanitizeAndOrderDnsServers preserves IPv6-only servers and orders IPv4 before IPv6 on dual stack`() {
    val v6a = InetAddress.getByName("2001:4860:4860::8888")
    val v6b = InetAddress.getByName("2001:4860:4860::8844")
    val v4 = InetAddress.getByName("192.0.2.53")
    val unspecified = InetAddress.getByName("0.0.0.0")
    val multicast = InetAddress.getByName("224.0.0.251")

    assertEquals(
      listOf(v6a, v6b),
      UnderlyingDnsResolver.sanitizeAndOrderDnsServers(listOf(v6a, unspecified, v6b, multicast)),
    )
    assertEquals(
      listOf(v4, v6a),
      UnderlyingDnsResolver.sanitizeAndOrderDnsServers(listOf(v6a, v4)),
    )
  }

  @Test
  fun `selectRankedDnsConfigs prioritizes validated non-suspended active networks over unvalidated networks`() {
    val v6OnlyDns = listOf(InetAddress.getByName("2001:db8::53"))
    val captiveDns = listOf(InetAddress.getByName("192.0.2.10"))
    val suspendedDns = listOf(InetAddress.getByName("192.0.2.20"))

    val ranked =
      UnderlyingDnsResolver.selectRankedDnsConfigs(
        listOf(
          NetworkDnsCandidate(
            network = null,
            dnsServers = captiveDns,
            isPrivateDnsActive = false,
            isActiveDefault = true,
            isValidated = false,
            isNotSuspended = true,
          ),
          NetworkDnsCandidate(
            network = null,
            dnsServers = suspendedDns,
            isPrivateDnsActive = false,
            isActiveDefault = false,
            isValidated = true,
            isNotSuspended = false,
          ),
          NetworkDnsCandidate(
            network = null,
            dnsServers = v6OnlyDns,
            isPrivateDnsActive = false,
            isActiveDefault = false,
            isValidated = true,
            isNotSuspended = true,
          ),
        )
      )

    assertEquals(3, ranked.size)
    assertEquals(v6OnlyDns, ranked[0].dnsServers)
    assertTrue(ranked[0].isValidated)
    assertEquals(suspendedDns, ranked[1].dnsServers)
    assertEquals(captiveDns, ranked[2].dnsServers)
  }

  @Test
  fun `coalesces concurrent identical queries and falls back across IPv6 and secondary networks`() =
    runTest {
      val server1 = InetAddress.getByName("192.0.2.1")
      val server2 = InetAddress.getByName("2001:db8::53")
      val calls = AtomicInteger(0)

      val resolver =
        UnderlyingDnsResolver(
          context = context,
          dispatcherProvider = FakeDispatcherProvider(kotlinx.coroutines.Dispatchers.IO),
          socketProtector = { true },
          configsProvider = {
            listOf(
              UnderlyingDnsConfig(network = null, dnsServers = listOf(server1), isPrivateDnsActive = false, isValidated = false),
              UnderlyingDnsConfig(network = null, dnsServers = listOf(server2), isPrivateDnsActive = false, isValidated = true),
            )
          },
          serverQueryRunner = { _, server, query, _ ->
            calls.incrementAndGet()
            if (server == server1) {
              UnderlyingDnsResolver.SingleQueryOutcome.BindFailed
            } else {
              // Hold the single upstream lookup briefly so concurrent callers coalesce deterministically.
              Thread.sleep(200)
              val reply = query.copyOf()
              DnsWireCodec.writeU16(reply, 2, 0x8180)
              UnderlyingDnsResolver.SingleQueryOutcome.Success(reply)
            }
          },
        )

      val responses =
        (1..10)
          .map { idx ->
            async(kotlinx.coroutines.Dispatchers.IO) {
              resolver.forwardQuery(buildQuery(txId = 0x1000 + idx, qname = "bank.example"))
            }
          }
          .awaitAll()

      assertEquals(2, calls.get()) // 1 BindFailed on server1 + 1 coalesced Success on server2
      responses.forEachIndexed { idx, resp ->
        assertNotNull(resp)
        assertEquals(0x1000 + idx + 1, DnsWireCodec.extractTransactionId(resp!!))
      }

      // TCP transport also succeeds
      val tcpResp = resolver.forwardQueryTcp(buildQuery(txId = 0x9999, qname = "search.example"))
      assertNotNull(tcpResp)
      assertEquals(0x9999, DnsWireCodec.extractTransactionId(tcpResp!!))
    }
}

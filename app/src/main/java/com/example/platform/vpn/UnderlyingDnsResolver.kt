package com.example.platform.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.example.core.common.DispatcherProvider
import com.example.platform.dns.DnsWireCodec
import com.example.platform.dns.UpstreamDnsForwarder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.withContext

data class UnderlyingDnsConfig(
  val network: Network?,
  val dnsServers: List<InetAddress>,
  val isPrivateDnsActive: Boolean,
)

/**
 * Discovers the active non-VPN network's configured IPv4 DNS resolvers and forwards allowed UDP
 * DNS queries over sockets protected with [android.net.VpnService.protect] and bound to the
 * underlying network so ordinary traffic never loops or goes to hardcoded third-party resolvers.
 */
class UnderlyingDnsResolver(
  private val context: Context,
  private val dispatcherProvider: DispatcherProvider,
  private val socketProtector: (DatagramSocket) -> Boolean,
  private val timeoutMillis: Int = 2_500,
) : UpstreamDnsForwarder {

  fun inspectCurrentDnsConfig(): UnderlyingDnsConfig {
    val cm =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return UnderlyingDnsConfig(network = null, dnsServers = emptyList(), isPrivateDnsActive = false)

    val candidateNetworks = buildList {
      cm.activeNetwork?.let { add(it) }
      @Suppress("DEPRECATION")
      cm.allNetworks.forEach { if (it !in this) add(it) }
    }

    for (network in candidateNetworks) {
      val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
      if (
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
          !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      ) {
        continue
      }
      val linkProps = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
      val ipv4Dns = linkProps.dnsServers.filterIsInstance<Inet4Address>()
      val privateDns =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          linkProps.isPrivateDnsActive
        } else {
          false
        }
      if (ipv4Dns.isNotEmpty()) {
        return UnderlyingDnsConfig(
          network = network,
          dnsServers = ipv4Dns,
          isPrivateDnsActive = privateDns,
        )
      }
    }

    return UnderlyingDnsConfig(network = null, dnsServers = emptyList(), isPrivateDnsActive = false)
  }

  override suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray? =
    withContext(dispatcherProvider.io) {
      if (rawDnsQuery.size < DnsWireCodec.DNS_HEADER_SIZE || rawDnsQuery.size > DnsWireCodec.MAX_DNS_PACKET_BYTES) {
        return@withContext null
      }
      val config = inspectCurrentDnsConfig()
      if (config.dnsServers.isEmpty()) {
        return@withContext null
      }

      for (upstreamServer in config.dnsServers) {
        val response = querySingleServer(config.network, upstreamServer, rawDnsQuery)
        if (response != null) {
          return@withContext response
        }
      }
      null
    }

  private fun querySingleServer(
    underlyingNetwork: Network?,
    dnsServer: InetAddress,
    rawDnsQuery: ByteArray,
  ): ByteArray? {
    return try {
      DatagramSocket().use { socket ->
        if (!socketProtector(socket)) {
          return null
        }
        underlyingNetwork?.let { network ->
          runCatching { network.bindSocket(socket) }
        }
        socket.soTimeout = timeoutMillis
        val sendPacket = DatagramPacket(rawDnsQuery, rawDnsQuery.size, dnsServer, DNS_PORT)
        socket.send(sendPacket)

        val receiveBuffer = ByteArray(DnsWireCodec.MAX_DNS_PACKET_BYTES)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        socket.receive(receivePacket)
        receiveBuffer.copyOfRange(0, receivePacket.length)
      }
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    const val DNS_PORT = 53
  }
}

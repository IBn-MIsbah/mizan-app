package com.example.platform.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.example.core.common.DispatcherProvider
import com.example.platform.dns.DnsTransport
import com.example.platform.dns.DnsWireCodec
import com.example.platform.dns.UpstreamDnsForwarder
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class UnderlyingDnsConfig(
  val network: Network?,
  val dnsServers: List<InetAddress>,
  val isPrivateDnsActive: Boolean,
  val isValidated: Boolean = false,
)

internal data class NetworkDnsCandidate(
  val network: Network?,
  val dnsServers: List<InetAddress>,
  val isPrivateDnsActive: Boolean,
  val isActiveDefault: Boolean,
  val isValidated: Boolean,
  val isNotSuspended: Boolean,
)

/**
 * Discovers active non-VPN networks' configured IPv4 and IPv6 DNS resolvers, prioritizes validated
 * networks, caches discovery until network callbacks fire, coalesces concurrent identical in-flight
 * queries (without retaining any query history), and forwards allowed UDP/TCP DNS queries over
 * sockets protected with [android.net.VpnService.protect] and bound to the underlying network.
 */
class UnderlyingDnsResolver(
  private val context: Context,
  private val dispatcherProvider: DispatcherProvider,
  private val socketProtector: (DatagramSocket) -> Boolean,
  private val tcpSocketProtector: (Socket) -> Boolean = { true },
  private val timeoutMillis: Int = 1_200,
  private val overallTimeoutMillis: Long = 3_000L,
  private val cacheTtlMillis: Long = 5_000L,
  private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
  private val configsProvider: () -> List<UnderlyingDnsConfig> = {
    discoverNetworkDnsConfigs(context)
  },
  private val serverQueryRunner: ((UnderlyingDnsConfig, InetAddress, ByteArray, DnsTransport) -> SingleQueryOutcome)? =
    null,
) : UpstreamDnsForwarder {

  private data class CachedConfigs(
    val configs: List<UnderlyingDnsConfig>,
    val capturedAtMillis: Long,
  )

  private class QueryCoalesceKey(
    private val normalizedQueryWithoutTxId: ByteArray,
    private val transport: DnsTransport,
  ) {
    private val hash: Int = 31 * normalizedQueryWithoutTxId.contentHashCode() + transport.hashCode()

    override fun equals(other: Any?): Boolean =
      other is QueryCoalesceKey &&
        transport == other.transport &&
        normalizedQueryWithoutTxId.contentEquals(other.normalizedQueryWithoutTxId)

    override fun hashCode(): Int = hash
  }

  @Volatile private var cachedConfigs: CachedConfigs? = null
  private val inFlightQueries = ConcurrentHashMap<QueryCoalesceKey, CompletableDeferred<ByteArray?>>()

  fun invalidateCache() {
    cachedConfigs = null
  }

  fun inspectCurrentDnsConfig(): UnderlyingDnsConfig =
    inspectCandidateConfigs().firstOrNull()
      ?: UnderlyingDnsConfig(network = null, dnsServers = emptyList(), isPrivateDnsActive = false)

  fun inspectCandidateConfigs(): List<UnderlyingDnsConfig> {
    val now = nowMillisProvider()
    val existing = cachedConfigs
    if (existing != null && now - existing.capturedAtMillis <= cacheTtlMillis) {
      return existing.configs
    }
    val discovered = configsProvider()
    cachedConfigs = CachedConfigs(configs = discovered, capturedAtMillis = now)
    return discovered
  }

  override suspend fun forwardQuery(rawDnsQuery: ByteArray): ByteArray? =
    forwardWithCoalescing(rawDnsQuery = rawDnsQuery, transport = DnsTransport.UDP)

  override suspend fun forwardQueryTcp(rawDnsQuery: ByteArray): ByteArray? =
    forwardWithCoalescing(rawDnsQuery = rawDnsQuery, transport = DnsTransport.TCP)

  private suspend fun forwardWithCoalescing(
    rawDnsQuery: ByteArray,
    transport: DnsTransport,
  ): ByteArray? {
    if (
      rawDnsQuery.size < DnsWireCodec.DNS_HEADER_SIZE ||
        rawDnsQuery.size > DnsWireCodec.MAX_DNS_PACKET_BYTES
    ) {
      return null
    }
    val callerTxId = DnsWireCodec.extractTransactionId(rawDnsQuery) ?: return null
    val keyBytes = rawDnsQuery.copyOf().apply {
      this[0] = 0
      this[1] = 0
    }
    val coalesceKey = QueryCoalesceKey(keyBytes, transport)

    val existingDeferred = inFlightQueries[coalesceKey]
    if (existingDeferred != null) {
      val shared = withTimeoutOrNull(overallTimeoutMillis) { existingDeferred.await() } ?: return null
      return patchTransactionId(shared, callerTxId)
    }

    val ownDeferred = CompletableDeferred<ByteArray?>()
    val raced = inFlightQueries.putIfAbsent(coalesceKey, ownDeferred)
    if (raced != null) {
      val shared = withTimeoutOrNull(overallTimeoutMillis) { raced.await() } ?: return null
      return patchTransactionId(shared, callerTxId)
    }

    return try {
      val response =
        withContext(dispatcherProvider.io) {
          withTimeoutOrNull(overallTimeoutMillis) {
            executeUpstreamLookup(rawDnsQuery, transport)
          }
        }
      ownDeferred.complete(response)
      response?.let { patchTransactionId(it, callerTxId) }
    } catch (t: Throwable) {
      ownDeferred.complete(null)
      throw t
    } finally {
      inFlightQueries.remove(coalesceKey, ownDeferred)
    }
  }

  private fun executeUpstreamLookup(
    rawDnsQuery: ByteArray,
    transport: DnsTransport,
  ): ByteArray? {
    val configs = inspectCandidateConfigs()
    if (configs.isEmpty()) {
      return null
    }

    val deadlineMillis = nowMillisProvider() + overallTimeoutMillis
    for (config in configs) {
      for (upstreamServer in config.dnsServers) {
        if (nowMillisProvider() >= deadlineMillis) {
          return null
        }
        val outcome =
          serverQueryRunner?.invoke(config, upstreamServer, rawDnsQuery, transport)
            ?: when (transport) {
              DnsTransport.UDP -> querySingleServerUdp(config.network, upstreamServer, rawDnsQuery)
              DnsTransport.TCP -> querySingleServerTcp(config.network, upstreamServer, rawDnsQuery)
            }
        when (outcome) {
          is SingleQueryOutcome.Success -> return outcome.responseBytes
          SingleQueryOutcome.BindFailed -> {
            invalidateCache()
            break // Stale network binding; advance immediately to next candidate network.
          }
          SingleQueryOutcome.Failed -> continue
        }
      }
    }
    return null
  }

  sealed interface SingleQueryOutcome {
    data class Success(val responseBytes: ByteArray) : SingleQueryOutcome

    data object BindFailed : SingleQueryOutcome

    data object Failed : SingleQueryOutcome
  }

  private fun querySingleServerUdp(
    underlyingNetwork: Network?,
    dnsServer: InetAddress,
    rawDnsQuery: ByteArray,
  ): SingleQueryOutcome {
    return try {
      DatagramSocket().use { socket ->
        if (!socketProtector(socket)) {
          return SingleQueryOutcome.Failed
        }
        if (underlyingNetwork != null) {
          try {
            underlyingNetwork.bindSocket(socket)
          } catch (_: Exception) {
            return SingleQueryOutcome.BindFailed
          }
        }
        socket.soTimeout = timeoutMillis
        val sendPacket = DatagramPacket(rawDnsQuery, rawDnsQuery.size, dnsServer, DNS_PORT)
        socket.send(sendPacket)

        val receiveBuffer = ByteArray(DnsWireCodec.MAX_DNS_PACKET_BYTES)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        socket.receive(receivePacket)
        SingleQueryOutcome.Success(receiveBuffer.copyOfRange(0, receivePacket.length))
      }
    } catch (_: Exception) {
      SingleQueryOutcome.Failed
    }
  }

  private fun querySingleServerTcp(
    underlyingNetwork: Network?,
    dnsServer: InetAddress,
    rawDnsQuery: ByteArray,
  ): SingleQueryOutcome {
    return try {
      Socket().use { socket ->
        if (!tcpSocketProtector(socket)) {
          return SingleQueryOutcome.Failed
        }
        if (underlyingNetwork != null) {
          try {
            underlyingNetwork.bindSocket(socket)
          } catch (_: Exception) {
            return SingleQueryOutcome.BindFailed
          }
        }
        socket.soTimeout = timeoutMillis
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(dnsServer, DNS_PORT), timeoutMillis)

        val out = socket.getOutputStream()
        val framedQuery = ByteArray(2 + rawDnsQuery.size)
        DnsWireCodec.writeU16(framedQuery, 0, rawDnsQuery.size)
        rawDnsQuery.copyInto(framedQuery, destinationOffset = 2)
        out.write(framedQuery)
        out.flush()

        val input = DataInputStream(socket.getInputStream())
        val responseLen = input.readUnsignedShort()
        if (
          responseLen < DnsWireCodec.DNS_HEADER_SIZE ||
            responseLen > DnsWireCodec.MAX_TCP_DNS_PACKET_BYTES
        ) {
          return SingleQueryOutcome.Failed
        }
        val responseBytes = ByteArray(responseLen)
        input.readFully(responseBytes)
        SingleQueryOutcome.Success(responseBytes)
      }
    } catch (_: Exception) {
      SingleQueryOutcome.Failed
    }
  }

  private fun patchTransactionId(response: ByteArray, txId: Int): ByteArray =
    response.copyOf().apply { DnsWireCodec.writeU16(this, 0, txId) }

  companion object {
    const val DNS_PORT = 53

    fun discoverNetworkDnsConfigs(context: Context): List<UnderlyingDnsConfig> {
      val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          ?: return emptyList()

      val active = cm.activeNetwork
      val candidateNetworks = buildList {
        active?.let { add(it) }
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { if (it !in this) add(it) }
      }

      val candidates = ArrayList<NetworkDnsCandidate>(candidateNetworks.size)
      for (network in candidateNetworks) {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
        if (
          caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
          continue
        }
        val linkProps = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
        val filteredDns = sanitizeAndOrderDnsServers(linkProps.dnsServers)
        if (filteredDns.isEmpty()) continue

        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isNotSuspended =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
          } else {
            true
          }
        val privateDns =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProps.isPrivateDnsActive
          } else {
            false
          }

        candidates +=
          NetworkDnsCandidate(
            network = network,
            dnsServers = filteredDns,
            isPrivateDnsActive = privateDns,
            isActiveDefault = network == active,
            isValidated = isValidated,
            isNotSuspended = isNotSuspended,
          )
      }
      return selectRankedDnsConfigs(candidates)
    }

    internal fun sanitizeAndOrderDnsServers(rawServers: List<InetAddress>): List<InetAddress> {
      val usable =
        rawServers.filter { addr ->
          !addr.isAnyLocalAddress &&
            !addr.isMulticastAddress &&
            (addr is Inet4Address || addr is Inet6Address)
        }
      if (usable.isEmpty()) return emptyList()
      // Keep IPv4 first on dual-stack networks while preserving all IPv6 servers for IPv6-only
      // networks and fallback.
      val ipv4 = usable.filterIsInstance<Inet4Address>()
      val ipv6 = usable.filterIsInstance<Inet6Address>()
      return ipv4 + ipv6
    }

    internal fun selectRankedDnsConfigs(
      candidates: List<NetworkDnsCandidate>,
    ): List<UnderlyingDnsConfig> =
      candidates
        .filter { it.dnsServers.isNotEmpty() }
        .sortedWith(
          compareByDescending<NetworkDnsCandidate> { it.isValidated }
            .thenByDescending { it.isNotSuspended }
            .thenByDescending { it.isActiveDefault }
        )
        .map { candidate ->
          UnderlyingDnsConfig(
            network = candidate.network,
            dnsServers = candidate.dnsServers,
            isPrivateDnsActive = candidate.isPrivateDnsActive,
            isValidated = candidate.isValidated,
          )
        }
  }
}

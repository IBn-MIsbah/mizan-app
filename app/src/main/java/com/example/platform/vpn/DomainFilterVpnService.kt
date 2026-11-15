package com.example.platform.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.domain.filter.CompiledDomainRuleSet
import com.example.domain.model.Commitment
import com.example.domain.model.DomainCategory
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import com.example.platform.dns.DnsProcessOutcome
import com.example.platform.dns.DnsQueryProcessor
import com.example.platform.dns.DnsTransport
import com.example.platform.dns.DnsWireCodec
import com.example.platform.dns.Ipv4TcpDnsCodec
import com.example.platform.dns.Ipv4UdpDnsPacketCodec
import com.example.platform.dns.TcpDnsSessionManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Local-only DNS filtering VPN service.
 *
 * Route contract:
 * - Local TUN interface IPv4 address: 10.120.0.1/32
 * - Virtual DNS resolver address: 10.120.0.2
 * - Only route added: 10.120.0.2/32 (host route to the virtual DNS address)
 *
 * Ordinary HTTP/HTTPS/TCP/UDP device traffic is NOT routed through this TUN and is never forwarded
 * to any remote MIZAN server. Only DNS packets directed to 10.120.0.2:53 arrive on the TUN fd.
 */
@AndroidEntryPoint
class DomainFilterVpnService : VpnService() {

  @Inject lateinit var commitmentRepository: CommitmentRepository
  @Inject lateinit var domainFilterRepository: DomainFilterRepository
  @Inject lateinit var userPreferencesRepository: UserPreferencesRepository
  @Inject lateinit var securityEventRepository: SecurityEventRepository
  @Inject lateinit var vpnFilterMonitor: VpnFilterMonitor
  @Inject lateinit var timeProvider: TimeProvider
  @Inject lateinit var idGenerator: IdGenerator
  @Inject lateinit var dispatcherProvider: DispatcherProvider

  private lateinit var serviceScope: CoroutineScope
  private lateinit var upstreamDnsResolver: UnderlyingDnsResolver
  private lateinit var dnsProcessor: DnsQueryProcessor

  private val tunInterface = AtomicReference<ParcelFileDescriptor?>(null)
  private val activeCommitmentRef = AtomicReference<Commitment.Active?>(null)
  private val compiledRulesRef = AtomicReference(CompiledDomainRuleSet(emptyMap(), emptyMap()))
  private val tcpSessionManagerRef = AtomicReference<TcpDnsSessionManager?>(null)
  private val writeLock = Any()
  private val sessionMutex = Mutex()
  private val commandGeneration = AtomicLong(0L)
  private val isStopping = AtomicBoolean(false)
  private val packetSemaphore = Semaphore(permits = MAX_IN_FLIGHT_UDP_QUERIES)

  private val forwardedCounter = AtomicLong(0L)
  private val blockedCounter = AtomicLong(0L)
  private val upstreamFailureCounter = AtomicLong(0L)
  private val lastStatsPublishMillis = AtomicLong(0L)

  private var stateObserverJob: Job? = null
  private var packetLoopJob: Job? = null
  private var networkCallbackRegistered = false

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        upstreamDnsResolver.invalidateCache()
        refreshUnderlyingNetworkStatus()
      }

      override fun onLost(network: Network) {
        upstreamDnsResolver.invalidateCache()
        refreshUnderlyingNetworkStatus()
      }

      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        upstreamDnsResolver.invalidateCache()
        refreshUnderlyingNetworkStatus()
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        upstreamDnsResolver.invalidateCache()
        refreshUnderlyingNetworkStatus()
      }
    }

  override fun onCreate() {
    super.onCreate()
    serviceScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    upstreamDnsResolver =
      UnderlyingDnsResolver(
        context = applicationContext,
        dispatcherProvider = dispatcherProvider,
        socketProtector = { socket -> protect(socket) },
        tcpSocketProtector = { socket -> protect(socket) },
      )
    dnsProcessor =
      DnsQueryProcessor(
        timeProvider = timeProvider,
        upstreamForwarder = upstreamDnsResolver,
      )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val gen = commandGeneration.incrementAndGet()
    if (intent?.action == ACTION_STOP) {
      triggerStop(stoppedByUser = true, revoked = false, generation = gen)
      return START_NOT_STICKY
    }

    ensureNotificationChannel()
    enterForeground()
    vpnFilterMonitor.onServiceStarting()

    serviceScope.launch {
      sessionMutex.withLock {
        if (commandGeneration.get() == gen && tunInterface.get() == null) {
          isStopping.set(false)
          startFilteringSessionLocked(expectedGeneration = gen)
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onRevoke() {
    super.onRevoke()
    val gen = commandGeneration.incrementAndGet()
    val anotherVpn = detectAnotherActiveVpn()
    triggerStop(
      stoppedByUser = false,
      revoked = true,
      anotherVpnActive = anotherVpn,
      generation = gen,
    )
  }

  override fun onDestroy() {
    isStopping.set(true)
    unregisterNetworkCallbackSafely()
    tcpSessionManagerRef.getAndSet(null)?.closeAll()
    closeTunSafely()
    packetLoopJob?.cancel()
    stateObserverJob?.cancel()
    if (::serviceScope.isInitialized) {
      serviceScope.cancel()
    }
    super.onDestroy()
  }

  private suspend fun startFilteringSessionLocked(expectedGeneration: Long) {
    try {
      if (prepare(applicationContext) != null) {
        vpnFilterMonitor.onTunnelStopped(stoppedByUser = false)
        if (commandGeneration.get() == expectedGeneration) stopSelf()
        return
      }

      val prefs = userPreferencesRepository.preferences.first()
      if (!prefs.websiteFilteringConsented || prefs.vpnStoppedByUser) {
        vpnFilterMonitor.onTunnelStopped(stoppedByUser = prefs.vpnStoppedByUser)
        if (commandGeneration.get() == expectedGeneration) stopSelf()
        return
      }

      val active =
        when (val res = commitmentRepository.observeActiveCommitment().first()) {
          is PersistenceResult.Success -> res.data as? Commitment.Active
          is PersistenceResult.Failure -> null
        }

      val now = timeProvider.currentWallClock()
      if (active == null || active.hasReachedEnd(now) || !active.rules.hasConfiguredWebsiteFiltering()) {
        vpnFilterMonitor.onTunnelStopped(stoppedByUser = false)
        if (commandGeneration.get() == expectedGeneration) stopSelf()
        return
      }

      val categories = active.rules.blockedDomainCategories - DomainCategory.CUSTOM
      val datasetDomains =
        when (val dsRes = domainFilterRepository.getActiveDomainsForCategories(categories)) {
          is PersistenceResult.Success -> dsRes.data
          is PersistenceResult.Failure -> emptyMap()
        }

      if (commandGeneration.get() != expectedGeneration) return

      activeCommitmentRef.set(active)
      compiledRulesRef.set(CompiledDomainRuleSet.compile(active, datasetDomains))

      val builder =
        Builder()
          .setSession(getString(R.string.vpn_session_name))
          .addAddress(LOCAL_TUN_ADDRESS, 32)
          .addDnsServer(LOCAL_VIRTUAL_DNS_IP)
          .addRoute(LOCAL_VIRTUAL_DNS_IP, 32)
          .setMtu(TUN_MTU)
          .setBlocking(true)
          .allowBypass()

      runCatching { builder.addDisallowedApplication(packageName) }

      val pfd = builder.establish()
      if (pfd == null) {
        val conflict = detectAnotherActiveVpn()
        if (conflict) {
          recordNonSensitiveEvent(
            commitmentId = active.id,
            type = SecurityEventType.VPN_CONFLICT_DETECTED,
            summary = "VPN tunnel could not establish while another VPN is active",
          )
          vpnFilterMonitor.onTunnelRevoked(anotherVpnActive = true)
        } else {
          vpnFilterMonitor.onTunnelError(getString(R.string.vpn_error_establish_failed))
        }
        if (commandGeneration.get() == expectedGeneration) stopSelf()
        return
      }

      tunInterface.set(pfd)
      upstreamDnsResolver.invalidateCache()
      registerNetworkCallbackSafely()

      val dnsConfig = upstreamDnsResolver.inspectCurrentDnsConfig()
      vpnFilterMonitor.onTunnelEstablished(
        upstreamDnsCount = dnsConfig.dnsServers.size,
        isPrivateDnsActive = dnsConfig.isPrivateDnsActive,
      )
      recordNonSensitiveEvent(
        commitmentId = active.id,
        type = SecurityEventType.VPN_FILTER_STARTED,
        summary = "Local DNS filtering VPN started",
      )

      observeActiveStateContinuously()
      launchPacketLoop(pfd)
    } catch (_: Exception) {
      vpnFilterMonitor.onTunnelError(getString(R.string.vpn_error_establish_failed))
      if (commandGeneration.get() == expectedGeneration) stopSelf()
    }
  }

  private fun observeActiveStateContinuously() {
    stateObserverJob?.cancel()
    stateObserverJob =
      serviceScope.launch {
        combine(
            commitmentRepository.observeActiveCommitment(),
            domainFilterRepository.observeDatasets(),
            userPreferencesRepository.preferences,
          ) { activeRes, _, prefs ->
            activeRes to prefs
          }
          .collect { (activeRes, prefs) ->
            val active = (activeRes as? PersistenceResult.Success)?.data as? Commitment.Active
            val now = timeProvider.currentWallClock()
            if (
              active == null ||
                active.hasReachedEnd(now) ||
                !active.rules.hasConfiguredWebsiteFiltering() ||
                !prefs.websiteFilteringConsented ||
                prefs.vpnStoppedByUser
            ) {
              val gen = commandGeneration.incrementAndGet()
              triggerStop(stoppedByUser = prefs.vpnStoppedByUser, revoked = false, generation = gen)
              return@collect
            }

            val categories = active.rules.blockedDomainCategories - DomainCategory.CUSTOM
            val datasetDomains =
              when (val dsRes = domainFilterRepository.getActiveDomainsForCategories(categories)) {
                is PersistenceResult.Success -> dsRes.data
                is PersistenceResult.Failure -> emptyMap()
              }
            activeCommitmentRef.set(active)
            compiledRulesRef.set(CompiledDomainRuleSet.compile(active, datasetDomains))
          }
      }
  }

  private fun launchPacketLoop(pfd: ParcelFileDescriptor) {
    packetLoopJob?.cancel()
    forwardedCounter.set(0L)
    blockedCounter.set(0L)
    upstreamFailureCounter.set(0L)
    lastStatsPublishMillis.set(0L)

    packetLoopJob =
      serviceScope.launch(dispatcherProvider.io) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val writePacketToTun: (ByteArray) -> Unit = { packet ->
          synchronized(writeLock) {
            if (!isStopping.get() && tunInterface.get() === pfd) {
              runCatching {
                output.write(packet)
                output.flush()
              }
            }
          }
        }
        val tcpManager =
          TcpDnsSessionManager(
            scope = serviceScope,
            workerDispatcher = dispatcherProvider.io,
            packetWriter = writePacketToTun,
            onDnsQuery = { rawQuery -> executeDnsQuery(rawQuery, DnsTransport.TCP) },
          )
        tcpSessionManagerRef.getAndSet(tcpManager)?.closeAll()

        val readBuffer = ByteArray(DnsWireCodec.MAX_DNS_PACKET_BYTES + 128)
        try {
          while (!isStopping.get() && tunInterface.get() === pfd) {
            val bytesRead =
              try {
                input.read(readBuffer)
              } catch (_: Exception) {
                break
              }
            if (bytesRead <= 0) {
              if (bytesRead < 0) break
              continue
            }
            if (bytesRead < MIN_IPV4_HEADER_BYTES) continue

            val ipVersion = (readBuffer[0].toInt() ushr 4) and 0x0F
            if (ipVersion != 4) continue
            val protocol = readBuffer[9].toInt() and 0xFF
            val packetCopy = readBuffer.copyOfRange(0, bytesRead)

            when (protocol) {
              Ipv4TcpDnsCodec.PROTOCOL_TCP -> {
                tcpManager.handlePacket(packetCopy, packetCopy.size)
              }
              PROTOCOL_UDP -> {
                val datagram =
                  Ipv4UdpDnsPacketCodec.parseDnsQueryPacket(packetCopy, packetCopy.size)
                    ?: continue
                // Never block the TUN reader loop with suspending acquire(); shed only under extreme
                // saturation so local blocks and healthy queries are never head-of-line stalled.
                if (!packetSemaphore.tryAcquire()) continue
                launch(dispatcherProvider.io) {
                  try {
                    val dnsResponsePayload =
                      executeDnsQuery(datagram.dnsPayload, DnsTransport.UDP)
                    if (dnsResponsePayload != null && !isStopping.get()) {
                      val replyPacket =
                        Ipv4UdpDnsPacketCodec.buildDnsResponsePacket(datagram, dnsResponsePayload)
                      if (replyPacket.size <= TUN_MTU) {
                        writePacketToTun(replyPacket)
                      }
                    }
                  } finally {
                    packetSemaphore.release()
                  }
                }
              }
            }
          }
        } finally {
          tcpManager.closeAll()
          tcpSessionManagerRef.compareAndSet(tcpManager, null)
        }
      }
  }

  private suspend fun executeDnsQuery(
    rawDnsQuery: ByteArray,
    transport: DnsTransport,
  ): ByteArray? {
    val outcome =
      dnsProcessor.processDnsQuery(
        rawDnsQuery = rawDnsQuery,
        commitment = activeCommitmentRef.get(),
        compiledRules = compiledRulesRef.get(),
        transport = transport,
        maxUdpResponseBytes = DnsWireCodec.MAX_TUN_UDP_DNS_PAYLOAD,
      )
    val shouldPublishNow: Boolean
    val responseBytes: ByteArray?
    when (outcome) {
      is DnsProcessOutcome.BlockedLocally -> {
        blockedCounter.incrementAndGet()
        shouldPublishNow = true
        responseBytes = outcome.responseBytes
      }
      is DnsProcessOutcome.ForwardedUpstream -> {
        forwardedCounter.incrementAndGet()
        shouldPublishNow = false
        responseBytes = outcome.responseBytes
      }
      is DnsProcessOutcome.UpstreamFailed -> {
        upstreamFailureCounter.incrementAndGet()
        shouldPublishNow = true
        responseBytes = outcome.responseBytes
      }
      is DnsProcessOutcome.MalformedDropped -> {
        shouldPublishNow = false
        responseBytes = outcome.formErrBytes
      }
    }
    publishAggregateStatsIfDue(force = shouldPublishNow)
    return responseBytes
  }

  private fun publishAggregateStatsIfDue(force: Boolean) {
    val nowMs = System.currentTimeMillis()
    val prev = lastStatsPublishMillis.get()
    if (force || nowMs - prev >= STATS_PUBLISH_INTERVAL_MS) {
      if (lastStatsPublishMillis.compareAndSet(prev, nowMs)) {
        vpnFilterMonitor.onDnsStatsUpdated(
          forwardedCount = forwardedCounter.get(),
          blockedCount = blockedCounter.get(),
          upstreamFailureCount = upstreamFailureCounter.get(),
        )
      }
    }
  }

  private fun triggerStop(
    stoppedByUser: Boolean,
    revoked: Boolean,
    anotherVpnActive: Boolean = false,
    generation: Long,
  ) {
    isStopping.set(true)
    val commitmentId = activeCommitmentRef.get()?.id
    unregisterNetworkCallbackSafely()
    tcpSessionManagerRef.getAndSet(null)?.closeAll()
    closeTunSafely()
    packetLoopJob?.cancel()
    stateObserverJob?.cancel()

    if (::serviceScope.isInitialized) {
      serviceScope.launch {
        sessionMutex.withLock {
          stopFilteringLocked(
            commitmentId = commitmentId,
            stoppedByUser = stoppedByUser,
            revoked = revoked,
            anotherVpnActive = anotherVpnActive,
            expectedGeneration = generation,
          )
        }
      }
    } else {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private suspend fun stopFilteringLocked(
    commitmentId: com.example.domain.model.CommitmentId?,
    stoppedByUser: Boolean,
    revoked: Boolean,
    anotherVpnActive: Boolean,
    expectedGeneration: Long,
  ) {
    when {
      anotherVpnActive -> {
        recordNonSensitiveEvent(
          commitmentId = commitmentId,
          type = SecurityEventType.VPN_CONFLICT_DETECTED,
          summary = "MIZAN DNS filter VPN displaced by another active VPN",
        )
        vpnFilterMonitor.onTunnelRevoked(anotherVpnActive = true)
      }
      revoked -> {
        recordNonSensitiveEvent(
          commitmentId = commitmentId,
          type = SecurityEventType.VPN_PERMISSION_REVOKED,
          summary = "MIZAN local DNS filtering VPN permission revoked",
        )
        vpnFilterMonitor.onTunnelRevoked(anotherVpnActive = false)
      }
      else -> {
        recordNonSensitiveEvent(
          commitmentId = commitmentId,
          type = SecurityEventType.VPN_FILTER_STOPPED,
          summary =
            if (stoppedByUser) "Local DNS filtering VPN stopped by user"
            else "Local DNS filtering VPN stopped after commitment ended",
        )
        vpnFilterMonitor.onTunnelStopped(stoppedByUser = stoppedByUser)
      }
    }
    if (commandGeneration.get() == expectedGeneration) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private suspend fun recordNonSensitiveEvent(
    commitmentId: com.example.domain.model.CommitmentId?,
    type: SecurityEventType,
    summary: String,
  ) {
    runCatching {
      securityEventRepository.recordEvent(
        SecurityEvent(
          id = SecurityEventId(idGenerator.nextId()),
          commitmentId = commitmentId,
          eventType = type,
          occurredAt = timeProvider.currentWallClock(),
          summary = summary,
        )
      )
    }
  }

  private fun refreshUnderlyingNetworkStatus() {
    if (isStopping.get() || tunInterface.get() == null) return
    val dnsConfig = upstreamDnsResolver.inspectCurrentDnsConfig()
    vpnFilterMonitor.onTunnelEstablished(
      upstreamDnsCount = dnsConfig.dnsServers.size,
      isPrivateDnsActive = dnsConfig.isPrivateDnsActive,
    )
  }

  private fun detectAnotherActiveVpn(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val networks =
      try {
        @Suppress("DEPRECATION")
        cm.allNetworks
      } catch (_: RuntimeException) {
        return false
      }
    return networks.any { network ->
      val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return@any false
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
  }

  private fun registerNetworkCallbackSafely() {
    if (networkCallbackRegistered) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(networkCallback)
      networkCallbackRegistered = true
    }
  }

  private fun unregisterNetworkCallbackSafely() {
    if (!networkCallbackRegistered) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching { cm.unregisterNetworkCallback(networkCallback) }
    networkCallbackRegistered = false
  }

  private fun closeTunSafely() {
    val pfd = tunInterface.getAndSet(null) ?: return
    runCatching { pfd.close() }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java) ?: return
      val existing = manager.getNotificationChannel(CHANNEL_ID)
      if (existing == null) {
        val channel =
          NotificationChannel(
              CHANNEL_ID,
              getString(R.string.vpn_notification_channel_name),
              NotificationManager.IMPORTANCE_LOW,
            )
            .apply {
              description = getString(R.string.vpn_notification_channel_desc)
              setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
      }
    }
  }

  private fun enterForeground() {
    val launchIntent = Intent(this, MainActivity::class.java)
    val contentPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val stopIntent = Intent(this, DomainFilterVpnService::class.java).setAction(ACTION_STOP)
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        1,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.vpn_notification_title))
        .setContentText(getString(R.string.vpn_notification_body))
        .setContentIntent(contentPendingIntent)
        .setOngoing(true)
        .setSilent(true)
        .addAction(0, getString(R.string.vpn_stop_action), stopPendingIntent)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  companion object {
    const val ACTION_START = "com.ibnmisbah.mizan.action.START_DNS_FILTER"
    const val ACTION_STOP = "com.ibnmisbah.mizan.action.STOP_DNS_FILTER"
    const val LOCAL_TUN_ADDRESS = "10.120.0.1"
    const val LOCAL_VIRTUAL_DNS_IP = "10.120.0.2"
    const val TUN_MTU = 1500
    private const val MIN_IPV4_HEADER_BYTES = 20
    private const val PROTOCOL_UDP = 17
    private const val MAX_IN_FLIGHT_UDP_QUERIES = 64
    private const val STATS_PUBLISH_INTERVAL_MS = 1_000L
    private const val CHANNEL_ID = "mizan_vpn_filter"
    private const val NOTIFICATION_ID = 4001

    fun start(context: Context) {
      val intent = Intent(context, DomainFilterVpnService::class.java).setAction(ACTION_START)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, DomainFilterVpnService::class.java).setAction(ACTION_STOP)
      runCatching { context.startService(intent) }
    }
  }
}

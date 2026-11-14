package com.example.testing

import android.content.Intent
import android.provider.Settings
import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.domain.allowance.SharedAllowanceAccountant
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.AppPreferences
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.DailyUsage
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.SecurityEvent
import com.example.domain.model.ThemePreference
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageIntegrityCheckpoint
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.DatasetImportResult
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.InstalledAppRepository
import com.example.domain.repository.InstalledAppsResult
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.enforcement.BlockingLaunchRequest
import com.example.platform.enforcement.BlockingUiLauncher
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.platform.health.AndroidEnforcementHealthMonitor
import com.example.platform.health.AndroidUsageAccessMonitor
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.platform.health.UsageAccessSnapshot
import com.example.platform.usage.BootSessionProvider
import com.example.platform.usage.DeviceInteractiveStateProvider
import com.example.platform.usage.ForegroundUsageTracker
import com.example.platform.usage.OsUsageEvent
import com.example.platform.usage.UsageStatsHistorySource
import com.example.platform.usage.UsageStatsReconciler
import com.example.platform.vpn.VpnFilterMonitor
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnServiceLifecycleStatus
import com.example.platform.vpn.VpnStartRequestOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeDispatcherProvider(
  private val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : DispatcherProvider {
  override val main: CoroutineDispatcher get() = dispatcher
  override val io: CoroutineDispatcher get() = dispatcher
  override val default: CoroutineDispatcher get() = dispatcher
}

class FakeTimeProvider(
  initialWallClock: WallClockPoint =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
      utcOffsetMinutes = 0,
      zoneId = "UTC",
    ),
  initialMonotonic: MonotonicTimestamp = MonotonicTimestamp(1_000L),
) : TimeProvider {
  var wallClock: WallClockPoint = initialWallClock
  var monotonic: MonotonicTimestamp = initialMonotonic

  override fun currentWallClock(): WallClockPoint = wallClock

  override fun currentMonotonic(): MonotonicTimestamp = monotonic
}

class FakeIdGenerator(private var counter: Int = 1) : IdGenerator {
  override fun nextId(): String = "id-${counter++}"
}

class FakeCommitmentRepository(initialCommitments: List<Commitment> = emptyList()) :
  CommitmentRepository {

  private val store = MutableStateFlow(initialCommitments)
  var saveFailure: PersistenceError? = null
  var observeFailure: PersistenceError? = null
    set(value) {
      field = value
      failureTrigger.value = value
    }

  private val failureTrigger = MutableStateFlow<PersistenceError?>(null)

  override fun observeActiveCommitment(): Flow<PersistenceResult<Commitment?>> =
    combine(store, failureTrigger) { list, failure ->
      if (failure != null) PersistenceResult.Failure(failure)
      else PersistenceResult.Success(list.firstOrNull { it is Commitment.Active })
    }

  override fun observeAllCommitments(): Flow<PersistenceResult<List<Commitment>>> =
    combine(store, failureTrigger) { list, failure ->
      if (failure != null) PersistenceResult.Failure(failure)
      else PersistenceResult.Success(list.sortedByDescending { it.createdAt.epochMillis })
    }

  override suspend fun getCommitment(id: CommitmentId): PersistenceResult<Commitment?> =
    observeFailure?.let { PersistenceResult.Failure(it) }
      ?: PersistenceResult.Success(store.value.firstOrNull { it.id == id })

  override suspend fun saveCommitment(commitment: Commitment): PersistenceResult<Unit> {
    saveFailure?.let { return PersistenceResult.Failure(it) }
    val updated = store.value.filterNot { it.id == commitment.id } + commitment
    store.value = updated
    return PersistenceResult.Success(Unit)
  }

  override suspend fun deleteCommitment(id: CommitmentId): PersistenceResult<Unit> {
    store.value = store.value.filterNot { it.id == id }
    return PersistenceResult.Success(Unit)
  }
}

class FakeDailyUsageRepository : DailyUsageRepository {
  private val usages = MutableStateFlow<Map<Pair<CommitmentId, DomainDate>, DailyUsage>>(emptyMap())
  private var activeSession: PersistedActiveUsageSession? = null
  private var integrityCheckpoint: UsageIntegrityCheckpoint? = null
  var readFailure: PersistenceError? = null
  var writeFailure: PersistenceError? = null

  fun setUsage(usage: DailyUsage) {
    usages.value = usages.value + ((usage.commitmentId to usage.date) to usage)
  }

  fun setPersistedActiveSession(session: PersistedActiveUsageSession?) {
    activeSession = session
  }

  fun setIntegrityCheckpointDirect(checkpoint: UsageIntegrityCheckpoint?) {
    integrityCheckpoint = checkpoint
  }

  override fun observeDailyUsage(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): Flow<PersistenceResult<DailyUsage>> =
    usages.map { map ->
      readFailure?.let { return@map PersistenceResult.Failure(it) }
      PersistenceResult.Success(
        map[commitmentId to date] ?: DailyUsage.empty(commitmentId, date)
      )
    }

  override suspend fun getDailyUsage(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): PersistenceResult<DailyUsage> {
    readFailure?.let { return PersistenceResult.Failure(it) }
    return PersistenceResult.Success(
      usages.value[commitmentId to date] ?: DailyUsage.empty(commitmentId, date)
    )
  }

  override suspend fun recordUsageEvent(
    commitment: Commitment,
    event: UsageEvent,
  ): PersistenceResult<DailyUsage> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    val current = usages.value[commitment.id to event.localDate]
    return when (val result = SharedAllowanceAccountant.recordUsage(commitment, current, event)) {
      is com.example.domain.allowance.AllowanceRecordResult.Success -> {
        setUsage(result.dailyUsage)
        PersistenceResult.Success(result.dailyUsage)
      }
      is com.example.domain.allowance.AllowanceRecordResult.Failure ->
        PersistenceResult.Failure(PersistenceError.StorageFailure("Allowance record error"))
    }
  }

  override suspend fun markDayIncomplete(
    commitmentId: CommitmentId,
    date: DomainDate,
    reason: String,
  ): PersistenceResult<DailyUsage> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    val current = usages.value[commitmentId to date] ?: DailyUsage.empty(commitmentId, date)
    val updated =
      current.copy(
        coverageStatus = UsageCoverageStatus.INCOMPLETE,
        incompleteReason = reason,
      )
    setUsage(updated)
    return PersistenceResult.Success(updated)
  }

  override suspend fun markReconciliationAttempt(
    commitmentId: CommitmentId,
    date: DomainDate,
    reconciledAtEpochMillis: Long,
    coverageStatus: UsageCoverageStatus?,
    incompleteReason: String?,
  ): PersistenceResult<DailyUsage> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    val current = usages.value[commitmentId to date] ?: DailyUsage.empty(commitmentId, date)
    val nextCoverage =
      when {
        current.coverageStatus == UsageCoverageStatus.INCOMPLETE -> UsageCoverageStatus.INCOMPLETE
        coverageStatus != null -> coverageStatus
        else -> current.coverageStatus
      }
    val nextReason =
      when {
        current.coverageStatus == UsageCoverageStatus.INCOMPLETE && current.incompleteReason != null ->
          current.incompleteReason
        else -> incompleteReason ?: current.incompleteReason
      }
    val updated =
      current.copy(
        coverageStatus = nextCoverage,
        incompleteReason = nextReason,
        lastReconciledEpochMillis = reconciledAtEpochMillis,
      )
    setUsage(updated)
    return PersistenceResult.Success(updated)
  }

  override suspend fun saveActiveUsageSession(
    session: PersistedActiveUsageSession,
  ): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    activeSession = session
    return PersistenceResult.Success(Unit)
  }

  override suspend fun getActiveUsageSession(): PersistenceResult<PersistedActiveUsageSession?> {
    readFailure?.let { return PersistenceResult.Failure(it) }
    return PersistenceResult.Success(activeSession)
  }

  override suspend fun clearActiveUsageSession(): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    activeSession = null
    return PersistenceResult.Success(Unit)
  }

  override suspend fun getIntegrityCheckpoint(): PersistenceResult<UsageIntegrityCheckpoint?> {
    readFailure?.let { return PersistenceResult.Failure(it) }
    return PersistenceResult.Success(integrityCheckpoint)
  }

  override suspend fun saveIntegrityCheckpoint(
    checkpoint: UsageIntegrityCheckpoint,
  ): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    integrityCheckpoint = checkpoint
    return PersistenceResult.Success(Unit)
  }
}

class FakeSecurityEventRepository : SecurityEventRepository {
  private val events = MutableStateFlow<List<SecurityEvent>>(emptyList())

  val recordedEvents: List<SecurityEvent>
    get() = events.value

  override fun observeSecurityEvents(
    commitmentId: CommitmentId?,
  ): Flow<PersistenceResult<List<SecurityEvent>>> =
    events.map { list ->
      val filtered = if (commitmentId == null) list else list.filter { it.commitmentId == commitmentId }
      PersistenceResult.Success(filtered)
    }

  override suspend fun recordEvent(event: SecurityEvent): PersistenceResult<Unit> {
    events.value = listOf(event) + events.value
    return PersistenceResult.Success(Unit)
  }
}

class FakeAccountabilityMetadataRepository(
  initial: AccountabilityMetadata = AccountabilityMetadata(),
) : AccountabilityMetadataRepository {
  private val state = MutableStateFlow(initial)

  fun setMetadata(metadata: AccountabilityMetadata) {
    state.value = metadata
  }

  override fun observeMetadata(): Flow<PersistenceResult<AccountabilityMetadata>> =
    state.map { PersistenceResult.Success(it) }

  override suspend fun updateMetadata(metadata: AccountabilityMetadata): PersistenceResult<Unit> {
    state.value = metadata
    return PersistenceResult.Success(Unit)
  }
}

class FakeUserPreferencesRepository(initial: AppPreferences = AppPreferences()) :
  UserPreferencesRepository {
  private val _preferences = MutableStateFlow(initial)
  override val preferences: Flow<AppPreferences> = _preferences

  var writeFailure: PersistenceError? = null

  override suspend fun setThemePreference(
    themePreference: ThemePreference,
  ): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    _preferences.value = _preferences.value.copy(themePreference = themePreference)
    return PersistenceResult.Success(Unit)
  }

  override suspend fun setOnboardingCompleted(completed: Boolean): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    _preferences.value = _preferences.value.copy(onboardingCompleted = completed)
    return PersistenceResult.Success(Unit)
  }

  override suspend fun setWifiOnlyFilterUpdates(wifiOnly: Boolean): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    _preferences.value = _preferences.value.copy(wifiOnlyFilterUpdates = wifiOnly)
    return PersistenceResult.Success(Unit)
  }

  override suspend fun setWebsiteFilteringConsented(consented: Boolean): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    _preferences.value = _preferences.value.copy(websiteFilteringConsented = consented)
    return PersistenceResult.Success(Unit)
  }

  override suspend fun setVpnStoppedByUser(stoppedByUser: Boolean): PersistenceResult<Unit> {
    writeFailure?.let { return PersistenceResult.Failure(it) }
    _preferences.value = _preferences.value.copy(vpnStoppedByUser = stoppedByUser)
    return PersistenceResult.Success(Unit)
  }
}

class FakeDomainFilterRepository(
  initialDatasets: List<FilterDatasetMetadata> = DatasetSeedDefaults.defaultMetadata(),
) : DomainFilterRepository {
  private val datasets = MutableStateFlow(initialDatasets)
  private val categoryDomains = mutableMapOf<DomainCategory, Set<String>>()

  fun setCategoryDomains(category: DomainCategory, domains: Set<String>) {
    categoryDomains[category] = domains
  }

  override fun observeDatasets(): Flow<PersistenceResult<List<FilterDatasetMetadata>>> =
    datasets.map { PersistenceResult.Success(it) }

  override suspend fun ensureDefaultDatasetsInitialized(): PersistenceResult<Unit> {
    if (datasets.value.isEmpty()) {
      datasets.value = DatasetSeedDefaults.defaultMetadata()
    }
    return PersistenceResult.Success(Unit)
  }

  override suspend fun getActiveDomainsForCategories(
    categories: Set<DomainCategory>,
  ): PersistenceResult<Map<DomainCategory, Set<String>>> =
    PersistenceResult.Success(
      categories.associateWith { categoryDomains[it].orEmpty() }.filterValues { it.isNotEmpty() }
    )

  override suspend fun stageVerifyAndActivateDataset(
    datasetId: String,
    category: DomainCategory,
    sourceName: String,
    license: String,
    versionCode: Long,
    expectedSha256: String,
    signatureAlgorithm: String?,
    signatureValue: String?,
    rawPayloadBytes: ByteArray,
    limitationsNote: String,
    requireSignature: Boolean,
  ): PersistenceResult<DatasetImportResult> {
    val lines =
      rawPayloadBytes
        .decodeToString()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()
    val meta =
      FilterDatasetMetadata(
        datasetId = datasetId,
        category = category,
        sourceName = sourceName,
        license = license,
        versionCode = versionCode,
        contentSha256 = expectedSha256,
        signatureAlgorithm = signatureAlgorithm,
        signatureValue = signatureValue,
        recordCount = lines.size,
        trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
        limitationsNote = limitationsNote,
      )
    categoryDomains[category] = lines
    datasets.value = datasets.value.filterNot { it.datasetId == datasetId } + meta
    return PersistenceResult.Success(DatasetImportResult.Success(meta))
  }

  override suspend fun rollbackDataset(
    datasetId: String,
  ): PersistenceResult<FilterDatasetMetadata> =
    PersistenceResult.Failure(PersistenceError.NotFound("No rollback snapshot in fake"))
}

class FakeVpnFilterMonitor(
  initialSnapshot: VpnFilterSnapshot = VpnFilterSnapshot(),
) : VpnFilterMonitor {
  private val _vpnState = MutableStateFlow(initialSnapshot)
  override val vpnState: StateFlow<VpnFilterSnapshot> = _vpnState.asStateFlow()

  var nextPrepareIntent: Intent? = null
  var startRequests: Int = 0
  var stopRequests: Int = 0
  var commitmentEndedCalls: Int = 0
  var refreshCalls: Int = 0

  fun setSnapshot(snapshot: VpnFilterSnapshot) {
    _vpnState.value = snapshot
  }

  override fun prepareVpnIntent(): Intent? = nextPrepareIntent

  override fun refreshSystemStatus() {
    refreshCalls += 1
  }

  override suspend fun requestStartFiltering(): VpnStartRequestOutcome {
    startRequests += 1
    if (nextPrepareIntent != null) {
      return VpnStartRequestOutcome.PERMISSION_REQUIRED
    }
    _vpnState.update {
      it.copy(
        status = VpnServiceLifecycleStatus.ACTIVE,
        isPermissionPrepared = true,
        isTunnelEstablished = true,
        stoppedByUser = false,
      )
    }
    return VpnStartRequestOutcome.STARTED
  }

  override suspend fun requestStopFiltering(markStoppedByUser: Boolean) {
    stopRequests += 1
    _vpnState.update {
      it.copy(
        status = VpnServiceLifecycleStatus.STOPPED_BY_USER,
        isTunnelEstablished = false,
        stoppedByUser = markStoppedByUser,
      )
    }
  }

  override suspend fun onCommitmentEnded() {
    commitmentEndedCalls += 1
    _vpnState.update {
      it.copy(
        status = VpnServiceLifecycleStatus.NOT_CONFIGURED,
        hasActiveWebsiteCommitment = false,
        isTunnelEstablished = false,
        stoppedByUser = false,
      )
    }
  }

  override fun markPermissionDenied() {
    _vpnState.update {
      it.copy(
        status = VpnServiceLifecycleStatus.PERMISSION_DENIED,
        permissionDenied = true,
        isPermissionPrepared = false,
        isTunnelEstablished = false,
      )
    }
  }

  override fun onServiceStarting() {
    _vpnState.update { it.copy(status = VpnServiceLifecycleStatus.STARTING) }
  }

  override fun onTunnelEstablished(upstreamDnsCount: Int, isPrivateDnsActive: Boolean) {
    _vpnState.update {
      it.copy(
        status = VpnServiceLifecycleStatus.ACTIVE,
        isTunnelEstablished = true,
        upstreamDnsCount = upstreamDnsCount,
        isPrivateDnsActive = isPrivateDnsActive,
      )
    }
  }

  override fun onTunnelRevoked(anotherVpnActive: Boolean) {
    _vpnState.update {
      it.copy(
        status =
          if (anotherVpnActive) VpnServiceLifecycleStatus.ANOTHER_VPN_ACTIVE
          else VpnServiceLifecycleStatus.REVOKED,
        isTunnelEstablished = false,
        wasRevoked = !anotherVpnActive,
        anotherVpnActive = anotherVpnActive,
      )
    }
  }

  override fun onTunnelStopped(stoppedByUser: Boolean) {
    _vpnState.update {
      it.copy(
        status =
          if (stoppedByUser) VpnServiceLifecycleStatus.STOPPED_BY_USER
          else VpnServiceLifecycleStatus.READY_INACTIVE,
        isTunnelEstablished = false,
        stoppedByUser = stoppedByUser,
      )
    }
  }

  override fun onTunnelError(message: String) {
    _vpnState.update {
      it.copy(
        status = VpnServiceLifecycleStatus.DEGRADED_ERROR,
        isTunnelEstablished = false,
        lastError = message,
      )
    }
  }
}

class FakeInstalledAppRepository(
  var result: InstalledAppsResult = InstalledAppsResult.Success(emptyList()),
) : InstalledAppRepository {
  override suspend fun getLaunchableApps(): InstalledAppsResult = result
}

class FakeEnforcementHealthMonitor(
  initialPermission: AccessibilityPermissionStatus = AccessibilityPermissionStatus.DISABLED,
) : EnforcementHealthMonitor {
  var systemPermissionStatus: AccessibilityPermissionStatus = initialPermission

  private val _healthState =
    MutableStateFlow(
      AndroidEnforcementHealthMonitor.computeSnapshot(
        permissionStatus = initialPermission,
        isServiceConnected = false,
        ruleStateAvailable = true,
        updatedAt = 1_000L,
      )
    )
  override val healthState: StateFlow<EnforcementHealthSnapshot> = _healthState.asStateFlow()

  var refreshCount: Int = 0
    private set

  override fun refreshSystemStatus() {
    refreshCount += 1
    _healthState.update { current ->
      val connected =
        if (systemPermissionStatus != AccessibilityPermissionStatus.ENABLED) false
        else current.isServiceConnected
      AndroidEnforcementHealthMonitor.computeSnapshot(
        permissionStatus = systemPermissionStatus,
        isServiceConnected = connected,
        ruleStateAvailable = current.ruleStateAvailable,
        updatedAt = (current.lastUpdatedEpochMillis ?: 0L) + 1L,
      )
    }
  }

  override fun setServiceConnected(connected: Boolean) {
    if (connected) {
      systemPermissionStatus = AccessibilityPermissionStatus.ENABLED
    }
    _healthState.update { current ->
      AndroidEnforcementHealthMonitor.computeSnapshot(
        permissionStatus = systemPermissionStatus,
        isServiceConnected = connected,
        ruleStateAvailable = current.ruleStateAvailable,
        updatedAt = (current.lastUpdatedEpochMillis ?: 0L) + 1L,
      )
    }
  }

  override fun setRuleStateAvailable(available: Boolean) {
    _healthState.update { current ->
      AndroidEnforcementHealthMonitor.computeSnapshot(
        permissionStatus = current.permissionStatus,
        isServiceConnected = current.isServiceConnected,
        ruleStateAvailable = available,
        updatedAt = (current.lastUpdatedEpochMillis ?: 0L) + 1L,
      )
    }
  }

  override fun createAccessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

class FakeUsageAccessMonitor(
  initialStatus: UsageAccessPermissionStatus = UsageAccessPermissionStatus.DISABLED,
) : UsageAccessMonitor {
  var systemStatus: UsageAccessPermissionStatus = initialStatus

  private val _usageAccessState =
    MutableStateFlow(
      UsageAccessSnapshot(permissionStatus = initialStatus, lastCheckedEpochMillis = 1_000L)
    )
  override val usageAccessState: StateFlow<UsageAccessSnapshot> = _usageAccessState.asStateFlow()

  var refreshCount: Int = 0
    private set

  override fun refreshSystemStatus() {
    refreshCount += 1
    _usageAccessState.update { current ->
      UsageAccessSnapshot(
        permissionStatus = systemStatus,
        lastCheckedEpochMillis = (current.lastCheckedEpochMillis ?: 0L) + 1L,
      )
    }
  }

  override fun createUsageAccessSettingsIntent(): Intent =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

class FakeBootSessionProvider(var token: String = "boot:1") : BootSessionProvider {
  override fun currentBootSessionToken(): String = token
}

class FakeDeviceInteractiveStateProvider(var interactiveAndUnlocked: Boolean = true) :
  DeviceInteractiveStateProvider {
  override fun isInteractiveAndUnlocked(): Boolean = interactiveAndUnlocked
}

class FakeUsageStatsHistorySource(var events: List<OsUsageEvent> = emptyList()) :
  UsageStatsHistorySource {
  var lastQueryStartMs: Long? = null
  var lastQueryEndMs: Long? = null
  var throwOnQuery: Throwable? = null

  override fun queryEvents(startEpochMillis: Long, endEpochMillis: Long): List<OsUsageEvent> {
    lastQueryStartMs = startEpochMillis
    lastQueryEndMs = endEpochMillis
    throwOnQuery?.let { throw it }
    return events
  }
}

class FakeBlockingUiLauncher(var shouldSucceed: Boolean = true) : BlockingUiLauncher {
  val requests = mutableListOf<BlockingLaunchRequest>()

  override fun launchBlockingScreen(request: BlockingLaunchRequest): Boolean {
    if (!shouldSucceed) return false
    requests.add(request)
    return true
  }
}

fun createTestUsageTracker(
  dailyUsageRepository: DailyUsageRepository = FakeDailyUsageRepository(),
  securityEventRepository: SecurityEventRepository = FakeSecurityEventRepository(),
  timeProvider: TimeProvider = FakeTimeProvider(),
  bootSessionProvider: BootSessionProvider = FakeBootSessionProvider(),
  deviceInteractiveStateProvider: DeviceInteractiveStateProvider = FakeDeviceInteractiveStateProvider(),
  idGenerator: IdGenerator = FakeIdGenerator(),
  dispatcherProvider: DispatcherProvider = FakeDispatcherProvider(),
  ownPackageName: String = "com.ibnmisbah.mizan",
): ForegroundUsageTracker =
  ForegroundUsageTracker(
    dailyUsageRepository = dailyUsageRepository,
    securityEventRepository = securityEventRepository,
    timeProvider = timeProvider,
    bootSessionProvider = bootSessionProvider,
    deviceInteractiveStateProvider = deviceInteractiveStateProvider,
    idGenerator = idGenerator,
    dispatcherProvider = dispatcherProvider,
    ownPackageName = ownPackageName,
  )

fun createTestUsageReconciler(
  usageAccessMonitor: UsageAccessMonitor = FakeUsageAccessMonitor(),
  historySource: UsageStatsHistorySource = FakeUsageStatsHistorySource(),
  dailyUsageRepository: DailyUsageRepository = FakeDailyUsageRepository(),
  timeProvider: TimeProvider = FakeTimeProvider(),
  dispatcherProvider: DispatcherProvider = FakeDispatcherProvider(),
  ownPackageName: String = "com.ibnmisbah.mizan",
): UsageStatsReconciler =
  UsageStatsReconciler(
    usageAccessMonitor = usageAccessMonitor,
    historySource = historySource,
    dailyUsageRepository = dailyUsageRepository,
    timeProvider = timeProvider,
    dispatcherProvider = dispatcherProvider,
    ownPackageName = ownPackageName,
  )

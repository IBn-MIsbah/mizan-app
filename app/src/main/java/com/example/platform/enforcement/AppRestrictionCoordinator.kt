package com.example.platform.enforcement

import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.domain.evaluator.RestrictionDecision
import com.example.domain.evaluator.RestrictionEvaluator
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.time.TimeProvider
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.usage.ForegroundUsageTracker
import com.example.platform.usage.SessionEndReason
import com.example.platform.usage.UsageStatsReconciler
import com.example.platform.usage.UsageTrackingOutcome
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPackageName

private sealed interface CachedRuleSnapshot {
  data object Uninitialized : CachedRuleSnapshot

  data object Unavailable : CachedRuleSnapshot

  data class Loaded(val commitment: Commitment?) : CachedRuleSnapshot
}

@Singleton
class AppRestrictionCoordinator
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val dailyUsageRepository: DailyUsageRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
  private val healthMonitor: EnforcementHealthMonitor,
  private val blockingUiLauncher: BlockingUiLauncher,
  private val usageTracker: ForegroundUsageTracker,
  private val usageReconciler: UsageStatsReconciler,
  private val dispatcherProvider: DispatcherProvider,
  @param:AppPackageName private val ownPackageName: String,
) {

  private val mutex = Mutex()
  private val ruleCache = MutableStateFlow<CachedRuleSnapshot>(CachedRuleSnapshot.Uninitialized)
  private var observerScope: CoroutineScope? = null
  private var observerJob: Job? = null

  private var lastBlockedPackage: String? = null
  private var lastBlockLaunchedMonotonicMs: Long = Long.MIN_VALUE

  init {
    usageTracker.setOnAllowanceExhaustedListener { exhaustedPackage ->
      onForegroundPackageObserved(exhaustedPackage)
    }
  }

  fun startObserving() {
    if (observerJob?.isActive == true) return
    val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    observerScope = scope
    observerJob =
      scope.launch {
        try {
          commitmentRepository.observeActiveCommitment().collect { result ->
            when (result) {
              is PersistenceResult.Success -> {
                ruleCache.value = CachedRuleSnapshot.Loaded(result.data)
                healthMonitor.setRuleStateAvailable(true)
                if (result.data !is Commitment.Active) {
                  clearDebounceStateLockedSafely()
                  usageTracker.closeActiveSession(SessionEndReason.COMMITMENT_ENDED)
                }
              }
              is PersistenceResult.Failure -> {
                ruleCache.value = CachedRuleSnapshot.Unavailable
                healthMonitor.setRuleStateAvailable(false)
                usageTracker.closeActiveSession(SessionEndReason.COMMITMENT_ENDED)
              }
            }
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: RuntimeException) {
          ruleCache.value = CachedRuleSnapshot.Unavailable
          healthMonitor.setRuleStateAvailable(false)
        }
      }
  }

  fun onServiceConnected() {
    healthMonitor.setServiceConnected(true)
    startObserving()
    resetDebounce()
    observerScope?.launch {
      usageTracker.recoverUnfinishedSessionIfNeeded()
      val active = resolveCurrentCommitment()
      if (active is Commitment.Active) {
        usageReconciler.reconcileActiveCommitment(active)
      }
    }
  }

  fun onServiceInterrupted() {
    healthMonitor.setServiceConnected(false)
    val currentCommitment = (ruleCache.value as? CachedRuleSnapshot.Loaded)?.commitment
    val scope = observerScope ?: CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    scope.launch {
      usageTracker.closeActiveSession(SessionEndReason.SERVICE_INTERRUPTED)
      if (currentCommitment is Commitment.Active) {
        runCatching {
          securityEventRepository.recordEvent(
            SecurityEvent(
              id = SecurityEventId(idGenerator.nextId()),
              commitmentId = currentCommitment.id,
              eventType = SecurityEventType.SERVICE_INTERRUPTED,
              occurredAt = timeProvider.currentWallClock(),
              summary = "Accessibility enforcement service interrupted by system",
            )
          )
        }
      }
    }
  }

  fun onServiceDisconnectedOrDestroyed() {
    healthMonitor.setServiceConnected(false)
    val scope = observerScope ?: CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    scope.launch {
      usageTracker.closeActiveSession(SessionEndReason.SERVICE_DISCONNECTED)
    }
    observerJob?.cancel()
    observerJob = null
    observerScope = null
    ruleCache.value = CachedRuleSnapshot.Uninitialized
    resetDebounce()
  }

  suspend fun onDeviceInteractiveEnded(reason: SessionEndReason = SessionEndReason.SCREEN_OFF) {
    usageTracker.closeActiveSession(reason)
    resetDebounce()
  }

  fun resetDebounce() {
    lastBlockedPackage = null
    lastBlockLaunchedMonotonicMs = Long.MIN_VALUE
  }

  suspend fun onForegroundPackageObserved(rawPackageName: String?): RestrictionDecision? {
    val packageName = PackageNameValidator.normalize(rawPackageName) ?: return null
    if (packageName == ownPackageName) {
      // Never treat MIZAN or its BlockingActivity as a restricted target; close any tracked
      // restricted session cleanly when user enters MIZAN.
      usageTracker.closeActiveSession(SessionEndReason.ALLOWLISTED_OR_MIZAN)
      return null
    }

    return mutex.withLock {
      val commitment = resolveCurrentCommitment()
      if (commitment == null && ruleCache.value is CachedRuleSnapshot.Unavailable) {
        // Rule state is unavailable: fail safely without blocking on stale or guessed state.
        usageTracker.closeActiveSession(SessionEndReason.COMMITMENT_ENDED)
        return@withLock null
      }

      val nowWallClock = timeProvider.currentWallClock()
      val nowMonotonic = timeProvider.currentMonotonic()

      if (commitment !is Commitment.Active) {
        lastBlockedPackage = null
        val decision =
          RestrictionEvaluator.evaluate(
            commitment = commitment,
            appId = AppId(packageName),
            wallClock = nowWallClock,
          )
        usageTracker.onForegroundTransition(
          commitment = commitment,
          packageName = packageName,
          decision = decision,
          nowWall = nowWallClock,
          nowMonotonic = nowMonotonic,
        )
        return@withLock decision
      }

      val dailyUsage: DailyUsage? =
        if (commitment.rules.mode == RestrictionMode.HYBRID &&
          commitment.rules.isRestricted(AppId(packageName)) &&
          !commitment.rules.isAllowlisted(AppId(packageName))
        ) {
          when (
            val usageResult =
              withContext(dispatcherProvider.io) {
                runCatching { dailyUsageRepository.getDailyUsage(commitment.id, nowWallClock.date) }
                  .getOrNull()
              }
          ) {
            is PersistenceResult.Success -> usageResult.data
            is PersistenceResult.Failure,
            null -> {
              healthMonitor.setRuleStateAvailable(false)
              usageTracker.closeActiveSession(SessionEndReason.COMMITMENT_ENDED)
              return@withLock null
            }
          }
        } else {
          null
        }

      healthMonitor.setRuleStateAvailable(true)

      val decision =
        RestrictionEvaluator.evaluate(
          commitment = commitment,
          appId = AppId(packageName),
          wallClock = nowWallClock,
          dailyUsage = dailyUsage,
        )

      val trackingOutcome =
        usageTracker.onForegroundTransition(
          commitment = commitment,
          packageName = packageName,
          decision = decision,
          nowWall = nowWallClock,
          nowMonotonic = nowMonotonic,
        )
      if (trackingOutcome is UsageTrackingOutcome.StorageError) {
        healthMonitor.setRuleStateAvailable(false)
      }

      if (decision.isBlocked) {
        val isDuplicateBurst =
          lastBlockedPackage == packageName &&
            lastBlockLaunchedMonotonicMs != Long.MIN_VALUE &&
            (nowMonotonic.elapsedMillis - lastBlockLaunchedMonotonicMs) in 0 until BLOCK_DEBOUNCE_MILLIS

        if (!isDuplicateBurst) {
          val resolvedLabel =
            commitment.rules.restrictedApps
              .firstOrNull { it.appId.packageName == packageName }
              ?.displayLabel
          val launched =
            blockingUiLauncher.launchBlockingScreen(
              BlockingLaunchRequest(
                packageName = packageName,
                appLabel = resolvedLabel,
                reason = decision.reason,
                commitmentId = commitment.id.value,
              )
            )
          if (launched) {
            lastBlockedPackage = packageName
            lastBlockLaunchedMonotonicMs = nowMonotonic.elapsedMillis
          }
        }
      } else {
        // User navigated to an allowed app, system home launcher, or commitment rules allow access.
        lastBlockedPackage = null
      }

      decision
    }
  }

  private suspend fun resolveCurrentCommitment(): Commitment? {
    when (val cached = ruleCache.value) {
      is CachedRuleSnapshot.Loaded -> return cached.commitment
      CachedRuleSnapshot.Uninitialized -> Unit
      CachedRuleSnapshot.Unavailable -> {
        // Attempt recovery read so restored persistence brings enforcement back cleanly.
      }
    }

    val fetched =
      withContext(dispatcherProvider.io) {
        runCatching { commitmentRepository.observeActiveCommitment().first() }.getOrNull()
      }
    return when (fetched) {
      is PersistenceResult.Success -> {
        ruleCache.value = CachedRuleSnapshot.Loaded(fetched.data)
        healthMonitor.setRuleStateAvailable(true)
        fetched.data
      }
      is PersistenceResult.Failure,
      null -> {
        ruleCache.value = CachedRuleSnapshot.Unavailable
        healthMonitor.setRuleStateAvailable(false)
        null
      }
    }
  }

  private fun clearDebounceStateLockedSafely() {
    lastBlockedPackage = null
    lastBlockLaunchedMonotonicMs = Long.MIN_VALUE
  }

  companion object {
    const val BLOCK_DEBOUNCE_MILLIS: Long = 750L
  }
}

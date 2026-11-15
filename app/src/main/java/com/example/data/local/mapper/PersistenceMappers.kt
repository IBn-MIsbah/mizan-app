package com.example.data.local.mapper

import com.example.data.local.entity.AccountabilityMetadataEntity
import com.example.data.local.entity.ActiveUsageSessionEntity
import com.example.data.local.entity.AllowedAppEntity
import com.example.data.local.entity.CommitmentBlockedDomainEntity
import com.example.data.local.entity.CommitmentEntity
import com.example.data.local.entity.CommitmentRulesEntity
import com.example.data.local.entity.CommitmentWithRelations
import com.example.data.local.entity.DailyUsageWithEvents
import com.example.data.local.entity.FilterDatasetMetadataEntity
import com.example.data.local.entity.RemovalRequestEntity
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.ScheduleWindowEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.data.local.entity.UsageEventEntity
import com.example.data.local.entity.UsageIntegrityStateEntity
import com.example.data.local.entity.WallClockPointEmbedded
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.AllowedApp
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.BlockedDomain
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.model.DailyUsage
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.RecordedUsageInterval
import com.example.domain.model.RemovalRequest
import com.example.domain.model.RemovalRequestId
import com.example.domain.model.RemovalRequestStatus
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.model.UsageIntegrityCheckpoint
import com.example.domain.model.UsageMeasurementSource
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class CorruptLocalDataException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal object PersistenceMappers {

  private const val STATE_DRAFT = "DRAFT"
  private const val STATE_ACTIVE = "ACTIVE"
  private const val STATE_COMPLETED = "COMPLETED"
  private const val STATE_EMERGENCY_TERMINATED = "EMERGENCY_TERMINATED"

  fun WallClockPoint.toEmbedded(): WallClockPointEmbedded =
    WallClockPointEmbedded(
      dateIso = date.toIsoString(),
      timeIso = time.toIsoString(),
      epochMillis = epochMillis,
      utcOffsetMinutes = utcOffsetMinutes,
      zoneId = zoneId,
    )

  fun WallClockPointEmbedded.toDomain(): WallClockPoint =
    try {
      WallClockPoint(
        date = parseDomainDate(dateIso),
        time = parseDomainTime(timeIso),
        epochMillis = epochMillis,
        utcOffsetMinutes = utcOffsetMinutes,
        zoneId = zoneId,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid persisted wall-clock timestamp", e)
    }

  fun DomainDate.toIsoString(): String =
    String.format(Locale.US, "%04d-%02d-%02d", year, month, dayOfMonth)

  fun parseDomainDate(raw: String): DomainDate {
    val parts = raw.split("-")
    if (parts.size != 3) {
      throw CorruptLocalDataException("Invalid stored date format")
    }
    return try {
      DomainDate(
        year = parts[0].toInt(),
        month = parts[1].toInt(),
        dayOfMonth = parts[2].toInt(),
      )
    } catch (e: RuntimeException) {
      throw CorruptLocalDataException("Invalid stored calendar date", e)
    }
  }

  fun DomainTime.toIsoString(): String =
    String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second)

  private fun parseDomainTime(raw: String): DomainTime {
    val parts = raw.split(":")
    if (parts.size != 3) {
      throw CorruptLocalDataException("Invalid stored time format")
    }
    return try {
      DomainTime(
        hour = parts[0].toInt(),
        minute = parts[1].toInt(),
        second = parts[2].toInt(),
      )
    } catch (e: RuntimeException) {
      throw CorruptLocalDataException("Invalid stored time of day", e)
    }
  }

  fun Commitment.toEntities(): CommitmentEntitiesBundle {
    val commitmentId = id.value
    val entity =
      when (this) {
        is Commitment.Draft ->
          CommitmentEntity(
            id = commitmentId,
            lifecycleState = STATE_DRAFT,
            createdAt = createdAt.toEmbedded(),
            activatedAt = null,
            endsAt = null,
            completedAt = null,
            nextStepChoice = null,
            terminatedAt = null,
            pinVerified = null,
          )

        is Commitment.Active ->
          CommitmentEntity(
            id = commitmentId,
            lifecycleState = STATE_ACTIVE,
            createdAt = createdAt.toEmbedded(),
            activatedAt = activatedAt.toEmbedded(),
            endsAt = endsAt.toEmbedded(),
            completedAt = null,
            nextStepChoice = null,
            terminatedAt = null,
            pinVerified = null,
          )

        is Commitment.Completed ->
          CommitmentEntity(
            id = commitmentId,
            lifecycleState = STATE_COMPLETED,
            createdAt = createdAt.toEmbedded(),
            activatedAt = activatedAt.toEmbedded(),
            endsAt = endsAt.toEmbedded(),
            completedAt = completedAt.toEmbedded(),
            nextStepChoice = nextStepChoice?.name,
            terminatedAt = null,
            pinVerified = null,
          )

        is Commitment.EmergencyTerminated ->
          CommitmentEntity(
            id = commitmentId,
            lifecycleState = STATE_EMERGENCY_TERMINATED,
            createdAt = createdAt.toEmbedded(),
            activatedAt = activatedAt.toEmbedded(),
            endsAt = plannedEndsAt.toEmbedded(),
            completedAt = null,
            nextStepChoice = null,
            terminatedAt = terminatedAt.toEmbedded(),
            pinVerified = pinVerified,
          )
      }

    val rulesEntity =
      CommitmentRulesEntity(
        commitmentId = commitmentId,
        restrictionMode = rules.mode.name,
        dailyAllowanceMillis = rules.dailyAllowance.inWholeMilliseconds,
        requirePinForEmergencyTermination = rules.requirePinForEmergencyTermination,
        websiteFilteringEnabled = rules.websiteFilteringEnabled,
        blockedDomainCategoriesCsv =
          rules.blockedDomainCategories.sortedBy { it.name }.joinToString(",") { it.name },
        blockYouTubeShorts = rules.blockYouTubeShorts,
      )

    val restrictedAppEntities =
      rules.restrictedApps.map { app ->
        RestrictedAppEntity(
          commitmentId = commitmentId,
          packageName = app.appId.packageName,
          displayLabel = app.displayLabel,
        )
      }

    val allowedAppEntities =
      rules.allowedApps.map { app ->
        AllowedAppEntity(
          commitmentId = commitmentId,
          packageName = app.appId.packageName,
          displayLabel = app.displayLabel,
          category = app.category.name,
        )
      }

    val scheduleEntities =
      rules.scheduleWindows.mapIndexed { index, window ->
        ScheduleWindowEntity(
          commitmentId = commitmentId,
          windowOrder = index,
          daysOfWeekCsv =
            window.daysOfWeek
              .sortedBy { it.isoValue }
              .joinToString(separator = ",") { it.name },
          startHour = window.start.hour,
          startMinute = window.start.minute,
          startSecond = window.start.second,
          endHour = window.end.hour,
          endMinute = window.end.minute,
          endSecond = window.end.second,
        )
      }

    val blockedDomainEntities =
      rules.blockedDomains.map { entry ->
        CommitmentBlockedDomainEntity(
          commitmentId = commitmentId,
          normalizedDomain = entry.domain.value,
          displayDomain = entry.displayName,
          category = entry.category.name,
          ruleType = entry.ruleType.name,
        )
      }

    return CommitmentEntitiesBundle(
      commitment = entity,
      rules = rulesEntity,
      restrictedApps = restrictedAppEntities,
      allowedApps = allowedAppEntities,
      scheduleWindows = scheduleEntities,
      blockedDomains = blockedDomainEntities,
    )
  }

  fun CommitmentWithRelations.toDomain(): Commitment {
    val rulesEntity =
      rules ?: throw CorruptLocalDataException("Missing commitment_rules row for commitment")

    val mode =
      parseEnumSafe<RestrictionMode>(rulesEntity.restrictionMode, "RestrictionMode")

    val domainRestrictedApps =
      try {
        restrictedApps
          .map { RestrictedApp(appId = AppId(it.packageName), displayLabel = it.displayLabel) }
          .toSet()
      } catch (e: IllegalArgumentException) {
        throw CorruptLocalDataException("Invalid restricted app entry", e)
      }

    val domainAllowedApps =
      try {
        allowedApps
          .map {
            AllowedApp(
              appId = AppId(it.packageName),
              displayLabel = it.displayLabel,
              category = parseEnumSafe(it.category, "AllowlistCategory"),
            )
          }
          .toSet()
      } catch (e: IllegalArgumentException) {
        throw CorruptLocalDataException("Invalid allowed app entry", e)
      }

    val domainScheduleWindows =
      scheduleWindows
        .sortedBy { it.windowOrder }
        .map { entity ->
          val days =
            entity.daysOfWeekCsv
              .split(",")
              .filter { it.isNotBlank() }
              .map { token -> parseEnumSafe<DomainDayOfWeek>(token, "DomainDayOfWeek") }
              .toSet()
          try {
            ScheduleWindow(
              daysOfWeek = days,
              start = DomainTime(entity.startHour, entity.startMinute, entity.startSecond),
              end = DomainTime(entity.endHour, entity.endMinute, entity.endSecond),
            )
          } catch (e: IllegalArgumentException) {
            throw CorruptLocalDataException("Invalid schedule window values", e)
          }
        }

    val domainBlockedDomains =
      try {
        blockedDomains
          .map { entity ->
            BlockedDomain(
              domain = DomainName(entity.normalizedDomain),
              displayName = entity.displayDomain,
              category = parseEnumSafe(entity.category, "DomainCategory"),
              ruleType = parseEnumSafe(entity.ruleType, "DomainRuleType"),
            )
          }
          .toSet()
      } catch (e: IllegalArgumentException) {
        throw CorruptLocalDataException("Invalid blocked domain entry", e)
      }

    val domainBlockedCategories =
      rulesEntity.blockedDomainCategoriesCsv
        .split(",")
        .filter { it.isNotBlank() }
        .map { token -> parseEnumSafe<DomainCategory>(token, "DomainCategory") }
        .toSet()

    val domainRules =
      try {
        CommitmentRules(
          mode = mode,
          restrictedApps = domainRestrictedApps,
          allowedApps = domainAllowedApps,
          scheduleWindows = domainScheduleWindows,
          dailyAllowance = rulesEntity.dailyAllowanceMillis.milliseconds,
          requirePinForEmergencyTermination = rulesEntity.requirePinForEmergencyTermination,
          websiteFilteringEnabled = rulesEntity.websiteFilteringEnabled,
          blockedDomains = domainBlockedDomains,
          blockedDomainCategories = domainBlockedCategories,
          blockYouTubeShorts = rulesEntity.blockYouTubeShorts,
        )
      } catch (e: IllegalArgumentException) {
        throw CorruptLocalDataException("Invalid commitment rules values", e)
      }

    val domainId =
      try {
        CommitmentId(commitment.id)
      } catch (e: IllegalArgumentException) {
        throw CorruptLocalDataException("Invalid commitment id", e)
      }
    val createdAt = commitment.createdAt.toDomain()

    return try {
      when (commitment.lifecycleState) {
        STATE_DRAFT ->
          Commitment.Draft(
            id = domainId,
            createdAt = createdAt,
            rules = domainRules,
          )

        STATE_ACTIVE ->
          Commitment.Active(
            id = domainId,
            createdAt = createdAt,
            rules = domainRules,
            activatedAt =
              commitment.activatedAt?.toDomain()
                ?: throw CorruptLocalDataException("Active commitment missing activatedAt"),
            endsAt =
              commitment.endsAt?.toDomain()
                ?: throw CorruptLocalDataException("Active commitment missing endsAt"),
          )

        STATE_COMPLETED ->
          Commitment.Completed(
            id = domainId,
            createdAt = createdAt,
            rules = domainRules,
            activatedAt =
              commitment.activatedAt?.toDomain()
                ?: throw CorruptLocalDataException("Completed commitment missing activatedAt"),
            endsAt =
              commitment.endsAt?.toDomain()
                ?: throw CorruptLocalDataException("Completed commitment missing endsAt"),
            completedAt =
              commitment.completedAt?.toDomain()
                ?: throw CorruptLocalDataException("Completed commitment missing completedAt"),
            nextStepChoice =
              commitment.nextStepChoice?.let {
                parseEnumSafe<CompletionNextStep>(it, "CompletionNextStep")
              },
          )

        STATE_EMERGENCY_TERMINATED ->
          Commitment.EmergencyTerminated(
            id = domainId,
            createdAt = createdAt,
            rules = domainRules,
            activatedAt =
              commitment.activatedAt?.toDomain()
                ?: throw CorruptLocalDataException(
                  "EmergencyTerminated commitment missing activatedAt"
                ),
            plannedEndsAt =
              commitment.endsAt?.toDomain()
                ?: throw CorruptLocalDataException(
                  "EmergencyTerminated commitment missing plannedEndsAt"
                ),
            terminatedAt =
              commitment.terminatedAt?.toDomain()
                ?: throw CorruptLocalDataException(
                  "EmergencyTerminated commitment missing terminatedAt"
                ),
            pinVerified =
              commitment.pinVerified
                ?: throw CorruptLocalDataException(
                  "EmergencyTerminated commitment missing pinVerified"
                ),
          )

        else ->
          throw CorruptLocalDataException(
            "Unknown commitment lifecycle_state: ${commitment.lifecycleState}"
          )
      }
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Stored commitment violates domain invariants", e)
    }
  }

  fun DailyUsageWithEvents.toDomain(): DailyUsage =
    try {
      val coverage = parseEnumSafe<UsageCoverageStatus>(dailyUsage.coverageStatus, "UsageCoverageStatus")
      val recordedIntervals =
        events.map { eventEntity ->
          RecordedUsageInterval(
            eventId = UsageEventId(eventEntity.eventId),
            packageName = AppId(eventEntity.packageName),
            startedAtEpochMillis = eventEntity.startedAtEpochMillis,
            endedAtEpochMillis = eventEntity.endedAtEpochMillis,
            duration = eventEntity.foregroundDurationMillis.milliseconds,
            source = parseEnumSafe<UsageMeasurementSource>(eventEntity.source, "UsageMeasurementSource"),
          )
        }
      DailyUsage(
        commitmentId = CommitmentId(dailyUsage.commitmentId),
        date = parseDomainDate(dailyUsage.localDate),
        consumed = dailyUsage.consumedMillis.milliseconds,
        recordedEventIds = events.map { UsageEventId(it.eventId) }.toSet(),
        coverageStatus = coverage,
        incompleteReason = dailyUsage.incompleteReason,
        lastReconciledEpochMillis = dailyUsage.lastReconciledEpochMillis,
        intervals = recordedIntervals,
        blockedAttempts = dailyUsage.blockedAttempts,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid stored DailyUsage values", e)
    }

  fun UsageEvent.toEntity(dailyUsageId: Long, commitmentId: CommitmentId): UsageEventEntity =
    UsageEventEntity(
      eventId = eventId.value,
      dailyUsageId = dailyUsageId,
      commitmentId = commitmentId.value,
      packageName = appId.packageName,
      foregroundDurationMillis = foregroundDuration.inWholeMilliseconds,
      isForeground = isForeground,
      localDate = localDate.toIsoString(),
      source = source.name,
      startedAtEpochMillis = startedAtEpochMillis,
      endedAtEpochMillis = endedAtEpochMillis,
      zoneId = zoneId,
      bootSessionToken = bootSessionToken,
    )

  fun PersistedActiveUsageSession.toEntity(): ActiveUsageSessionEntity =
    ActiveUsageSessionEntity(
      id = ActiveUsageSessionEntity.SINGLETON_ID,
      commitmentId = commitmentId.value,
      packageName = appId.packageName,
      startedAt = startedAtWallClock.toEmbedded(),
      startedMonotonicMillis = startedAtMonotonic.elapsedMillis,
      bootSessionToken = bootSessionToken,
    )

  fun ActiveUsageSessionEntity.toDomain(): PersistedActiveUsageSession =
    try {
      PersistedActiveUsageSession(
        commitmentId = CommitmentId(commitmentId),
        appId = AppId(packageName),
        startedAtWallClock = startedAt.toDomain(),
        startedAtMonotonic = MonotonicTimestamp(startedMonotonicMillis),
        bootSessionToken = bootSessionToken,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid stored ActiveUsageSession values", e)
    }

  fun UsageIntegrityCheckpoint.toEntity(): UsageIntegrityStateEntity =
    UsageIntegrityStateEntity(
      id = UsageIntegrityStateEntity.SINGLETON_ID,
      lastWallEpochMillis = lastWallClock.epochMillis,
      lastMonotonicMillis = lastMonotonic.elapsedMillis,
      lastBootSessionToken = bootSessionToken,
      lastWallDateIso = lastWallClock.date.toIsoString(),
      lastWallTimeIso = lastWallClock.time.toIsoString(),
      lastZoneId = lastWallClock.zoneId,
      lastUtcOffsetMinutes = lastWallClock.utcOffsetMinutes,
    )

  fun UsageIntegrityStateEntity.toDomain(): UsageIntegrityCheckpoint =
    try {
      UsageIntegrityCheckpoint(
        lastWallClock =
          WallClockPoint(
            date = parseDomainDate(lastWallDateIso),
            time = parseDomainTime(lastWallTimeIso),
            epochMillis = lastWallEpochMillis,
            utcOffsetMinutes = lastUtcOffsetMinutes,
            zoneId = lastZoneId,
          ),
        lastMonotonic = MonotonicTimestamp(lastMonotonicMillis),
        bootSessionToken = lastBootSessionToken,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid stored UsageIntegrityState values", e)
    }

  fun SecurityEvent.toEntity(): SecurityEventEntity =
    SecurityEventEntity(
      id = id.value,
      commitmentId = commitmentId?.value,
      eventType = eventType.name,
      occurredAt = occurredAt.toEmbedded(),
      summary = summary,
    )

  fun SecurityEventEntity.toDomain(): SecurityEvent =
    try {
      SecurityEvent(
        id = SecurityEventId(id),
        commitmentId = commitmentId?.let { CommitmentId(it) },
        eventType = parseEnumSafe<SecurityEventType>(eventType, "SecurityEventType"),
        occurredAt = occurredAt.toDomain(),
        summary = summary,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid stored SecurityEvent values", e)
    }

  fun AccountabilityMetadata.toEntity(): AccountabilityMetadataEntity =
    AccountabilityMetadataEntity(
      id = AccountabilityMetadataEntity.SINGLETON_ID,
      isPinEnabled = isPinEnabled,
      updatedAtEpochMillis = updatedAtEpochMillis,
    )

  fun AccountabilityMetadataEntity.toDomain(): AccountabilityMetadata =
    try {
      AccountabilityMetadata(
        isPinEnabled = isPinEnabled,
        updatedAtEpochMillis = updatedAtEpochMillis,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid stored AccountabilityMetadata values", e)
    }

  fun FilterDatasetMetadata.toEntity(): FilterDatasetMetadataEntity =
    FilterDatasetMetadataEntity(
      datasetId = datasetId,
      category = category.name,
      sourceName = sourceName,
      sourceUrl = sourceUrl,
      license = license,
      versionCode = versionCode,
      updatedAtEpochMillis = updatedAtEpochMillis,
      contentSha256 = contentSha256,
      signatureAlgorithm = signatureAlgorithm,
      signatureValue = signatureValue,
      recordCount = recordCount,
      trustStatus = trustStatus.name,
      lastError = lastError,
      limitationsNote = limitationsNote,
      previousVersionCode = previousVersionCode,
      previousContentSha256 = previousContentSha256,
    )

  fun FilterDatasetMetadataEntity.toDomain(): FilterDatasetMetadata =
    FilterDatasetMetadata(
      datasetId = datasetId,
      category = parseEnumSafe(category, "DomainCategory"),
      sourceName = sourceName,
      sourceUrl = sourceUrl,
      license = license,
      versionCode = versionCode,
      updatedAtEpochMillis = updatedAtEpochMillis,
      contentSha256 = contentSha256,
      signatureAlgorithm = signatureAlgorithm,
      signatureValue = signatureValue,
      recordCount = recordCount,
      trustStatus = parseEnumSafe(trustStatus, "DatasetTrustStatus"),
      lastError = lastError,
      limitationsNote = limitationsNote,
      previousVersionCode = previousVersionCode,
      previousContentSha256 = previousContentSha256,
    )

  fun RemovalRequest.toEntity(): RemovalRequestEntity =
    RemovalRequestEntity(
      id = id.value,
      commitmentId = commitmentId?.value,
      status = status.name,
      requestedAt = requestedAt.toEmbedded(),
      unlocksAt = unlocksAt.toEmbedded(),
      requestedMonotonicMillis = requestedMonotonicMillis,
      bootSessionToken = bootSessionToken,
      resolvedAt = resolvedAt?.toEmbedded(),
      clockAnomalyObserved = clockAnomalyObserved,
    )

  fun RemovalRequestEntity.toDomain(): RemovalRequest =
    try {
      RemovalRequest(
        id = RemovalRequestId(id),
        commitmentId = commitmentId?.let { CommitmentId(it) },
        status = parseEnumSafe<RemovalRequestStatus>(status, "RemovalRequestStatus"),
        requestedAt = requestedAt.toDomain(),
        unlocksAt = unlocksAt.toDomain(),
        requestedMonotonicMillis = requestedMonotonicMillis,
        bootSessionToken = bootSessionToken,
        resolvedAt = resolvedAt?.toDomain(),
        clockAnomalyObserved = clockAnomalyObserved,
      )
    } catch (e: IllegalArgumentException) {
      throw CorruptLocalDataException("Invalid stored RemovalRequest values", e)
    }

  private inline fun <reified E : Enum<E>> parseEnumSafe(raw: String, typeName: String): E =
    enumValues<E>().firstOrNull { it.name == raw }
      ?: throw CorruptLocalDataException("Unknown $typeName value: $raw")
}

internal data class CommitmentEntitiesBundle(
  val commitment: CommitmentEntity,
  val rules: CommitmentRulesEntity,
  val restrictedApps: List<RestrictedAppEntity>,
  val allowedApps: List<AllowedAppEntity>,
  val scheduleWindows: List<ScheduleWindowEntity>,
  val blockedDomains: List<CommitmentBlockedDomainEntity> = emptyList(),
)

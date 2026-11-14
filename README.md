# MIZAN (ميزان)

Offline-first native Android digital-discipline application built with Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, and Navigation Compose. MIZAN helps users voluntarily commit to intentional technology use without accounts, cloud sync, ads, external analytics, or manipulative engagement.

## Current Status: Milestone 7 — Hardening and Release Preparation

Milestone 7 completes all planned MVP hardening, security, insights, and release-readiness layers on top of Milestones 0–6:

- **Application ID:** `com.ibnmisbah.mizan` (preserving template namespace `com.example`)
- **Accountability PIN Vault & Emergency Enforcement (`domain/pin/*`, `data/security/*`, `feature/*`):**
  - Users can configure, verify, change, or remove a local 4–8 digit numeric accountability PIN in **Settings**.
  - `Pbkdf2PinHasher` derives 256-bit keys with `PBKDF2WithHmacSHA256` (120,000 iterations, 16-byte `SecureRandom` salt, constant-time `MessageDigest.isEqual`, buffer zero-filling).
  - `AndroidKeystorePinCredentialCipher` wraps the vault payload with AES-256-GCM using a non-backed-up `AndroidKeyStore` key stored in `context.noBackupFilesDir`.
  - Progressive verification cooldown (`30s` -> `60s` -> `300s` after 3+ failed attempts), clock-regression-safe lockout checks, and zero plaintext PINs in Room, logs, or `SecurityEvent`s.
  - Commitment Setup enforces configuring a PIN before enabling PIN-gated emergency termination, and Emergency Termination fails closed (`BlockedByUnavailablePinVerification`) if the PIN vault cannot be verified.
- **Voluntary 24-Hour Removal Request (`domain/model/RemovalRequest.kt`, `domain/lifecycle/RemovalRequestStateMachine.kt`, `data/repository/RoomRemovalRequestRepository.kt`):**
  - Room schema **v4** (`MIGRATION_3_4` + exported `4.json` alongside `1.json`/`2.json`/`3.json`) adds `removal_requests` and `daily_usages.blocked_attempts`.
  - Users can initiate a 24-hour cooling-off request in Settings, inspect exact timestamps and remaining wait duration, cancel anytime before execution, or execute deactivation once 24 hours genuinely elapse (conservatively blocking same-boot forward clock jumps and backward clock changes).
  - Disclosed honestly as a voluntary cooling-off deterrent that cannot block OS Settings uninstall, ADB, safe mode, or factory reset.
- **Local Usage Insights (`domain/insights/UsageInsightsCalculator.kt`, `core/designsystem/component/DashboardInsightsCard.kt`):**
  - Tracks blocked app launch attempts per commitment/day in Room without recording browsing history.
  - Computes 7-day trend bars, 7-day total & measured daily average, Hybrid consecutive days within allowance, commitment completion vs emergency-termination totals, and honest prior-vs-recent baseline comparisons (reporting `InsufficientData` until >= 6 measured days exist).
- **Boot/Update Recovery & Battery Optimization Health (`platform/recovery/BootAndUpdateRecoveryReceiver.kt`, `platform/health/BatteryOptimizationMonitor.kt`):**
  - `BootAndUpdateRecoveryReceiver` (`BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`) recovers unfinished usage sessions conservatively, refreshes Accessibility/Usage Access/Battery/VPN health, records non-sensitive recovery `SecurityEvent`s, and attempts background VPN resume only when previously consented and permitted by Android.
  - Play-compliant read-only `BatteryOptimizationMonitor` flags aggressive optimization (`OPTIMIZED`) and links directly to system settings (never declaring `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- **Release Hardening (`AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `proguard-rules.pro`):**
  - Disables OS auto-backup (`android:allowBackup="false"`) with empty backup/extraction rules so Keystore-sealed PIN state and local discipline records never leak to cloud backups.
  - Configures explicit ProGuard/R8 rules for `@Serializable` navigation/domain/DNS DTOs, Room entities, and `AndroidKeyStore`.

### Explicitly Post-MVP

Per `AGENTS.md`:

- **Signed External Filter Feed:** `DisabledRemoteDatasetUpdater` + `NoTrustedKeySignatureVerifier` remain in place until an external blocklist feed, license, and pinned public verification key are selected. User-configured custom `BLOCK`/`ALLOW` domains operate 100% offline.
- **YouTube Shorts-Only Detection:** Intentionally deferred post-MVP due to fragile app/UI heuristics and explicitly **not** substituted by blocking all of `youtube.com`.

### Privacy, Policy, and Platform Limitations

- **Zero Cloud / Zero Tracking:** No accounts, remote servers, analytics SDKs, or ads. Zero DNS query history or plaintext PINs are stored or logged.
- **Best-Effort Android Enforcement:** App blocking (`AccessibilityService`), DNS filtering (local `VpnService`), the accountability PIN, and the 24-hour cooling-off workflow are voluntary self-discipline aids. They can be bypassed via Android Settings uninstall/force-stop, safe mode, ADB, Private DNS / DoH / another active VPN, or device reset. MIZAN never uses Device Owner or Play-restricted permissions (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `QUERY_ALL_PACKAGES`, broad storage access).

## Prerequisites

- **Android Studio / SDK:** Compile SDK `36` (`targetSdk = 36`, `minSdk = 24`)
- **JDK:** Java 11+ (AGP `9.1.1` / Gradle `9.x`)
- **Gradle:** System `gradle` (or Android Studio Gradle sync)

## Project Structure

```text
app/src/main/java/com/example/
├── MizanApplication.kt              # Hilt application entry point
├── MainActivity.kt                  # Single-activity edge-to-edge host + ThemePreference
├── core/
│   ├── common/                      # DispatcherProvider, IdGenerator, PersistenceError formatting
│   ├── designsystem/component/      # M3 cards, Insights, Accessibility, Usage Access, Battery, VPN cards
│   ├── di/                          # Core Hilt bindings (DispatcherProvider, TimeProvider, IdGenerator)
│   ├── navigation/                  # @Serializable routes & MizanNavHost
│   └── time/                        # SystemTimeProvider (calendar/DST midnight lookup) & formatting
├── data/
│   ├── di/                          # Room (MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4) & DataStore bindings
│   ├── local/                       # Room database (v4 + 1.json..4.json schemas), DAOs, DataStore
│   ├── repository/                  # Commitment, DailyUsage, RemovalRequest, UserPreferences, DomainFilter repos
│   └── security/                    # AndroidKeystore AES-GCM cipher & encrypted local PIN vault
├── domain/
│   ├── allowance/                   # SharedAllowanceAccountant, MidnightIntervalSplitter, IntervalOverlapFilter
│   ├── evaluator/                   # RestrictionEvaluator & RestrictionDecision
│   ├── filter/                      # DomainNormalizer, DomainRuleMatcher, DomainFilterModels, DomainFilterRepository
│   ├── insights/                    # UsageInsightsCalculator & baseline/trend/streak metrics
│   ├── lifecycle/                   # CommitmentStateMachine, CommitmentRulesValidator, RemovalRequestStateMachine
│   ├── model/                       # Commitment, Rules, DailyUsage, RemovalRequest, SecurityEvent, Preferences
│   ├── pin/                         # Pbkdf2PinHasher, PinFormatValidator, AccountabilityPinRepository
│   ├── repository/                  # Pure domain repository contracts
│   └── time/                        # Platform-independent DomainDate/Time/WallClockPoint
├── feature/
│   ├── blocking/                    # BlockingActivity, BlockingScreen & BlockingViewModel
│   ├── onboarding/                  # First-launch & revisit onboarding screen
│   ├── home/                        # Dashboard screen, Insights, usage coverage & platform health cards
│   ├── commitments/                 # Commitment setup with installed apps, domain rules & PIN requirement
│   ├── review/                      # Review & explicit agreement screen & ViewModel
│   ├── completion/                  # Completion & next-step choices screen & ViewModel
│   ├── emergency/                   # Emergency termination with verified PIN gate & lockout countdown
│   └── settings/                    # PIN vault, 24h removal request, Privacy, Health, Battery & VPN controls
├── platform/
│   ├── accessibility/               # AppRestrictionAccessibilityService + screen-off/shutdown receiver
│   ├── apps/                        # PackageManagerInstalledAppRepository
│   ├── di/                          # Platform Hilt module
│   ├── dns/                         # DnsWireCodec, Ipv4UdpDnsPacketCodec, DnsQueryProcessor
│   ├── enforcement/                 # AppRestrictionCoordinator, blocked attempt counter, BlockingUiLauncher
│   ├── health/                      # Accessibility, Usage Access, and Battery Optimization monitors
│   ├── recovery/                    # BootAndUpdateRecoveryReceiver & BootRecoveryCoordinator
│   ├── usage/                       # ForegroundUsageTracker, UsageStatsReconciler, BootSessionProvider, InteractiveState
│   └── vpn/                         # DomainFilterVpnService, UnderlyingDnsResolver, VpnHealthMonitor
└── ui/
    ├── MizanApp.kt                  # Adaptive top-level navigation scaffold & onboarding gate
    ├── MizanRootViewModel.kt        # Observes UserPreferencesRepository for root state
    └── theme/                       # Material 3 colors, shapes, typography, theme
```

## Build & Verification Commands

Run local JVM unit and Robolectric tests:

```bash
gradle :app:testDebugUnitTest
```

Build the debug APK:

```bash
gradle :app:assembleDebug
```

# MIZAN (ميزان)

Offline-first native Android digital-discipline application built with Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, and Navigation Compose. MIZAN helps users voluntarily commit to intentional technology use without accounts, cloud sync, ads, external analytics, or manipulative engagement.

## Current Status: Milestone 6 — Website and Domain Filtering

Milestone 6 adds optional, on-device DNS-level website and domain filtering using Android `VpnService`, bounded packet parsing, Room v3 persistence, and explicit user consent/health reporting on top of Milestones 0–5:

- **Application ID:** `com.ibnmisbah.mizan` (preserving template namespace `com.example`)
- **Local DNS-Only VPN Routing (`platform/vpn/DomainFilterVpnService.kt`, `platform/vpn/UnderlyingDnsResolver.kt`):**
  - Establishes a local TUN interface with address `10.120.0.1/32`, virtual DNS server `10.120.0.2`, and single host route `10.120.0.2/32` (`allowBypass()`, `addDisallowedApplication(packageName)`, `SUPPORTS_ALWAYS_ON = false`).
  - Does **not** capture `0.0.0.0/0` or `::/0`; ordinary TCP/UDP/HTTPS browsing traffic bypasses the TUN untouched and is never routed through a remote MIZAN server.
  - Allowed DNS queries are forwarded asynchronously (bounded semaphore) via `VpnService.protect()` + `network.bindSocket()` UDP sockets to the active underlying non-VPN network's IPv4 `LinkProperties.dnsServers` (never hardcoded third-party resolvers).
- **Bounded Pure DNS Engine (`platform/dns/DnsWireCodec.kt`, `Ipv4UdpDnsPacketCodec.kt`, `DnsQueryProcessor.kt`):**
  - Parses IPv4 UDP port 53 DNS frames (TUN MTU 1500, max DNS packet 4096 bytes, header/length bounds, max 16 compression-pointer jumps + visited-offset cycle detection, max 253-char QNAME).
  - Supports `A`, `AAAA`, `HTTPS` (type 65), `SVCB` (type 64), OPT EDNS0 UDP payload-size inspection, and forwarding other UDP record queries; replies to blocked queries locally with `NXDOMAIN` (`ANCOUNT=0`) while preserving transaction IDs, opcode, and `RD` flag.
- **Pure Domain Normalization & Matching (`domain/filter/DomainNormalizer.kt`, `DomainRuleMatcher.kt`):**
  - Case-insensitive ASCII & IDN (`java.net.IDN.toASCII`) normalization, trailing-dot stripping, strict label validation, label-boundary matching (`example.com` matches `example.com` and `sub.example.com`, never `notexample.com` or `example.com.attacker.test`), and explicit precedence (`ALLOW` > `BLOCK`, active commitment lifecycle gates).
- **Dataset Trust & Offline Custom Rules (`domain/filter/DomainFilterModels.kt`, `data/local/dao/DomainFilterDao.kt`, `data/repository/RoomDomainFilterRepository.kt`):**
  - Room schema **v3** (`MIGRATION_2_3` preserved + exported `3.json`) stores user `BLOCK`/`ALLOW` custom rules, category toggles, and dataset metadata/integrity checks without storing any DNS query history.
  - Because no signed built-in or remote filter dataset and pinned public verification key exist in this repository, `DisabledRemoteDatasetUpdater` + `NoTrustedKeySignatureVerifier` keep remote updates disabled and built-in category datasets report `DatasetTrustStatus.MISSING_OR_EMPTY` honestly rather than fabricating a partial blocklist. User-configured custom `BLOCK` and `ALLOW` domains work 100% offline.
- **Consent, Lifecycle & Decoupled Health (`platform/vpn/VpnHealthMonitor.kt`, `core/designsystem/component/VpnFilterHealthCard.kt`, `feature/*`):**
  - Discloses Android's single-VPN limit, domain-level scope, Private DNS/DoH/DoT/VPN/IP bypasses, and local-only routing before launching `VpnService.prepare()`.
  - Represents all 10 honest VPN states (`NOT_CONFIGURED`, `PERMISSION_REQUIRED`, `PERMISSION_DENIED`, `READY_INACTIVE`, `STARTING`, `ACTIVE`, `STOPPED_BY_USER`, `REVOKED`, `ANOTHER_VPN_ACTIVE`, `DEGRADED_ERROR`) plus explicit missing/incomplete dataset and Private DNS warnings, respects user stops without silent restart, stops cleanly on commitment completion/emergency termination, and preserves app blocking and usage tracking independently of VPN failures.

### Explicitly Deferred / Future Work (Milestone 7 & Post-MVP)

Per `AGENTS.md` and the Milestone 6 specification, the following remain deferred:

- Accountability PIN cryptography / Android Keystore verification, historical insights polish, battery/OEM hardening, and signed filter dataset feed integration once a verified external feed & pinned key are selected
- YouTube Shorts-only detection research (post-MVP, explicitly **not** substituted by blocking all of `youtube.com`)

### Privacy, Policy, and Platform Limitations

- **Zero DNS Query History:** MIZAN never persists, transmits, or logs queried domain names or browsing history. Security events record only non-sensitive lifecycle transitions (`VPN_FILTER_STARTED`, `VPN_FILTER_STOPPED`, `VPN_FILTER_REVOKED`, `VPN_FILTER_CONFLICT_DETECTED`, `VPN_FILTER_DEGRADED`).
- **DNS Filtering Scope & Bypasses:** Local DNS filtering cannot inspect HTTPS paths/content (no TLS MITM), does not support TCP DNS / DNS-over-HTTPS (DoH) / Android Private DNS (DoT) / IP-literal connections / proxies, and can be displaced by another VPN because Android permits only one active VPN at a time. Android's `VpnFilterMonitor` explicitly surfaces Private DNS (`LinkProperties.isPrivateDnsActive`) warnings to the user.
- **Play Policy & FGS Type:** Uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` with explicit manifest subtype declaration and low-importance notification only while the local DNS VPN is actively running (`SUPPORTS_ALWAYS_ON = false`).
- **Existing Accessibility & Usage Access Boundaries:** Foreground window package transitions (`canRetrieveWindowContent="false"`) and optional `UsageStatsManager` reconciliation remain strictly offline on-device and continue operating independently even if VPN filtering stops or fails.

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
│   ├── designsystem/component/      # M3 components, AccessibilityHealthCard, UsageAccessCard, VpnFilterHealthCard
│   ├── di/                          # Core Hilt bindings (DispatcherProvider, TimeProvider, IdGenerator)
│   ├── navigation/                  # @Serializable routes & MizanNavHost
│   └── time/                        # SystemTimeProvider (calendar/DST midnight lookup) & formatting
├── data/
│   ├── di/                          # Room (MIGRATION_1_2, MIGRATION_2_3) & DataStore Hilt bindings
│   ├── local/                       # Room database (v3 + 1.json/2.json/3.json schemas), DAOs, DataStore
│   └── repository/                  # Commitment, UserPreferences, and DomainFilter repository implementations
├── domain/
│   ├── allowance/                   # SharedAllowanceAccountant, MidnightIntervalSplitter, IntervalOverlapFilter
│   ├── evaluator/                   # RestrictionEvaluator & RestrictionDecision
│   ├── filter/                      # DomainNormalizer, DomainRuleMatcher, DomainFilterModels, DomainFilterRepository
│   ├── lifecycle/                   # CommitmentStateMachine & CommitmentRulesValidator
│   ├── model/                       # Commitment, Rules, DailyUsage, UsageCoverageStatus, SecurityEvent, Preferences
│   ├── repository/                  # Pure domain repository contracts
│   └── time/                        # Platform-independent DomainDate/Time/WallClockPoint
├── feature/
│   ├── blocking/                    # BlockingActivity, BlockingScreen & BlockingViewModel
│   ├── onboarding/                  # First-launch & revisit onboarding screen
│   ├── home/                        # Dashboard screen, usage coverage, allowance status, Accessibility & VPN health
│   ├── commitments/                 # Commitment setup with installed-app selection & website/domain rule editor
│   ├── review/                      # Review & explicit agreement screen & ViewModel
│   ├── completion/                  # Completion & next-step choices screen & ViewModel
│   ├── emergency/                   # Emergency termination screen & ViewModel
│   └── settings/                    # Settings, privacy, Accessibility, Usage Access, and VPN/Dataset controls
├── platform/
│   ├── accessibility/               # AppRestrictionAccessibilityService + screen-off/shutdown receiver
│   ├── apps/                        # PackageManagerInstalledAppRepository
│   ├── di/                          # Platform Hilt module
│   ├── dns/                         # DnsWireCodec, Ipv4UdpDnsPacketCodec, DnsQueryProcessor
│   ├── enforcement/                 # AppRestrictionCoordinator, PackageNameValidator, BlockingUiLauncher
│   ├── health/                      # AndroidEnforcementHealthMonitor & AndroidUsageAccessMonitor
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

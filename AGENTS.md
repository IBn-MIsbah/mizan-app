# MIZAN — Agent Development Instructions

## 1. Product definition

MIZAN (ميزان) is an offline-first native Android digital-discipline application. Its purpose is to help users voluntarily commit to intentional technology use. It is not merely a screen-time dashboard and must not use shame, manipulative engagement, ads, feeds, or unnecessary notifications.

The app is local-data-first:
- No mandatory account.
- No required cloud service or sync.
- No advertisements or external analytics.
- Do not upload app usage, browsing activity, blocked domains, commitments, or other sensitive user activity.
- Explain sensitive Android permissions clearly and request them only when needed.

Android is the first platform. iOS may be considered later, but do not introduce cross-platform complexity now.

## 2. Product decisions — preserve these

### Commitment lifecycle
- A commitment initially lasts 7 days.
- Before activation, show the exact rules and require the user to explicitly agree.
- Do not silently activate or renew commitments.
- At the end of seven days, the user chooses to finish, begin another detox with new configuration and agreement, or enter discipline mode.
- Emergency termination is immediate after a warning and explicit confirmation.
- If accountability PIN protection is enabled, require the trusted-person PIN for emergency termination.
- The optional 24-hour removal request is a voluntary deterrent, not tamper-proof protection. Users can cancel it. When the period ends, permit deactivation/removal. Never claim that it prevents uninstall, force-stop, safe mode, ADB, factory reset, or other privileged bypasses.

### Restriction modes
Support these modes:
1. **Strict:** selected apps are blocked throughout the commitment.
2. **Scheduled:** selected apps are blocked during configured time windows.
3. **Hybrid:** scheduled blocking plus one shared daily allowance.

Strict blocking overrides allowance. In hybrid mode:
- The default allowance is 30 minutes per day.
- The user configures it before commitment activation.
- It is locked during an active commitment.
- The allowance is shared across all selected restricted apps, not granted separately per app.
- Count foreground use only.
- Reset at local midnight.

### App selection and allowlist
- Let users select installed apps individually.
- Suggested apps may include Instagram, Facebook, TikTok, Reddit, Snapchat, X, and Telegram, but suggestions must not imply those apps are installed.
- Allow custom selection of installed apps.
- Provide a user-controlled allowlist for phone/emergency functions, SMS, banking, maps, work apps, and custom apps.
- Do not block essential functions by default.

### Website filtering
- Website filtering targets adult/pornography domains, social-media domains, and user-defined domains.
- Use Android `VpnService` for local DNS/domain filtering; do not route user traffic through a remote VPN server.
- Domain filtering cannot reliably filter HTTPS URL paths. Do not claim path-level filtering.
- Disclose limitations such as encrypted DNS, other VPNs, proxies, and unsupported network configurations.
- Android permits only one active VPN service at a time; explain this limitation.
- Do not claim that website filtering is impossible to bypass.

### Domain-filter database
- Cache the filter database locally for offline use.
- Updates may occur when online; support an optional Wi-Fi-only preference and manual update.
- Verify authenticity/integrity and version metadata before applying an update.
- Apply updates atomically; preserve and roll back to the last known-good database on failure.
- Keep the provider implementation replaceable. Do not select or hard-code a provider without evaluating coverage, licensing, update policy, and privacy.

### YouTube Shorts
- Shorts-only blocking is post-MVP and technically fragile.
- Do not block all of YouTube as a substitute.
- Do not claim reliable Shorts detection until tested against real app versions and UI variations.
- Isolate any future detection logic so it can be disabled or updated independently.

### Anti-tampering and service health
Monitor, where Android APIs permit:
- Permission revocation.
- Accessibility service state.
- Usage access state.
- VPN state.
- Reboot and service recovery.
- Clock changes.
- Service/process interruptions.
- Battery optimization restrictions.

Describe this as best-effort integrity monitoring, never guaranteed uninterrupted enforcement. Do not attempt Device Owner provisioning, factory-reset prevention, or other device-administration escalation.

## 3. Technology stack

Use native Android development:
- Kotlin.
- Jetpack Compose and Material 3.
- MVVM with Clean Architecture boundaries.
- Hilt for dependency injection.
- Room/SQLite for structured local data.
- DataStore for small preferences.
- WorkManager for deferrable work such as filter updates and reconciliation; never use it as a real-time enforcement mechanism.
- AccessibilityService for foreground-app observation and app-blocking UI where permitted.
- UsageStatsManager for usage reporting and reconciliation, not as the sole real-time enforcement mechanism.
- Android `VpnService` for local DNS/domain filtering.
- Android Keystore for cryptographic key protection.
- Navigation Compose.
- JUnit and Android instrumentation/UI tests.

Use current stable dependency versions that are mutually compatible. Prefer the Gradle version catalog (`gradle/libs.versions.toml`). Avoid unnecessary dependencies.

## 4. Architecture boundaries

Organize code by responsibility. A reasonable starting structure is:

- `app/` — Android application entry point, navigation host, DI setup.
- `core/` — shared utilities, time abstractions, common UI components, and platform-independent primitives.
- `domain/` — entities, value objects, repository interfaces, use cases, commitment state machine, restriction evaluation, allowance rules, scheduling, and completion/termination logic.
- `data/` — Room database, DAOs, DataStore, repository implementations, persistence mappings, filter metadata and update verification.
- `feature/` — Compose screens and ViewModels grouped by feature.
- `platform/` — Android-specific accessibility, usage access, VPN, boot recovery, permission and service-health integrations.

Adapt this structure if the generated project or build system makes a different clean arrangement more appropriate. Avoid creating empty abstractions solely to match a diagram.

Strict dependency rules:
- Domain logic must not depend on Compose, Activity, Android Service, Room, or UI classes.
- Composables render state and emit user actions; they do not own business rules.
- ViewModels coordinate presentation state and domain use cases.
- Android services adapt platform events to domain logic; do not duplicate restriction rules inside each service.
- Reuse the same restriction evaluator across enforcement mechanisms and tests.

## 5. Time integrity and recovery

Use:
- Wall-clock/local calendar time for scheduled windows and local-midnight allowance resets.
- Monotonic elapsed time for measuring durations where available.

Persist enough information to recover conservatively after process death and reboot, including:
- Commitment start/end.
- Last observed wall-clock time.
- Monotonic timestamp where meaningful.
- Boot identity or reboot marker where available.
- Last allowance reset.
- Accumulated daily usage.
- Last reconciliation time.

Reconcile conservatively after downtime or reboot. Detect suspicious wall-clock changes where feasible. An offline app cannot guarantee trusted time against a user with sufficient device control; document this limitation rather than promising tamper-proof timing.

## 6. Local data model

Expected concepts/entities include:
- `Commitment`
- `CommitmentRules`
- `RestrictedApp`
- `AllowedApp`
- `BlockedDomain`
- `FilterDatabaseMetadata`
- `DailyUsage`
- `SecurityEvent`
- `AccountabilitySettings` (salted PIN hash; never plaintext PIN)
- `EmergencyEvent`
- `RemovalRequest`

Choose appropriate normalized Room tables, indexes, constraints, and migrations. Do not store derived data redundantly unless there is a clear consistency strategy. Never log PINs, secrets, or sensitive usage details.

## 7. Security and accountability PIN

- Accountability PIN is optional and configured locally.
- Store only a salted password hash using an appropriate password-hashing approach; never store plaintext PIN.
- Use Android Keystore for suitable cryptographic key material.
- Avoid logging PIN input, hashes, or security-sensitive values.
- Explain that a local PIN is an accountability friction mechanism, not strong protection against a device owner who can modify or reset the device.

## 8. UI and navigation direction

The UI should be minimal, fast, clear, and usable in light and dark themes. Avoid shame language and engagement tricks. Always make the current commitment state and active restrictions understandable.

Expected screens/flows:
- Onboarding and concise permission explanations.
- Dashboard / current commitment status.
- Commitment setup: mode, app selection, allowlist, schedule, allowance, domain-filter options.
- Review rules and explicit agreement before activation.
- Restricted-app and blocked-domain management.
- Usage and insights: usage, allowance, blocked attempts, commitment history, weekly trends, consecutive days within allowance, and baseline comparisons when enough data exists.
- Accountability PIN settings.
- Emergency termination confirmation.
- Commitment completion and next-step choice.
- Settings, privacy, permission/service health, and filter database update status.

Do not build the complete product UI during the foundation milestone. Use a small, coherent navigation scaffold first.

## 9. Testing expectations

Test domain rules independently of Android:
- Commitment state transitions.
- Explicit activation and completion.
- Strict, scheduled, and hybrid restriction evaluation.
- Strict-mode precedence.
- Shared allowance accounting and daily reset.
- Allowlist precedence.
- Emergency termination and optional PIN requirement.
- Removal request timing and cancellation.
- Clock-change and recovery behavior.

Add Room/database tests and Android integration tests when those layers are introduced. For platform enforcement, test on physical devices and document API/version limitations. Never treat a successful unit test as proof that AccessibilityService or VPN enforcement works on every device.

## 10. Development milestones

Work in order. Do not implement later milestones early.

### Milestone 0 — Project foundation only
- Inspect the existing Android Studio-generated project before changing it.
- Establish a clean package/module structure appropriate to the current project.
- Configure Kotlin, Compose, Material 3, and compatible Gradle dependencies.
- Add Hilt and a minimal dependency-injection setup.
- Add Navigation Compose and a minimal navigation scaffold.
- Add a README describing the app, prerequisites, build/run commands, and current milestone.
- Add or update required configuration and repository documentation.
- Ensure local machine files and secrets are excluded from Git.
- Run the Gradle build and relevant tests; fix failures.
- Report project structure, important dependency versions, commands executed, and results.
- Stop after Milestone 0 and wait for review.

Do not implement app blocking, AccessibilityService enforcement, VPN filtering, usage tracking, commitment business logic, or complete product UI in Milestone 0.

### Milestone 1 — Domain foundation
Define and test domain models, commitment lifecycle/state machine, restriction rules, schedule evaluation, allowance accounting, and time abstractions. No platform enforcement yet.

### Milestone 2 — Local persistence
Implement Room, DataStore, repositories, migrations, and persistence tests for commitments, app selections, rules, usage, and security events.

### Milestone 3 — Core user experience
Implement onboarding, permission education, commitment configuration/review/activation, dashboard, completion, emergency flow, and settings using real domain and data layers.

### Milestone 4 — App restriction enforcement
Implement and validate AccessibilityService-based foreground app observation and blocking flow, permission/service health, and recovery. Clearly document platform limitations.

### Milestone 5 — Usage and insights
Implement foreground usage accounting, reconciliation, shared allowance behavior, and local insights. Validate resets and recovery.

### Milestone 6 — Website filtering
Implement local VPN/DNS filtering, domain lists, signed/versioned database updates, atomic replacement/rollback, and network compatibility tests.

### Milestone 7 — Hardening and release preparation
Complete security/privacy review, accessibility and UI polish, battery/performance checks, migration tests, device/API compatibility testing, and release documentation. Treat Shorts-specific blocking as a separately evaluated post-MVP feature.

## 11. Agent operating rules

- Read this file completely before making changes.
- Work only on the milestone explicitly requested.
- Inspect the repository and existing code before editing.
- Preserve working project configuration unless a change is justified.
- Prefer small, reviewable changes; do not rewrite unrelated files.
- Do not invent product decisions that conflict with this document. If a material decision is missing, ask before committing to it.
- Do not add accounts, cloud sync, analytics, ads, remote traffic routing, or unnecessary permissions.
- Do not claim Android restrictions are tamper-proof or universally reliable.
- Use current compatible stable versions; explain version choices.
- Run relevant build/tests after changes and report exact outcomes. Never claim a command passed unless it was run.
- Do not proceed to the next milestone without the user's review and approval.

# MIZAN — Milestone 1: Domain Foundation

## 1. Role and operating instructions

You are implementing Milestone 1 of **MIZAN (ميزان)**, an offline-first native Android digital-discipline application.

Before changing code:

1. Read the repository-root `AGENTS.md` completely. It is the authoritative product and engineering specification.
2. Inspect the entire existing repository and identify the actual package/module structure, Kotlin version, Gradle configuration, dependency versions, and existing tests.
3. Inspect the result of Milestone 0 and preserve its working foundation.
4. Do not assume a file, class, package, dependency, or architecture exists until you have inspected the repository.
5. Provide a short implementation plan based on the actual codebase before editing.

If `AGENTS.md` is missing, stop and report that. If Milestone 0 is incomplete or the project does not build, stop and report the blocker instead of layering domain work onto a broken foundation.

## 2. Objective

Implement **domain logic only**: the core models, rules, state transitions, time abstractions, and automated unit tests required for MIZAN.

The domain layer must be independent of Android framework APIs, Jetpack Compose, Activities, Services, Room, DataStore, and UI code.

Use the existing project conventions where they are sound. Do not restructure the entire project just to match a preferred folder diagram.

## 3. Scope

### 3.1 Domain models and value types

Design appropriate immutable Kotlin domain types for:
- `Commitment`
- `CommitmentRules`
- `RestrictedApp`
- `AllowedApp`
- `DailyUsage`

Introduce only necessary supporting types, such as commitment/app identifiers, restriction mode, commitment status, schedule windows, allowance duration, local date, and restriction decision/reason.

Requirements:
- Prefer immutable data structures and explicit types over loosely related primitives.
- Validate invalid values at construction boundaries or through explicit validated factories.
- Avoid redundant or derived values unless consistency requires them.
- Keep Android package-manager objects and UI models out of the domain layer.
- Do not introduce Room annotations or persistence concerns.

### 3.2 Commitment lifecycle

Implement a clear, testable commitment state machine.

Product rules:
- A new commitment is a draft/configuration and is not active until the user explicitly confirms the agreement.
- Initial commitment duration is seven days.
- No silent activation or renewal.
- At the end of the commitment, it becomes completed and the user must explicitly choose what happens next.
- Emergency termination is an explicit transition, distinct from normal completion.
- If accountability PIN protection is enabled, emergency termination must require a successful PIN-verification result from the caller. Do not implement PIN storage or cryptography in this milestone.
- Invalid transitions must return a clear domain error/result or fail through a documented, consistent approach.

Model transitions such as:
- Draft/configured → Active only after explicit agreement.
- Active → Completed when the end condition is reached.
- Active → EmergencyTerminated only through the explicit emergency flow and required authorization.
- Completed → no automatic new commitment; a new commitment must be created separately with new configuration and agreement.

Do not use a loose collection of Booleans to represent lifecycle state. Make invalid states difficult to express.

### 3.3 Restriction evaluation

Implement one central, pure domain evaluator that determines whether a selected app should be restricted at a supplied point in time.

Support:
1. **Strict:** selected restricted apps are blocked throughout the active commitment.
2. **Scheduled:** selected restricted apps are blocked only during configured schedule windows.
3. **Hybrid:** scheduled blocking plus one shared daily allowance.
4. **Allowlist precedence:** an explicitly allowlisted app must not be blocked.
5. **Strict precedence:** strict blocking cannot be bypassed by remaining allowance.
6. **Commitment state:** restrictions do not apply without an active commitment.

The evaluator must receive inputs explicitly. It must not read the system clock, query installed apps, inspect Android services, or access persistence.

Return an explicit decision and reason, such as:
- Allowed.
- Blocked by strict mode.
- Blocked by schedule.
- Allowed by remaining allowance.
- Blocked because allowance is exhausted.
- Allowed because app is allowlisted.
- Allowed because no active commitment exists.

Callers and tests must be able to distinguish reasons.

#### Schedule semantics

Define and test:
- Selected days of the week and local start/end times.
- Half-open intervals `[start, end)` (start included, end excluded).
- Windows crossing midnight.
- Invalid or zero-length windows.
- Explicit local date/time inputs; no hidden system-clock access.

### 3.4 Shared daily allowance

Implement pure domain accounting for hybrid-mode allowance.

Product rules:
- Default allowance: 30 minutes per local calendar day.
- One shared pool across all selected restricted apps, not a separate allowance per app.
- Count foreground use only. This milestone receives already-measured foreground durations as inputs; it does not measure Android app usage.
- Allowance configuration is part of commitment rules and is locked while a commitment is active.
- Reset at local midnight according to the supplied local calendar date.
- Strict blocking overrides allowance.
- Define and document how scheduled blocking and allowance interact in hybrid mode, then cover that behavior in tests.
- Reject negative usage durations and invalid allowance values.
- Prevent double-counting identifiable usage events if the chosen API supports event identity.

Design the API so that creating separate per-app allowance pools is difficult. The shared total belongs to the commitment/day.

### 3.5 Time abstractions

Introduce platform-independent abstractions for:
- **Wall-clock/local time:** schedules, commitment calendar dates, and local-midnight resets.
- **Monotonic elapsed time:** measuring durations where possible without assuming wall-clock stability.

Use time types compatible with the project's Kotlin/JDK configuration. Inspect compatibility before adding dependencies; do not add one unnecessarily.

Domain logic receives time values or a time provider through an interface. It must not directly call Android clock APIs or scatter system-clock calls throughout business logic.

Document:
- Monotonic timestamps may not be comparable across device reboot.
- Offline software cannot guarantee trusted time against a user with sufficient device control.
- Clock-change and reboot recovery require conservative reconciliation in a later milestone.

Do not implement boot detection, persistence, or recovery services here.

## 4. Architecture requirements

- Domain code is independent of Android framework and UI dependencies.
- Restriction evaluation and commitment rules are pure and deterministic.
- Add interfaces only where they establish a real boundary, such as a time provider or future repository contract.
- Do not create empty use cases, repositories, or abstractions solely for appearance.
- Do not put business rules in Composables, Activities, ViewModels, or Android Services.
- Do not duplicate restriction evaluation across features.
- Follow the existing package/module layout unless there is a concrete reason to adjust it.
- If the project has a single app module, a separated domain package may be sufficient. Do not add a Gradle module without explaining why.

## 5. Testing requirements

Write automated unit tests that do not require an emulator, physical device, network, Room, or Android framework runtime.

At minimum, test:

### Commitment lifecycle
- A new commitment is not active before explicit agreement.
- Explicit agreement activates it.
- Invalid transitions are rejected.
- Completion occurs at the correct end boundary.
- Completion does not auto-renew.
- Emergency termination differs from normal completion.
- PIN-protected emergency termination cannot proceed without successful authorization.

### Restriction evaluation
- Strict mode blocks a selected app throughout an active commitment.
- Scheduled mode blocks inside the schedule and allows outside it.
- Hybrid mode combines schedule and shared allowance according to documented semantics.
- Allowlisted apps remain allowed.
- Strict mode takes precedence over allowance.
- Draft, inactive, completed, and emergency-terminated commitments do not enforce restrictions.
- Each decision includes the correct reason.

### Schedule behavior
- Start and end boundaries.
- Day selection.
- Overnight windows.
- Invalid/zero-length windows.
- Daylight-saving/time-zone behavior where applicable; document limitations of the chosen representation.

### Allowance accounting
- Default is 30 minutes.
- Usage from different restricted apps consumes the same shared pool.
- Remaining allowance is correct.
- Exhausted allowance blocks where hybrid rules require it.
- Local-midnight reset uses the supplied local date.
- Negative durations are rejected.
- Duplicate events are not counted twice if event identity is supported.
- Allowance cannot be changed during an active commitment.

### Time
- Behavior is deterministic with a fixed supplied clock.
- Wall-clock changes do not corrupt monotonic-duration calculations within the supported model.
- Reboot limitations are documented and not falsely treated as solved.

Use descriptive test names and include boundary/edge cases, not only happy paths.

## 6. Explicitly out of scope

Do **not** implement:
- Room, SQLite, DAOs, migrations, or database entities.
- DataStore or repository implementations.
- AccessibilityService or actual app blocking/overlay screens.
- App discovery or installed-app queries.
- UsageStatsManager or real device usage measurement.
- VPNService, DNS filtering, domain lists, or network interception.
- Filter database downloads or updates.
- PIN setup, PIN hashing, or Android Keystore integration.
- Compose screens or complete UI.
- WorkManager jobs, boot receivers, foreground services, permission monitoring, or anti-tampering services.
- Cloud sync, accounts, analytics, advertisements, or remote telemetry.

Do not simulate platform capabilities in domain code.

## 7. Implementation workflow

1. Read `AGENTS.md` and inspect the repository.
2. Confirm Milestone 0 is complete and the project builds.
3. Report the proposed domain package/module structure and key model/API decisions.
4. Implement domain models and validation.
5. Implement lifecycle/state-transition logic.
6. Implement schedule and restriction evaluation.
7. Implement shared allowance accounting.
8. Implement time abstractions.
9. Add and run unit tests.
10. Run relevant Gradle build and test tasks.
11. Fix failures caused by this milestone.
12. Review the final diff for scope creep and accidental platform dependencies.

If a product rule is ambiguous and choosing an interpretation would materially change user-visible behavior, identify the ambiguity and ask before implementing that part. Do not silently invent a product decision.

## 8. Completion report

When finished, provide:
- Summary of domain behavior implemented.
- Actual package/module structure.
- Important model and API decisions.
- Files created, changed, and deleted.
- Dependency changes, with reasons and versions.
- Exact build and test commands executed.
- Actual build/test results, including failures or skipped tests.
- Known limitations and unresolved product decisions.
- Confirmation that no out-of-scope platform enforcement or persistence was added.

Stop after Milestone 1. Do not begin Milestone 2 until the user reviews and approves the result.

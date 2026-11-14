# MIZAN — Milestone 3: Core User Experience

## 1. Role and operating instructions

You are implementing Milestone 3 of **MIZAN (ميزان)**, an offline-first native Android digital-discipline application.

Before changing code:

1. Read the repository-root `AGENTS.md` completely.
2. Read the approved Milestone 3 scope in this document completely.
3. Inspect the actual repository and the completed Milestones 0, 1, and 2.
4. Verify the existing app builds and the existing automated tests pass before editing.
5. Inspect the actual navigation, Compose theme/components, ViewModels, domain use cases, repositories, Room database, and DataStore contracts. Do not assume names or paths.
6. Preserve existing working architecture and behavior. Do not replace existing implementations just to match a preferred template.
7. Present a concise implementation plan based on the actual codebase before editing.

If a prior milestone is incomplete, the project does not build, or required domain/persistence contracts are missing, stop and report the blocker. Do not fabricate repositories or bypass missing foundations.

## 2. Objective

Implement the **first usable core MIZAN experience** using Jetpack Compose and the existing domain/data layers.

This milestone connects the user's main flows to real local domain and persistence behavior:
- First launch and onboarding.
- Clear permission education and permission-status presentation.
- Commitment configuration and review.
- Explicit commitment agreement and activation.
- Dashboard showing the current commitment state.
- Commitment completion and explicit next-step choice.
- Emergency termination confirmation.
- Basic settings and privacy information.

This is a core user experience milestone, not the enforcement milestone. The app must not claim to block apps or websites until those platform capabilities are implemented and verified in later milestones.

## 3. Product principles

Preserve these principles from `AGENTS.md`:

- MIZAN helps users voluntarily commit to intentional technology use.
- Keep the interface minimal, fast, understandable, and accessible.
- Support light and dark themes using the existing Material 3 foundation.
- Avoid shame language, guilt, manipulative engagement, ads, feeds, and unnecessary notifications.
- Make the current commitment state and configured rules clear.
- Explain sensitive Android permissions before asking users to grant them.
- Keep user data local. Do not add accounts, cloud sync, analytics, advertisements, or telemetry.
- Do not imply enforcement is active when it is not implemented or its required permission/service is unavailable.

## 4. Main navigation and screens

Adapt the exact navigation structure to the existing app. Avoid unnecessary tabs or screens. Provide a coherent route structure for the flows below.

### 4.1 Onboarding

Create a concise first-launch introduction that explains:
- What MIZAN does.
- The voluntary seven-day commitment concept.
- The difference between configuring a commitment and activating it.
- That app and website restriction features require additional Android capabilities and permissions.
- That data is stored locally.
- That no account is required.

Requirements:
- Do not request all permissions immediately on first launch.
- Explain each sensitive permission at the point where it becomes relevant.
- Persist onboarding completion using the existing DataStore/repository abstraction.
- Allow the user to revisit privacy and permission explanations from Settings.
- Do not use onboarding to imply that restrictions are already active.

### 4.2 Dashboard

Provide a clear dashboard for the current state.

It should handle at least:
- No commitment configured or active.
- Draft/configuration in progress, if drafts are persisted by the existing model.
- Active commitment.
- Completed commitment.
- Emergency-terminated commitment.

For an active commitment, show relevant information supported by real domain data:
- Commitment status.
- Start and end dates/times.
- Selected restriction mode.
- Selected restricted apps and allowlist summary.
- Schedule summary where relevant.
- Daily allowance and remaining amount only if the available persisted data is authoritative.
- Permission/service readiness status only for permissions actually inspected by an implemented platform component.

Do not invent usage statistics, blocked-attempt counts, or enforcement status. If enforcement is not implemented, state that clearly.

### 4.3 Commitment setup

Build a guided, understandable configuration flow based on the existing domain model.

The user should be able to configure the supported product options:
- Restriction mode: Strict, Scheduled, or Hybrid.
- Restricted app selections from the app-selection data available in the current milestone.
- Allowlist selections from available app-selection data.
- Schedule windows and selected days for Scheduled/Hybrid modes.
- Shared daily allowance for Hybrid mode, defaulting to 30 minutes.
- Website-filter preferences only if the domain model already supports configuration; do not imply filtering is operational.

Important:
- Do not implement installed-app discovery in this milestone. That belongs to platform integration. If app selection requires installed-app data that is not yet available, provide a clearly marked development-safe placeholder or an interface backed by existing approved data, and report the limitation. Do not fabricate installed packages.
- Validate configuration using domain validation, not only UI checks.
- Keep allowance configuration editable before activation but immutable during an active commitment.
- Prevent invalid or contradictory schedule configuration.
- Do not add product rules that conflict with `AGENTS.md`.

### 4.4 Review and explicit agreement

Before activation, show a review screen that summarizes the exact commitment:
- Duration and start/end.
- Restriction mode.
- Selected restricted apps and allowlist.
- Schedule windows.
- Shared daily allowance where applicable.
- Any known limitations or unavailable enforcement capabilities.

Require a deliberate, explicit agreement action before activation. Do not activate from a generic Continue button or automatically on app launch.

The activation operation must call the existing domain use case/state transition and persist the result through the existing repository. Do not duplicate lifecycle rules in the ViewModel or Composable.

### 4.5 Commitment completion

When the domain determines the seven-day commitment has ended:
- Display the completed state.
- Do not auto-renew.
- Present explicit choices consistent with `AGENTS.md`: finish, enter discipline mode, or configure a new detox with new rules and a new agreement.
- Do not treat a new commitment as a mutation of the old completed commitment.
- Persist user choice and any newly created commitment through the existing domain/data contracts.
- If “discipline mode” is not fully defined in the existing domain model, do not invent its enforcement behavior. Present the unresolved choice clearly and ask for a product decision, or implement only the approved state representation.

### 4.6 Emergency termination

Create an explicit emergency termination flow:
- Explain the consequence of terminating early.
- Require a deliberate confirmation.
- If accountability PIN protection is enabled in the existing domain contract, require successful PIN verification before invoking the termination operation.
- Do not implement PIN storage, hashing, or verification in this milestone if it does not already exist as an approved secure component.
- If the required PIN flow is not yet available, fail closed for the protected operation and report the blocker; do not bypass the PIN requirement.
- Record the emergency termination through the existing domain/data operation.
- Do not present emergency termination as normal completion.

### 4.7 Settings and privacy

Provide a basic Settings area with:
- Theme preference if supported by the existing DataStore.
- Onboarding/privacy information.
- Local-data statement.
- Permission explanations and status only where actual status APIs exist.
- App version/build information where available.
- A clear explanation of current feature limitations.

Do not add settings for unimplemented features unless they are explicitly labelled as not yet available and do not imply they work.

## 5. Architecture and implementation rules

- Use Jetpack Compose and Material 3 consistent with the existing project.
- Use ViewModels for screen state and user actions.
- Use the existing domain use cases and repository interfaces.
- Use Kotlin Flow/StateFlow according to existing project conventions.
- Keep Composables focused on rendering state and forwarding events.
- Keep business rules in the domain layer.
- Keep persistence access behind repository abstractions.
- Use Hilt for dependency injection as already configured.
- Use Navigation Compose and the existing navigation foundation.
- Represent UI state explicitly; avoid large collections of unrelated Booleans.
- Handle loading, empty, success, and error states.
- Preserve state across configuration changes and process recreation where appropriate using ViewModels and persisted domain data.
- Do not put Android permission requests directly inside reusable Composables without a clear lifecycle-aware boundary.
- Do not request permissions the app does not yet use.
- Avoid introducing additional libraries unless justified by a concrete need and compatible with the existing dependency versions.

## 6. Visual and interaction requirements

- Follow the existing MIZAN visual direction and Material 3 theme.
- Provide usable light and dark themes.
- Use clear typography hierarchy, consistent spacing, and accessible touch targets.
- Support screen readers with meaningful labels and content descriptions.
- Do not communicate restriction status using color alone.
- Provide clear validation and error messages.
- Confirm destructive or consequential actions.
- Avoid excessive animations and expensive visual effects.
- Ensure screens remain usable on smaller Android devices.
- Do not add decorative charts with fabricated data.

## 7. Testing requirements

Add tests appropriate to the existing architecture.

### ViewModel/presentation tests
- Onboarding completion and persistence.
- Dashboard state for no commitment, active, completed, and terminated states.
- Configuration validation and submission.
- Review screen data matches the actual configured commitment.
- Activation requires explicit agreement.
- Activation failures are displayed and do not create a false active state.
- Completion offers explicit next-step choices and does not auto-renew.
- Emergency termination requires confirmation.
- PIN-protected termination cannot bypass authorization.
- Settings reflect persisted preferences.

### UI/instrumentation tests
Where existing test infrastructure supports them, test:
- Main navigation routes.
- Onboarding completion.
- Commitment setup and review.
- Explicit activation interaction.
- Dashboard rendering for representative states.
- Completion and emergency confirmation flows.
- Basic accessibility semantics for important controls.

Use fakes/test doubles for repositories and domain use cases where appropriate. Do not require real app-blocking or VPN functionality for these tests.

### Regression
- Run all existing unit tests.
- Run relevant instrumentation/UI tests where practical.
- Run the debug build.
- Verify no Milestone 1 domain rules have been copied into the presentation layer.

## 8. Explicitly out of scope

Do **not** implement:
- AccessibilityService or actual app-blocking enforcement.
- Installed-app discovery or package-manager integration.
- UsageStatsManager or real usage measurement.
- Actual blocked-attempt tracking.
- VPNService, DNS filtering, or website blocking.
- Domain database downloads or updates.
- WorkManager scheduling.
- Boot receivers, foreground services, or anti-tampering monitoring.
- PIN cryptography, PIN storage, or new accountability security mechanisms.
- Cloud sync, accounts, remote APIs, analytics, advertisements, or telemetry.
- Features from Milestones 4–7.

Do not simulate unavailable platform enforcement and present it as real.

## 9. Implementation workflow

1. Read `AGENTS.md` and this milestone document.
2. Inspect and verify the completed Milestones 0–2.
3. Run the existing build and tests before editing.
4. Report the proposed screen/navigation structure and how each screen maps to existing domain use cases and repositories.
5. Identify any missing domain or persistence capability before implementation. Ask if a missing capability requires a product decision.
6. Implement onboarding and navigation integration.
7. Implement dashboard states.
8. Implement commitment configuration, validation, review, and explicit activation.
9. Implement completion and emergency termination flows using existing domain operations.
10. Implement basic settings and privacy information.
11. Add presentation and UI tests.
12. Run relevant Gradle build/test tasks and fix failures.
13. Review the final diff for scope creep, duplicated business logic, false enforcement claims, and privacy issues.

Do not make changes to Milestones 1 or 2 domain/persistence contracts merely to simplify UI implementation. If a contract is genuinely insufficient, explain the gap and propose the smallest change for approval.

## 10. Completion report

When finished, provide:
- Summary of user flows and screens implemented.
- Actual navigation structure.
- Mapping from screens/ViewModels to domain use cases and repositories.
- Files created, changed, and deleted.
- Dependency changes, with reasons and versions.
- Accessibility and responsive-layout considerations.
- Tests added and exact build/test commands executed.
- Actual test/build results, including failures or skipped tests.
- Any missing capabilities or unresolved product decisions.
- Confirmation that no app blocking, usage measurement, VPN filtering, or other out-of-scope enforcement was implemented.
- A short manual test checklist for running the app on a physical Android device.

Stop after Milestone 3. Do not begin Milestone 4 until the user reviews and approves the result.

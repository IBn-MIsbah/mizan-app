# MIZAN — Milestone 4: App Restriction Enforcement

**Project:** MIZAN (ميزان)  
**Milestone:** 4 — Android app restriction enforcement  
**Prerequisites:** Milestones 0–3 must be implemented, reviewed, and passing  
**Primary outcome:** Integrate Android's accessibility platform with the existing domain rules so MIZAN can detect a selected restricted app entering the foreground and present the appropriate blocking experience.

---

## 1. Instructions to the coding agent

Read these files before changing code:

1. `AGENTS.md` — the complete product requirements and architecture rules.
2. This milestone document.
3. The existing implementation of Milestones 0–3, including tests and project configuration.

Treat `AGENTS.md` as the product source of truth. This milestone narrows the work to Android app restriction enforcement; it does not authorize changes to unrelated product behavior.

### Mandatory preflight

Before implementation:

- Inspect the repository structure, Gradle configuration, manifest, dependency versions, domain models, repository interfaces, use cases, navigation, and existing tests.
- Confirm that Milestones 0–3 are actually present. Do not infer completion from documentation alone.
- Run the existing project's relevant unit tests and build.
- Identify the exact domain API that evaluates whether a package should currently be restricted. Reuse it; do not duplicate restriction rules in Android services.
- Identify how configured restricted and allowed packages are persisted and observed.
- Report a concise implementation plan and any blockers.

**Stop without making changes** if a prerequisite milestone is missing, the project does not build, the domain evaluator cannot be used correctly, or a required product rule is ambiguous. Report the exact issue and ask for direction.

Do not rewrite earlier milestones to make this milestone easier. Do not replace working architecture with a new architecture.

---

## 2. Scope and expected behavior

Implement on-device detection and blocking for apps that the user explicitly selected as restricted.

The enforcement flow must:

1. Observe foreground-window changes through an Android `AccessibilityService`.
2. Identify the foreground package name without collecting the screen's text or content.
3. Obtain the current commitment and relevant rules from the existing application/domain layer.
4. Ask the shared domain restriction evaluator whether the detected package should be blocked at the current time.
5. Respect allowlist precedence, commitment state, restriction mode, schedule windows, and hybrid allowance decisions exactly as defined by the domain layer.
6. Present MIZAN's blocking screen when the evaluator says the package is blocked.
7. Avoid interrupting allowed apps, system-critical flows, or the user when no active restriction applies.
8. Recover safely when the service is restarted or the process is recreated.

The service is an enforcement adapter, not a second place where product policy is implemented.

### Restriction behavior

- **Strict:** a selected restricted app is blocked while the active commitment's strict rule applies.
- **Scheduled:** it is blocked only during configured active schedule windows.
- **Hybrid:** scheduled restrictions and the shared daily allowance follow the domain evaluator's decision.
- **Allowlist:** explicitly allowed packages must never be blocked by MIZAN.
- **No active commitment:** do not block apps.
- **Completed or emergency-terminated commitment:** do not block apps.
- **Unknown package or unavailable rule state:** fail safely; do not block an app based on stale or guessed data.

Do not invent exceptions or duplicate the evaluator's logic.

---

## 3. AccessibilityService implementation

Create a focused Android service, for example `AppRestrictionAccessibilityService`, using the project's existing naming and package conventions.

### Service configuration

- Register the service in `AndroidManifest.xml` with the correct `android.permission.BIND_ACCESSIBILITY_SERVICE` protection and accessibility-service metadata.
- Add only the event types and feedback/capability configuration actually required for foreground package detection.
- Prefer window-state/window-focus events as appropriate for the supported Android versions.
- Do not request the ability to retrieve window content if package identification can be achieved without it.
- Do not inspect, store, log, transmit, or otherwise collect visible text, typed text, screenshots, or other screen content.
- Do not request unrelated accessibility capabilities.
- Keep the service implementation small and delegate decisions to application/domain components.
- Follow the project's dependency-injection pattern if it can be supported safely in the service lifecycle. Avoid unsafe manual service-locator patterns.

The coding agent must verify the exact APIs and manifest metadata against the project's compile SDK and supported Android versions. Do not assume an API or attribute exists without checking.

### Event handling

For each relevant event:

1. Extract the package name from the event/window information using the safest supported API.
2. Ignore null, empty, or malformed package names.
3. Ignore MIZAN's own package to prevent a blocking-screen loop.
4. Debounce duplicate events for the same package where appropriate, without delaying a necessary block.
5. Obtain current rule state from the repository/use-case layer.
6. Evaluate using the existing domain evaluator and a current time value supplied through the project's time abstraction.
7. If blocked, request the blocking UI through a dedicated coordinator.
8. If allowed, do not display or retain a blocking surface.

Do not perform slow database operations on the main thread. Use lifecycle-safe coroutines or the project's established asynchronous pattern. Handle cancellation and service disconnection without leaking work.

### Service lifecycle

Handle at least:

- `onServiceConnected`
- relevant accessibility events
- `onInterrupt`
- service/process recreation
- unavailable dependencies or persistence errors

On disconnect or unavailable state:

- Mark enforcement health as unavailable using an existing state model or a minimal, well-scoped addition.
- Do not claim that protection is active when it is not.
- Do not crash-loop or repeatedly launch UI.
- Re-evaluate current state after reconnection.

Do not promise that Android will always keep the service alive. Do not attempt to bypass Android lifecycle or user controls.

---

## 4. Blocking experience

Implement a dedicated, clear blocking screen consistent with the existing Compose design system.

### Required content

- MIZAN identity.
- A concise explanation that this app is restricted under the user's active commitment.
- A clear indication that the restriction is currently active.
- A way to return to the home screen or leave the blocked app.
- A short, neutral reminder of the user's commitment; no shame, threats, or manipulative language.

### Behavior

- Use a dedicated Activity or another Android-supported presentation mechanism appropriate to the existing architecture.
- Do not assume that drawing over other apps is permitted by default.
- Do not add `SYSTEM_ALERT_WINDOW` permission unless a separate, justified design decision requires it and the user is clearly informed.
- Avoid launching repeated blocking activities for duplicate events.
- Ensure MIZAN's own blocking screen is never itself treated as a restricted app.
- Ensure Back/Home behavior does not cause an infinite launch loop.
- Do not attempt to trap the user, disable system navigation, or prevent access to emergency functions.
- The blocking UI must not offer a bypass that silently changes the commitment or restriction rules.
- If a rule changes or the commitment ends, dismiss/stop enforcement consistently with the current domain state.

The coding agent must test the actual presentation behavior on a physical Android device. If Android version or manufacturer behavior prevents a reliable presentation approach, document the limitation and present a safe alternative rather than claiming it works.

---

## 5. Permission education and service health

The user must explicitly enable the accessibility service through Android Settings. MIZAN cannot grant this permission programmatically.

Implement or connect a permission education/status flow that:

- Explains why the AccessibilityService is needed: detecting when a selected restricted app comes to the foreground.
- Explicitly states that MIZAN does not read screen text, capture screenshots, or upload accessibility data.
- Explains that Android requires the user to enable the service manually in system settings.
- Provides a user-initiated action that opens the appropriate Android Accessibility Settings screen.
- Re-checks actual service-enabled status when the app resumes.
- Shows distinct states such as enabled, disabled, and status unavailable.
- Does not display a false “protected” or “enforcing” status when the service is disabled or disconnected.

Do not repeatedly open Settings or pressure the user to enable the service. Do not request unrelated permissions.

If the existing UI has a permissions/setup area, extend it. Do not create a competing permission system.

---

## 6. Installed-app discovery and selection integration

If Milestone 3 intentionally left installed-app discovery for a platform milestone, implement the minimum integration needed to let users select real installed apps.

Requirements:

- Query launchable user-facing apps using Android's package APIs.
- Use the least amount of package visibility needed for the app-selection feature.
- Follow current Android package-visibility rules for the project's target SDK.
- Do not request broad package visibility (`QUERY_ALL_PACKAGES`) unless the coding agent proves it is necessary, policy-compliant, and explicitly reports why.
- Do not assume every installed package is launchable or suitable for restriction.
- Exclude MIZAN itself and essential/system-critical packages from normal restriction selection where practical.
- Display a human-readable app label and app icon where available.
- Persist stable package names, not labels, as identifiers.
- Handle app uninstallation, package rename/update, missing icons, and stale selections without crashing.
- Preserve the existing allowlist and restricted-app domain rules.
- Never silently add apps to the restricted list.

If app selection already works with real installed packages, do not rebuild it. Verify its integration with enforcement instead.

---

## 7. Architecture and data boundaries

Keep the established architecture:

- **Presentation:** Compose screens, ViewModels, UI state.
- **Domain:** existing commitment state, restriction rules, evaluator, and use cases.
- **Data:** existing repositories and persistence.
- **Platform/enforcement:** AccessibilityService, Android package discovery, blocking UI coordinator, and permission/service status adapter.

Rules:

- Domain code must not depend on Android framework classes, Compose, Room, or AccessibilityService.
- Do not put restriction policy in the service, Activity, or Composable.
- Do not add a second source of truth for commitment or restriction state.
- Do not store sensitive screen content; it must not be collected in the first place.
- Do not log package activity in production logs unless a narrowly scoped, privacy-reviewed diagnostic requirement exists. Never log screen content.
- Keep all enforcement data local.
- Use existing dependency injection, repository, and time abstractions.
- Add only dependencies that are necessary and compatible with the current project.

If a minimal domain or persistence contract change is unavoidable, explain why, keep it backward-compatible, and add tests. Do not broaden the milestone.

---

## 8. Reliability and edge cases

Handle and test:

- No active commitment.
- Draft commitment.
- Active Strict commitment.
- Active Scheduled commitment inside and outside a schedule.
- Active Hybrid commitment with allowance available and exhausted.
- Allowed package foregrounded.
- Restricted package foregrounded.
- MIZAN itself foregrounded.
- Null/unknown package event.
- Duplicate accessibility events.
- Service disabled by the user.
- Service disconnected and re-enabled.
- Process death and service recreation.
- Commitment completed while the service is running.
- Emergency termination while the service is running.
- Schedule crossing midnight, using the existing domain semantics.
- Local time change while active, using the existing time-integrity rules.
- Restricted app uninstalled or unavailable.

If state cannot be loaded or evaluated, do not make a new block decision from stale cached data. Report enforcement as degraded/unavailable and recover when state becomes available.

---

## 9. Testing requirements

### Unit tests

Test the platform-independent coordinator/adapters with fakes:

- Package names are passed to the existing evaluator.
- Allowed decisions never request blocking UI.
- Blocked decisions request the blocking UI once per relevant transition.
- Duplicate events are debounced safely.
- MIZAN's package is ignored.
- Missing rule state does not cause a block.
- Service-health state is updated correctly.

Do not copy domain evaluator tests; rely on Milestone 1's domain test suite and add regression tests only where integration exposes a gap.

### Android/instrumentation tests

Where practical, test:

- Service declaration and metadata.
- Permission status detection.
- Settings navigation intent.
- Blocking Activity launch and finish behavior.
- Repeated event handling.
- Lifecycle/reconnection behavior.

### Physical-device validation

Test on the user's Android phone or another real device, not only an emulator:

1. Install and open MIZAN.
2. Enable the service manually through Android Settings.
3. Confirm MIZAN reports the service as enabled.
4. Create and explicitly activate a test commitment with a harmless test app selected.
5. Open that app and confirm the blocking experience appears only when the domain rules say it should.
6. Open an allowlisted app and confirm it is not blocked.
7. Return to MIZAN and confirm no blocking loop occurs.
8. Disable the accessibility service and confirm MIZAN reports enforcement as unavailable.
9. Re-enable it and confirm recovery.

Do not use essential apps, emergency functions, banking apps, or the user's primary communication apps as test targets.

If physical-device testing is not possible in the agent environment, state that clearly. Do not report unperformed tests as passing.

---

## 10. Explicitly out of scope

Do not implement these in Milestone 4:

- Daily usage measurement or allowance accounting from foreground duration. This belongs to a later milestone.
- `UsageStatsManager` as the primary real-time app detector.
- Website/domain filtering or `VPNService`.
- Remote filter database downloads.
- Cloud sync, accounts, analytics, ads, or telemetry.
- Accountability PIN cryptography or emergency PIN verification unless already implemented in an earlier approved milestone.
- Device Owner mode, device-admin lockdown, uninstall prevention, safe-mode prevention, ADB prevention, or factory-reset protection.
- Guaranteed anti-tampering or guaranteed background execution.
- Blocking all of YouTube as a substitute for Shorts-only filtering.
- New commitment rules or automatic renewal.
- Broad unrelated UI redesign.

For Hybrid allowance decisions, integrate with the existing domain decision contract. Do not invent a usage tracker to make the allowance appear functional. If the allowance cannot yet be evaluated because its usage data provider is not implemented, preserve the domain contract and report the dependency clearly; do not fake usage values.

---

## 11. Android policy, privacy, and user trust

Accessibility access is highly sensitive. Implement only the narrowly necessary foreground package observation.

- Explain the feature accurately before sending the user to Settings.
- Do not disguise the service's purpose.
- Do not read or transmit screen content.
- Do not collect credentials, messages, or typed text.
- Do not use accessibility APIs to automate unrelated user actions.
- Do not prevent users from accessing Android system controls or emergency functionality.
- Review current Google Play Accessibility API policy and distribution requirements if Play Store publication is intended. Do not assume that technical availability implies policy approval.
- Document any distribution restrictions or policy concerns discovered during implementation.

MIZAN provides a voluntary self-discipline aid. It must not claim to make a device tamper-proof or to guarantee that selected apps can never be opened.

---

## 12. Definition of done

Milestone 4 is complete only when:

- [ ] Milestones 0–3 were verified before implementation.
- [ ] The app builds successfully.
- [ ] Existing tests still pass.
- [ ] AccessibilityService is correctly declared and requests only necessary capabilities.
- [ ] Foreground package detection is implemented without reading screen content.
- [ ] Restriction decisions use the existing domain evaluator.
- [ ] The blocking experience is implemented and avoids loops.
- [ ] Allowlist and inactive-commitment behavior are respected.
- [ ] Permission education and actual service-health status are integrated.
- [ ] Installed-app selection is integrated only if it was not already implemented.
- [ ] New unit/instrumentation tests pass.
- [ ] Physical-device test results are reported honestly, including any untested cases.
- [ ] Privacy and Android policy considerations are documented.
- [ ] No out-of-scope features were added.

---

## 13. Required completion report

When finished, provide:

1. **Preflight:** which earlier milestones and tests were verified.
2. **Implementation:** files created/changed and the purpose of each major change.
3. **Enforcement flow:** how an observed package reaches the shared domain evaluator and blocking UI.
4. **Permissions:** exact accessibility capabilities requested and how the user enables/disables the service.
5. **Privacy:** confirmation of what accessibility data is and is not collected.
6. **Tests:** exact build/test commands and their actual results.
7. **Device validation:** steps actually performed on a physical device; clearly identify anything not tested.
8. **Limitations:** Android/OEM lifecycle or policy limitations and any remaining dependencies.
9. **Git:** do not commit or push unless the user explicitly asks.

After reporting, **stop**. Do not begin Milestone 5 or implement usage measurement, allowance accounting, or VPN filtering without a separate instruction.

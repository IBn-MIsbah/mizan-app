# MIZAN — Milestone 5: Usage Measurement and Allowance Accounting

**Project:** MIZAN (ميزان)  
**Milestone:** 5 — Local usage measurement and daily allowance accounting  
**Prerequisites:** Milestones 0–4 must be implemented, reviewed, and passing  
**Primary outcome:** Measure foreground usage of selected restricted apps, persist reliable local usage records, and connect measured usage to the existing Hybrid-mode allowance rules.

---

## 1. Instructions to the coding agent

Read these files before changing code:

1. `AGENTS.md` — product requirements and architecture rules.
2. This milestone document.
3. The implemented Milestones 0–4, including domain models, repositories, services, tests, and project configuration.

Treat `AGENTS.md` as the product source of truth. This milestone is limited to usage measurement, reconciliation, and allowance accounting. It does not authorize unrelated redesign or new product rules.

### Mandatory preflight

Before implementation:

- Inspect the current project structure and Gradle configuration.
- Verify that Milestones 0–4 are implemented; do not rely only on milestone documents or agent claims.
- Run the existing build and relevant tests.
- Inspect the existing `DailyUsage` model, allowance evaluator, time abstractions, repository contracts, Room schema, and migration strategy.
- Inspect the Milestone 4 foreground-app enforcement flow and identify exactly where usage measurement should integrate.
- Confirm whether the app currently has the required Usage Access education/status flow.
- Report a concise implementation plan before editing.

**Stop without making changes** if any prerequisite milestone is missing, the project does not build, usage records cannot be associated with the correct commitment/local day, or the existing domain rules are ambiguous. Report the exact blocker and ask for direction.

Do not rewrite earlier milestones to make this milestone easier. Preserve existing architecture and behavior.

---

## 2. Scope and expected behavior

Implement accurate, privacy-preserving measurement of time spent in the foreground in selected restricted apps.

The usage system must:

1. Measure foreground duration only for packages selected as restricted by the active commitment.
2. Count usage only while a selected app is actually in the foreground.
3. Maintain one shared daily allowance across all selected restricted apps in Hybrid mode.
4. Use the existing domain allowance rules and evaluator.
5. Persist usage locally and recover from process death or service interruption as safely as possible.
6. Reset the allowance according to the existing local-calendar-day rule, at local midnight.
7. Reconcile measured usage with Android's usage history where permission and API behavior allow.
8. Avoid double-counting time when event-based and historical data overlap.
9. Clearly distinguish measured usage, estimated/reconciled usage, and unavailable data where the UI needs to communicate uncertainty.

Do not measure or report time spent inside unrelated apps. Do not collect screen content, messages, URLs, keystrokes, screenshots, or user activity beyond the minimum package-and-time information required.

### Mode behavior

- **Strict:** app blocking follows the existing strict rule. Usage may be recorded for selected apps if the product's existing model requires it, but usage must not create a bypass or allowance.
- **Scheduled:** app blocking follows the existing schedule rule. Usage measurement must not alter schedule behavior.
- **Hybrid:** the shared daily allowance is consumed by foreground time across the selected restricted apps, subject to the existing domain rules.
- **Allowlisted packages:** do not consume restricted-app allowance.
- **No active commitment:** do not accrue commitment usage.
- **Completed or emergency-terminated commitment:** stop accruing usage for that commitment.

Do not introduce separate allowances per app. The allowance is shared across the selected restricted apps.

---

## 3. Usage measurement architecture

Use the existing architecture and keep platform APIs outside the domain layer.

### Platform measurement adapter

Implement a focused platform component, for example `ForegroundUsageTracker`, responsible for observing foreground transitions and producing package/time usage intervals.

Prefer the Milestone 4 AccessibilityService event stream as the immediate foreground-transition signal if that implementation provides reliable lifecycle integration. Keep the tracker independent from the service where practical so it can be tested.

The tracker should:

- Record the timestamp when a selected restricted package enters the foreground.
- Close the previous foreground interval when another package becomes foreground.
- Close the active interval when the screen turns off, the device locks, the service disconnects, or another reliable lifecycle signal indicates foreground use has ended.
- Ignore MIZAN itself, allowlisted packages, unknown packages, and packages outside the active commitment's selected restricted set.
- Avoid starting duplicate sessions for repeated events from the same package.
- Handle rapid transitions without overlapping intervals.
- Avoid counting time while the device is locked or the target app is not foregrounded.
- Use monotonic elapsed time for interval duration while the same boot session is active.
- Persist enough state to recover or conservatively reconcile an unfinished interval after process death.

Do not assume an accessibility event alone proves that an app remained visible for the entire time between two events. Define and document the exact start/end signals and uncertainty boundaries.

### Main-thread and lifecycle constraints

- Do not perform database or reconciliation work synchronously on the main thread.
- Use lifecycle-safe coroutines and the existing dependency-injection conventions.
- Ensure service disconnection and coroutine cancellation do not leak active tracking jobs.
- Do not create a permanent high-frequency polling loop.
- Do not keep the CPU awake continuously just to measure usage.
- Do not claim continuous measurement is guaranteed when Android/OEM lifecycle behavior can interrupt the service.

---

## 4. Time integrity and interval accounting

Time measurement must follow the time-integrity rules in `AGENTS.md`.

### Duration measurement

- Use monotonic elapsed time for measuring an interval during the same boot session.
- Use wall-clock/local time for calendar-day attribution and local-midnight allowance resets.
- Persist the boot marker or equivalent information needed to identify whether monotonic timestamps belong to the same boot.
- Do not calculate elapsed duration by subtracting wall-clock timestamps when a monotonic measurement is available.
- Do not accept negative or implausibly large intervals.
- Define a conservative policy for incomplete intervals, reboot, service interruption, and clock changes.
- Never fabricate missing usage to make a dashboard or allowance appear complete.

### Midnight boundary

If a foreground interval crosses local midnight:

1. Split it at the local calendar-day boundary using the project's time abstraction.
2. Attribute the pre-midnight portion to the previous local day.
3. Attribute the post-midnight portion to the new local day only when the tracker can establish that the app remained foregrounded.
4. Apply the configured allowance reset exactly once for the new local day.
5. Avoid duplicate reset or duplicate interval records after process recreation.

Use the existing timezone and date semantics. Handle daylight-saving/timezone changes as defined by the current time model; if the existing model does not specify them, report the ambiguity rather than inventing a new policy.

---

## 5. UsageStatsManager reconciliation

Use `UsageStatsManager` only as a supplementary reconciliation source, not as the sole real-time enforcement mechanism.

### Permission education

The user must explicitly grant Usage Access through Android Settings. MIZAN cannot grant this access programmatically.

Implement or extend the existing permission education/status flow to:

- Explain why Usage Access is requested: to reconcile app foreground usage and improve daily usage totals.
- State what information is accessed: app package identity and foreground/background event timestamps where Android provides them.
- State that data stays on the device and is not uploaded.
- Provide a user-initiated action that opens the appropriate Usage Access Settings screen.
- Re-check actual access status when MIZAN resumes.
- Show enabled, disabled, and unavailable states distinctly.
- Avoid claiming usage totals are complete when permission is disabled.

Do not request unrelated permissions or repeatedly open Settings.

### Reconciliation rules

- Query only the time range required for the current or recoverable commitment usage.
- Filter events to the selected restricted package set.
- Exclude allowlisted packages and MIZAN.
- Interpret event sequences defensively; missing or duplicated events must not produce negative or overlapping usage.
- Reconcile only intervals that can be supported by the available event data.
- Use a stable source identifier or deterministic deduplication strategy so repeated reconciliation does not add the same usage twice.
- Never add the same time twice when accessibility tracking and UsageStats history overlap.
- Do not overwrite higher-confidence persisted measurements with lower-confidence estimates.
- Do not infer that an app was foregrounded merely because it was installed or launched at some earlier time.
- Respect Android's available history window and permission behavior. Document when the OS does not provide enough historical detail.

If reconciliation cannot establish a reliable interval, preserve the existing measurement and mark the relevant coverage as incomplete rather than guessing.

---

## 6. Domain allowance integration

The domain layer remains the source of truth for allowance decisions.

- Reuse the existing allowance value object and restriction evaluator.
- Reuse the existing shared daily allowance configuration.
- Keep the configured allowance locked during an active commitment.
- Aggregate foreground usage across all selected restricted apps for the current local day.
- Ensure one app's usage reduces the same shared remaining allowance available to every other selected restricted app.
- Ensure Strict restrictions override allowance availability as defined in `AGENTS.md`.
- Ensure Scheduled restrictions and Hybrid allowance behavior compose exactly as already specified.
- Do not place allowance arithmetic in Compose, Activities, AccessibilityService, or Room DAOs.
- If domain changes are required, make them small, pure, and covered by unit tests.

### Exhausted allowance

When the shared Hybrid allowance is exhausted:

- The existing evaluator must return the appropriate blocked decision for selected restricted apps.
- Milestone 4's enforcement coordinator must consume that decision without duplicating policy.
- Do not create a second blocking mechanism or a special UI-only rule.
- Ensure the next foreground event after exhaustion is evaluated against the updated persisted usage.
- Define how an active foreground session is closed at the exhaustion boundary so the system does not continue consuming allowance indefinitely or overcount.

Do not invent grace periods, bonus time, per-app quotas, or manual allowance resets.

---

## 7. Persistence and data integrity

Use the existing Room database and repository abstractions. Extend the schema only where required.

Persist enough information to support:

- Commitment association.
- Restricted package identity.
- Local calendar date and timezone context as required by the existing model.
- Interval start/end or accumulated duration.
- Measurement source (for example, accessibility event tracking or UsageStats reconciliation).
- Confidence/coverage status if needed to distinguish measured from estimated or incomplete records.
- Stable deduplication identity for imported/reconciled events.
- Boot/session marker where needed for monotonic-time recovery.

Requirements:

- Keep usage data local.
- Use transactions for interval closure, daily aggregation, and allowance state changes where necessary.
- Prevent duplicate interval insertion and duplicate reconciliation.
- Add appropriate indexes and foreign keys.
- Preserve Room schema exports.
- Add explicit database migrations; never use destructive migration in normal development or production.
- Handle missing/deleted commitments and stale package records safely.
- Do not store screen text, URLs, keystrokes, screenshots, or unrelated app activity.
- Avoid storing redundant derived values unless needed for correctness or performance; explain any cached aggregate and how it is rebuilt.

Do not add a separate database or a second usage source of truth.

---

## 8. Dashboard and usage presentation

Extend the existing dashboard or insights UI only where the current design supports it.

Display, when available:

- Today's measured foreground usage.
- Shared daily allowance.
- Remaining allowance or exhausted state.
- A clear indication that allowance is shared across selected apps.
- A data availability/coverage indicator if Usage Access is disabled or reconciliation is incomplete.

UI requirements:

- Use real persisted values only.
- Never show fabricated usage, fake charts, or placeholder remaining time as if it were real.
- Distinguish zero usage from unavailable usage.
- Avoid implying that historical totals are complete when permission or service availability prevented measurement.
- Keep the interface minimal and consistent with MIZAN's no-shame product philosophy.
- Do not add rankings, streak pressure, shame language, or attention-grabbing engagement mechanics.

If Milestone 3's dashboard has no appropriate usage surface, add only the smallest justified component.

---

## 9. Recovery and edge cases

Handle and test:

- Repeated foreground events for the same package.
- Rapid switching between two selected restricted apps.
- Switching from a selected app to an allowlisted app.
- Switching to MIZAN.
- Screen off/device lock during an active interval.
- Accessibility service disabled while an interval is active.
- Process death during an active interval.
- Service reconnection.
- Device reboot.
- Wall-clock moved forward or backward.
- Local date/timezone boundary changes.
- Interval crossing local midnight.
- Commitment completed or emergency-terminated during an interval.
- Restricted package removed or uninstalled.
- Usage Access revoked.
- UsageStats history unavailable or incomplete.
- Reconciliation executed more than once.
- Duplicate events from different measurement sources.
- Shared allowance exhausted while a selected app is foregrounded.
- Database write failure during interval closure.

Recovery must be conservative. Never invent elapsed time across a reboot or a period where foreground state cannot be established. Mark coverage incomplete when necessary.

---

## 10. Testing requirements

### Domain unit tests

Test allowance and aggregation behavior:

- Usage from multiple selected apps is aggregated into one shared allowance.
- Allowlisted and unrelated packages do not consume allowance.
- Strict rules override allowance as specified.
- Usage is attributed to the correct local calendar day.
- Midnight splitting is correct.
- Negative and duplicate intervals are rejected or deduplicated.
- Allowance exhaustion changes the evaluator's decision at the correct boundary.
- Active commitment rules cannot be modified through usage accounting.

### Tracker unit tests

Use fake clocks, fake repositories, and fake event sources to test:

- Foreground interval start and closure.
- Duplicate event handling.
- App-to-app transitions without overlap.
- Screen lock and service interruption.
- MIZAN/allowlist/unselected package exclusion.
- Process recovery policy.
- Reboot behavior.
- Midnight split.
- Commitment completion and emergency termination.
- Database failure behavior.

### Reconciliation tests

Test:

- Usage Access unavailable.
- Empty history.
- Missing event pairs.
- Duplicate events.
- Overlapping event intervals.
- Repeated reconciliation.
- Accessibility/UsageStats overlap deduplication.
- Historical data older than Android's available window.
- No reconciliation data replacing higher-confidence records.

### Android/instrumentation tests

Where practical, test:

- Usage Access status detection.
- Opening Usage Access Settings.
- Re-checking permission after app resume.
- UsageStats query behavior on supported API levels.
- Room migrations and transactional interval persistence.

### Physical-device validation

Use a harmless test app on a physical Android device:

1. Install and open MIZAN.
2. Grant Usage Access manually through Android Settings.
3. Confirm MIZAN reports permission status correctly.
4. Activate a test Hybrid commitment with a small, clearly understood allowance.
5. Open a selected test app and keep it in the foreground for a measured interval.
6. Switch to an allowlisted app and confirm it does not consume the allowance.
7. Switch between two selected apps and confirm their usage is aggregated.
8. Confirm the remaining shared allowance decreases once, without double-counting.
9. Test service interruption and recovery.
10. Confirm usage data remains local and no unrelated app usage is collected.

Do not test with essential, emergency, banking, or primary communication apps. If physical-device testing cannot be performed, state this explicitly and do not claim it passed.

---

## 11. Explicitly out of scope

Do not implement:

- Website/domain filtering or `VPNService`.
- Remote filter database downloads or signature verification.
- Cloud sync, user accounts, analytics, ads, or telemetry.
- Device Owner mode or uninstall prevention.
- Guaranteed anti-tampering or guaranteed background execution.
- New commitment modes or automatic renewal.
- Per-app allowances or manual allowance resets.
- Screen-content capture, screenshots, OCR, keystroke logging, or URL history collection.
- High-frequency polling or a permanent foreground service solely to keep tracking alive.
- Unrelated dashboard redesign.
- New behavior for discipline mode.

Do not use `UsageStatsManager` to claim guaranteed real-time detection or guaranteed complete history.

---

## 12. Privacy and Android platform constraints

- Usage Access is sensitive and must be explained accurately.
- Request only the access needed for the stated feature.
- Keep usage records on-device.
- Do not transmit usage data to any server.
- Do not collect unrelated app activity.
- Do not expose package usage in production logs or crash reports.
- Respect the user's ability to revoke Usage Access and Accessibility access.
- Review current Android API behavior and Google Play policy if publication is intended.
- Document OS/OEM limitations, history availability, and measurement uncertainty.

MIZAN is a voluntary self-discipline aid. Do not claim that usage tracking is tamper-proof, perfectly accurate, or guaranteed to continue through all Android lifecycle conditions.

---

## 13. Definition of done

Milestone 5 is complete only when:

- [ ] Milestones 0–4 were verified before implementation.
- [ ] Existing build and tests pass.
- [ ] Foreground usage intervals are measured through the established platform integration.
- [ ] Only selected restricted apps in an active commitment are measured.
- [ ] Allowlisted and unrelated apps do not consume allowance.
- [ ] Hybrid allowance is shared across selected apps.
- [ ] Allowance decisions use the existing domain rules.
- [ ] Monotonic duration and local-calendar attribution follow the time-integrity model.
- [ ] Midnight, process death, service interruption, and reboot are handled conservatively.
- [ ] UsageStats reconciliation is optional, permission-gated, bounded, and deduplicated.
- [ ] Room changes use explicit migrations and preserve existing data.
- [ ] UI distinguishes measured, incomplete, zero, and unavailable usage.
- [ ] New tests pass.
- [ ] Physical-device validation is reported honestly.
- [ ] Privacy and platform limitations are documented.
- [ ] No out-of-scope features were added.

---

## 14. Required completion report

When finished, provide:

1. **Preflight:** which earlier milestones, builds, and tests were verified.
2. **Measurement design:** exact signals used to start and end foreground intervals.
3. **Time handling:** monotonic timing, local-day attribution, midnight, reboot, and clock-change policy.
4. **Allowance integration:** how aggregated usage reaches the existing domain evaluator.
5. **Reconciliation:** UsageStats permission, queried range, deduplication strategy, and limitations.
6. **Persistence:** schema changes, migrations, and recovery behavior.
7. **Privacy:** exact usage data collected and confirmation that it remains local.
8. **Tests:** exact commands and actual results.
9. **Device validation:** steps actually performed and anything not tested.
10. **Limitations:** known Android/OEM or measurement uncertainty.
11. **Git:** do not commit or push unless the user explicitly asks.

After reporting, **stop**. Do not begin Milestone 6 or implement VPN/domain filtering without a separate instruction.

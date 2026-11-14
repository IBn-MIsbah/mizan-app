# MIZAN — Milestone 7: Security, Recovery, Testing, and Final Hardening

**Project:** MIZAN (ميزان)  
**Milestone:** 7 — Final integration, security review, recovery validation, and release readiness  
**Prerequisites:** Milestones 0–6 must be implemented, reviewed, and passing.  
**Primary outcome:** Validate MIZAN as one integrated product, fix critical defects, strengthen privacy and recovery behavior, and produce an evidence-based release-readiness report.

---

## 1. Instructions to the coding agent

Read `AGENTS.md`, this document, and the actual implementation of Milestones 0–6 before changing code. `AGENTS.md` is the product source of truth.

This is an integration and hardening milestone. It is not permission to add features or redesign working systems.

### Mandatory preflight

- Inspect the complete repository, Gradle configuration, manifest, dependencies, and build variants.
- Verify Milestones 0–6 in code and tests; do not rely only on documents or prior agent claims.
- Run the full existing unit-test suite, available instrumentation/UI tests, and intended release build.
- Inspect all Room schemas and migrations.
- Review commitment state transitions and trace app restriction, usage accounting, and DNS filtering end to end.
- Review permissions, services, receivers, exported components, and foreground-service declarations.
- Identify TODOs, placeholders, disabled tests, and unverified claims.
- Report an audit summary and proposed plan before editing.

**Stop and report blockers** if a prerequisite is missing, the project cannot build, a product rule is ambiguous, or a change requires an unapproved redesign. Never hide failures by deleting tests, weakening assertions, or marking tests ignored.

Do not rewrite completed milestones wholesale.

## 2. Product-wide acceptance review

Validate the complete product against `AGENTS.md`. Mark every requirement **Pass**, **Fail**, or **Not verified**.

### Commitment lifecycle

Verify that:

- A commitment begins only after configuration review and explicit user agreement.
- Draft commitments do not enforce restrictions.
- Active commitments use the persisted rules the user agreed to.
- Seven-day duration and end conditions are consistent.
- No automatic renewal occurs.
- Completion requires an explicit choice among the approved options.
- Emergency termination follows the warning, confirmation, and optional accountability-PIN rules.
- Completed and emergency-terminated commitments stop commitment-specific enforcement.
- Process recreation cannot accidentally activate, extend, renew, or reactivate a commitment.

### Restriction modes

Verify:

- Strict restrictions apply for their full configured commitment period.
- Scheduled restrictions follow selected days and local-time windows, including overnight windows.
- Hybrid combines schedules with one shared daily allowance.
- Strict blocking takes precedence over allowance as specified.
- Allowlisted apps remain accessible.
- Inactive, completed, and emergency-terminated commitments do not enforce restrictions.
- Active commitment rules cannot be silently modified.
- No per-app allowance or undocumented grace period exists.

### Privacy and product philosophy

Verify:

- No account or cloud service is required.
- No ads, external analytics, or telemetry were added.
- Commitment, app-selection, usage, and domain data remain local.
- No browsing history, DNS query history, screen text, screenshots, keystrokes, or unrelated app activity is collected.
- No shame-based or coercive language was introduced.
- Permission explanations accurately describe each permission.
- Users can distinguish active, unavailable, and degraded protection states.

## 3. Security review

Review the actual implementation, not just its documentation.

### Local data and secrets

- Confirm sensitive cryptographic material uses Android Keystore where appropriate.
- If accountability PINs exist, verify they are never stored or logged in plaintext.
- Verify a suitable salted password-hashing/KDF design; do not invent custom cryptography.
- Ensure PINs, hashes, tokens, and sensitive commitment data never appear in logs or crash output.
- Inspect Android backup rules and exported data for unintended exposure.
- Inspect Room and DataStore contents for secrets and unnecessary sensitive data.
- Do not add encryption layers without a clear threat model and migration plan.

If PIN functionality was not implemented in an earlier approved milestone, do not rush an unreviewed implementation into this milestone. Report the gap and its release impact.

### Android components and permissions

Review `AndroidManifest.xml`, exported Activities/Services/Receivers/providers, intent filters, deep links, AccessibilityService capabilities, VPNService, Usage Access, runtime permissions, foreground-service declarations, notification behavior, PendingIntent mutability, and debug-only components.

Apply least privilege. Remove unused permissions only when safe; do not remove permissions required by implemented features.

### Input validation

Validate custom domains, package names, persisted enum values, imported datasets, DNS packets, time intervals, and migration inputs. Reject malformed input safely. Avoid crashes, unbounded allocations, unsafe parsing, and silent acceptance of invalid security-sensitive data.

### Logging

- Remove sensitive debug logs from release builds.
- Never log screen content, DNS query names, PINs, or detailed app-usage histories.
- Keep diagnostics minimal and useful.
- Ensure errors do not expose secrets.
- Verify no third-party crash reporting or logging was silently introduced.

## 4. Commitment and time-integrity recovery

Review the time-integrity model in `AGENTS.md`.

Confirm that the implementation persists and reconciles the relevant values, such as commitment start/end, last wall-clock observation, monotonic timestamp, boot marker, last allowance reset, accumulated usage, and last reconciliation time. Use existing models; do not add redundant fields without demonstrated need.

Test:

- Process death and recreation.
- Device reboot during an active commitment.
- Reboot during an open usage interval.
- Wall-clock moved forward and backward.
- Local timezone change and daylight-saving boundary where applicable.
- Database unavailable or migration failure.
- Accessibility service interruption.
- VPN permission revoked or another VPN activated.
- Usage Access revoked.
- Delayed or repeated WorkManager tasks.

Use monotonic time for durations within a boot session and wall-clock/local time for schedules and calendar resets, consistent with the existing domain model. Never fabricate elapsed time across reboot or an unobserved period. Do not let clock manipulation silently extend a commitment or grant extra allowance. Apply the documented conservative policy and disclose offline time-trust limitations.

## 5. Enforcement integration and failure behavior

Validate the complete interaction of the enforcement components.

### App restriction

- AccessibilityService delegates decisions to the domain evaluator.
- No policy is duplicated in services or UI.
- Allowlisted packages and MIZAN itself are not blocked.
- Blocking UI does not create a launch loop.
- Disabled/disconnected service state is visible.
- Stale or unavailable rule state does not create an unjustified block.
- Completion and emergency termination stop app blocking.
- Android lifecycle interruptions are reported honestly.

### Usage and allowance

- Only selected restricted apps in an active commitment are measured.
- Allowlisted and unrelated apps do not consume allowance.
- Usage across selected apps is aggregated into one shared daily allowance.
- Accessibility and UsageStats overlap is not double-counted.
- Incomplete/estimated data is not presented as exact.
- Midnight attribution and reset occur once.
- Tracking failure does not fabricate usage or silently grant extra time.
- Allowance exhaustion reaches the shared domain evaluator and existing blocking coordinator.

### DNS filtering

- VPN authorization and service state are accurate.
- VPN failure does not disable app restriction.
- Completion and emergency termination stop commitment-specific filtering.
- Domain matching uses label boundaries and rejects suffix lookalikes.
- DNS processing is bounded and handles malformed packets.
- Allowed DNS does not create routing loops or unintended traffic blackholing.
- DNS query history is not retained or transmitted.
- Dataset integrity, version, and rollback rules work as implemented.
- UI does not claim comprehensive website protection.

### Cross-feature failure

Test each feature independently and together. Failure of AccessibilityService, Usage Access, or VPNService must not crash the app or falsely report all protection active. Show separate, truthful status for each mechanism.

## 6. Database and migration hardening

- Verify every supported Room schema transition has an explicit migration.
- Test migrations from each supported prior schema, not only the latest development database.
- Verify foreign keys, indexes, unique constraints, and transactions.
- Confirm migrations preserve commitments, rules, app selections, usage, domain configuration, and security settings.
- Test empty, partially populated, and representative existing databases.
- Handle unknown enum values and missing optional fields safely.
- Test interrupted writes and inconsistent-state recovery.
- Ensure destructive migration is not used in production paths.
- Confirm schema exports match the current schema.
- Verify repository flows emit consistent state after writes and migrations.

Never reset the user's database to make migration tests pass. Stop and report unsafe migrations or data-loss risk.

## 7. Background work and lifecycle review

- Use WorkManager only for deferrable work such as dataset updates and reconciliation, not real-time enforcement.
- Make workers idempotent and use unique work where duplicate execution is harmful.
- Apply bounded retries/backoff and relevant network constraints.
- Avoid battery-intensive polling and generic keep-alive services.
- Ensure services release resources, sockets, file descriptors, and coroutines.
- Ensure reboot recovery respects Android restrictions and prior consent.
- Do not silently restart a feature the user explicitly stopped.
- Document work Android or OEM behavior may delay or terminate.

## 8. User experience and accessibility review

Perform focused quality review without redesigning the product.

Verify:

- Loading, empty, success, and error states exist where applicable.
- Permission-denied/revoked states explain what is unavailable.
- Destructive actions require clear confirmation.
- Emergency termination is understandable and accessible.
- Commitment status and remaining time use correct time semantics.
- Usage distinguishes zero, incomplete, and unavailable data.
- Domain status reflects actual VPN state.
- App restriction status reflects actual AccessibilityService state.
- No fake metrics, placeholder statistics, or fabricated success states remain.
- Light/dark themes are readable.
- Text scales without clipping.
- Touch targets and screen-reader labels are usable.
- Navigation does not silently alter a draft commitment.
- UI does not imply guarantees Android cannot provide.

Do not add streak pressure, rankings, engagement mechanics, or unrelated features.

## 9. Automated test strategy

Run and extend:

### Domain tests
Commitment transitions, explicit activation, completion choices, emergency authorization, Strict/Scheduled/Hybrid evaluation, allowlist precedence, overnight schedules, shared allowance, exhaustion, midnight resets, clock/reboot policy, and domain matching.

### Data tests
DAO/repository behavior, transactions, deduplication, DataStore, every supported Room migration, corrupt/unknown persisted values, dataset import, verification, atomic activation, and rollback.

### Platform/integration tests
Accessibility status and coordinator, blocking UI loop prevention, Usage Access and reconciliation, VPN permission and DNS processing, service lifecycle, commitment termination, and cross-feature failure isolation.

### UI tests
Test the primary user journeys:

1. First launch and privacy education.
2. Create and review a commitment.
3. Explicitly agree and activate.
4. Enable required permissions through user-initiated system settings.
5. View accurate commitment and protection status.
6. Encounter a blocked selected app.
7. View actual usage and allowance.
8. Configure and validate domain filtering where enabled.
9. Complete a commitment and choose the next action.
10. Emergency termination with the configured authorization flow.

Do not delete or weaken tests to obtain a green build. Record tests that cannot run.

## 10. Physical-device validation

Perform final validation on a real Android device, ideally the user's Tecno Camon 50, using a clean install and an upgrade/migration scenario where practical.

### Clean install

- Install the release candidate.
- Complete onboarding and review privacy explanations.
- Create a test commitment with harmless test apps/domains.
- Confirm explicit agreement is required.
- Enable AccessibilityService and Usage Access through Android Settings.
- Grant VPN authorization only if domain filtering is configured.
- Verify each feature's state independently.
- Test app restriction, usage accounting, allowance exhaustion, and DNS filtering.
- Verify allowlisted apps and unrelated domains remain accessible.
- Complete the commitment and verify enforcement stops.

### Recovery and upgrade

- Upgrade an installation containing local data.
- Verify migration without data loss.
- Reboot during a test commitment.
- Reopen MIZAN and inspect commitment, usage, and service states.
- Revoke each special access and verify degraded status.
- Restore access and verify safe recovery.
- Test another active VPN and verify conflict handling.
- Verify user-stopped services are not secretly restarted.

Do not use essential emergency, banking, or primary communication apps as restriction targets. Mark unperformed scenarios **Not verified**; never claim they passed.

## 11. Release build and dependency review

- Build the intended release variant.
- Verify application ID, version code/name, min/target SDK, and signing configuration.
- Exclude debug-only tools, test endpoints, and verbose logs from release.
- Review dependency versions and available vulnerability reports.
- Do not upgrade major dependencies merely for freshness during final hardening.
- Verify core flows do not require network access.
- Confirm offline-first behavior for commitments, app restriction, usage, and custom domains.
- Check APK/AAB generation and installation.
- Do not publish, upload, or distribute without explicit instruction.

If signing credentials are missing, do not generate fake credentials or claim a signed release is ready.

## 12. Explicitly out of scope

Do not add cloud accounts, sync, analytics, ads, telemetry, new restriction modes, automatic renewal, Device Owner mode, uninstall/safe-mode/ADB/factory-reset prevention, guaranteed anti-tampering, social/gamification features, remote VPN, HTTPS interception, unrelated redesign, Play Store publication, or broad refactoring unrelated to a verified defect.

Fix defects required by acceptance criteria. Report unrelated improvements as recommendations.

## 13. Definition of done

- [ ] Milestones 0–6 audited in the actual codebase.
- [ ] Product-wide requirements marked Pass, Fail, or Not verified.
- [ ] No known critical/high-severity security issue remains unresolved.
- [ ] Commitment lifecycle and restriction modes pass regression tests.
- [ ] App enforcement, usage accounting, and DNS filtering work independently and together.
- [ ] Failure states are truthful.
- [ ] Time integrity and recovery are tested and documented.
- [ ] Room migrations preserve existing data.
- [ ] Sensitive data is not logged or transmitted.
- [ ] Permissions and exported components follow least privilege.
- [ ] Automated tests pass where runnable.
- [ ] Release build succeeds.
- [ ] Physical-device results are reported honestly.
- [ ] Known limitations and unresolved issues are documented.
- [ ] No out-of-scope features were added.

Compilation alone does not establish completion. Unverified behavior must remain marked unverified.

## 14. Required final report

Provide:

1. Audit summary for Milestones 0–6.
2. Acceptance matrix: Pass / Fail / Not verified.
3. Security review: permissions, exported components, secrets, logs, backups, and validation.
4. Commitment integrity: transitions, time handling, emergency termination, and completion.
5. Enforcement results: app restriction, usage measurement, and DNS filtering.
6. Recovery results: reboot, process death, permission revocation, clock changes, and migrations.
7. Exact test commands, counts, failures, skipped tests, and build variants.
8. Physical-device scenarios performed and anything not verified.
9. Release artifact and signing status.
10. Known limitations: Android/OEM behavior, DNS bypasses, offline time limitations, and policy concerns.
11. Remaining defects with severity, impact, and recommended next action.
12. Git status and changed files. Do not commit or push unless explicitly asked.

End with one verdict:

- **Ready for controlled personal testing**
- **Not ready — blockers remain**

Do not claim “production ready” or “fully secure” without evidence. Do not equate a successful build with product readiness.

After reporting, stop. Do not publish, distribute, commit, push, or start new work without explicit instruction.

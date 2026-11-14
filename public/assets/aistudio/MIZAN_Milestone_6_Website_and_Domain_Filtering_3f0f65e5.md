# MIZAN — Milestone 6: Website and Domain Filtering

**Project:** MIZAN (ميزان)  
**Milestone:** 6 — Local DNS-based website/domain filtering with Android `VpnService`  
**Prerequisites:** Milestones 0–5 must be implemented, reviewed, and passing.

## 1. Instructions to the coding agent

Read `AGENTS.md`, this document, and the actual implementation of Milestones 0–5 before changing code. `AGENTS.md` remains the product source of truth.

### Mandatory preflight

- Inspect repository structure, Gradle configuration, manifest, compile/target SDK, dependencies, domain models, repositories, Room schema/migrations, and existing enforcement/permission flows.
- Verify Milestones 0–5 in code and tests; do not rely on milestone documents or agent claims.
- Run the existing build and relevant tests.
- Inspect the existing blocked-domain model, commitment lifecycle, and filter metadata contracts.
- Report a concise implementation plan and any Android/platform limitations before editing.

**Stop without editing** if a prerequisite is missing, the project does not build, domain-filter behavior is ambiguous, or a safe VPN architecture cannot be established. Do not rewrite earlier milestones to make this one easier.

## 2. Scope and expected behavior

Implement optional, on-device DNS filtering for domains configured by the user under an active MIZAN commitment.

The feature must:

1. Use Android `VpnService` to create a local VPN interface.
2. Process DNS queries locally and apply the existing blocked-domain rules.
3. Avoid routing ordinary user traffic through a remote MIZAN VPN server.
4. Support custom domains and existing domain categories where their data source is trustworthy.
5. Respect commitment activation, completion, emergency termination, and user consent.
6. Report actual permission and service health accurately.
7. Preserve existing app blocking and usage tracking independently of VPN status.

DNS filtering is domain-level filtering, not URL or page filtering. It cannot reliably distinguish paths on the same HTTPS domain, inspect page content, or guarantee filtering when apps use encrypted DNS, hard-coded IP addresses, proxies, or other bypasses. Do not claim otherwise.

Do not block all of YouTube as a substitute for Shorts-only filtering.

## 3. Android VPNService implementation

Implement a focused service, e.g. `DomainFilterVpnService`, following existing package and dependency-injection conventions.

- Declare the service with the correct `android.permission.BIND_VPN_SERVICE` protection and VPN intent filter.
- Use `VpnService.prepare()` and the system authorization flow. Never assume authorization.
- Handle denied/revoked permission, `onRevoke`, service stop, process recreation, network changes, and another VPN taking over.
- Respect Android's one-active-VPN behavior. Never bypass it.
- Verify all APIs and manifest attributes against the actual compile/target SDK.
- Request no unrelated permissions.
- Protect upstream sockets with the Android VPN API where applicable to avoid routing loops.
- If a foreground-service notification is required by the selected Android version/design, implement the correct service type and accurate notification. Do not add a keep-alive service without a technical reason.

### Critical routing requirement

This is a local DNS-filtering VPN, not a general VPN tunnel.

Document the exact TUN interface addresses, routes, DNS settings, packet handling, and upstream resolver behavior. Do not route all device traffic into an incomplete packet processor. Do not silently blackhole non-DNS traffic. If the chosen design requires forwarding all IP packets, implement and test correct forwarding or stop and report why it cannot safely be done.

Do not send user traffic to a remote MIZAN server. Do not introduce a third-party resolver without documenting its privacy implications and obtaining an explicit product decision.

## 4. DNS processing component

Build a small, independently testable DNS processor outside Android service lifecycle code.

For each query:

1. Validate packet length and DNS structure.
2. Parse the query name and record type safely.
3. Normalize the domain.
4. Apply the existing domain matching/rule evaluator.
5. Construct a valid blocking response for blocked domains.
6. Forward allowed queries only through the approved upstream design.
7. Handle malformed, unsupported, truncated, and oversized packets safely.

At minimum, support A and AAAA queries. Handle DNS compression pointers safely, including pointer loops and out-of-bounds references. Preserve transaction IDs and relevant flags. Consider EDNS0 and truncated-response behavior. If TCP DNS is unsupported, document that limitation and do not claim complete filtering.

Use a maintained DNS parsing library if it is compatible and justified. Otherwise, keep a custom parser bounded and extensively tested. Do not build an unbounded or permissive parser.

Do not log DNS query names in production or store browsing history.

## 5. Domain normalization and matching

Implement matching as a pure domain component, with no Android dependencies.

Test and define:

- Case-insensitive comparison.
- Trailing-dot normalization.
- Internationalized domain names/IDN handling where supported.
- Invalid labels, empty names, and malformed input.
- Exact domain and subdomain matching.
- Label-boundary matching rather than unsafe substring matching.

A rule for `example.com` must match `example.com` and `sub.example.com`, but must not match `notexample.com` or `example.com.attacker.test`.

Follow existing rule precedence. At minimum:

- Inactive, completed, or emergency-terminated commitments do not apply commitment-specific filtering.
- Explicit user configuration is respected.
- If the established domain model has allow rules, their precedence must be explicit and tested.
- Duplicate rules must not create inconsistent outcomes.
- Reject invalid domains at input/validation time.

Do not invent a new exception system or change active commitment rules outside the existing product contract.

## 6. Domain datasets and updates

Do not invent a domain list or claim any dataset is comprehensive.

For every built-in dataset, document its source, license, update frequency, and known coverage limitations. Keep the provider-neutral architecture specified in `AGENTS.md`.

Persist locally:

- Dataset version and source.
- Update timestamp and content hash.
- Signature/verification metadata where applicable.
- Current update/health status.

If remote updates are implemented:

- Use a documented signing format and pinned public verification key.
- Verify authenticity and integrity before import.
- Do not rely on TLS alone as dataset authenticity.
- Reject invalid signatures, malformed records, and disallowed rollback versions.
- Apply updates atomically; preserve the last known good dataset and support rollback.
- Bound file size, record count, and parsing resources.
- Never embed private signing keys in the app.

If no real signed dataset source and trusted public key exist, do not pretend data is signed. Keep remote updates disabled; implement only useful local contracts and report the dependency. Custom domains must remain available offline. An empty or missing dataset must not be shown as successful protection.

## 7. User consent, education, and status

Extend existing setup/settings flows; do not create a parallel permission system.

Before requesting VPN authorization, explain:

- MIZAN uses Android VPN permission for local DNS filtering.
- Android allows only one active VPN at a time; enabling MIZAN may displace another VPN.
- Filtering is domain-based, not URL-path or page-content filtering.
- Encrypted DNS, hard-coded IPs, proxies, and other mechanisms may bypass filtering.
- Ordinary browsing traffic is not routed through a remote MIZAN server.
- Shared domains can cause legitimate services to become inaccessible.

Provide a clear user-initiated enable/disable action. Re-check actual state when the app resumes. Do not secretly restart filtering after the user stops it.

Represent at least these states accurately:

- Not configured.
- Permission required or denied.
- Ready but inactive.
- Starting.
- Active.
- Stopped by user.
- Revoked.
- Another VPN active / unable to establish.
- Dataset missing, stale, or incomplete.
- Degraded/error.

A button press is not proof that the VPN started. Never show “protected” or “active” unless confirmed by actual service state.

## 8. Commitment lifecycle integration

Use the existing commitment state machine and repositories.

- Do not apply commitment-specific filtering before explicit commitment activation.
- Start filtering only when the commitment rules require it, the user has consented, and VPN authorization is granted.
- If permission is absent, show the feature as unavailable/not enabled; do not claim enforcement.
- Stop commitment-specific filtering when the commitment is completed or emergency-terminated.
- If the user can disable filtering during a commitment under the existing product rules, reflect that honestly.
- Do not auto-renew commitments.
- Do not silently alter active domain rules.
- VPN failure must not disable app blocking or corrupt usage accounting.

If the existing requirements do not define whether website filtering is optional or required, stop and ask rather than inventing behavior.

## 9. Persistence, privacy, and security

Use existing Room and repository abstractions. Add only necessary schema changes and explicit migrations; never use destructive migration.

Persist only configured domains, category selections, dataset metadata, and minimal service-health/recovery state. Do not persist DNS query history.

- Keep domain configuration and datasets local.
- Do not transmit queried domains to MIZAN servers.
- Do not log queried domains in production.
- Do not inspect HTTPS content or collect browser history.
- Bound packet sizes, memory, domain counts, and dataset sizes.
- Use transactions for dataset activation and rollback.
- Do not store private signing keys in the database.
- Handle malformed local records safely.

## 10. Reliability and edge cases

Handle and test:

- Permission denied/revoked.
- Another VPN active.
- VPN interface creation failure.
- Network changes and upstream timeout.
- Malformed, truncated, oversized DNS packets.
- A, AAAA, and unsupported record types.
- Compression pointer loops.
- Case variation, trailing dots, IDNs.
- Exact/subdomain matches and suffix lookalikes.
- Duplicate/conflicting rules.
- Commitment completion/emergency termination during filtering.
- Service process death and recreation.
- Reboot and user stop.
- Missing, stale, malformed, corrupt, or empty dataset.
- Invalid dataset signature and interrupted update.
- Rollback to last known good dataset.
- Custom domains while offline.
- App restriction enforcement continuing when VPN filtering fails.

Fail in a controlled and visible way. Never leave the UI claiming filtering is active when it is not.

## 11. Testing requirements

### Domain unit tests

Cover normalization, IDN behavior, invalid domains, exact/subdomain matching, suffix lookalikes, rule precedence, inactive commitments, and duplicate rules.

### DNS parser tests

Use deterministic packet fixtures for valid A/AAAA queries, transaction ID preservation, blocking response construction, malformed headers/labels, compression pointers and loops, truncated/oversized packets, unsupported types, EDNS0, and bounded parsing.

### VPN and dataset tests

Test permission state, service start/stop/revoke, service-state reporting, network loss/recovery, protected upstream sockets, no accidental traffic blackhole, commitment lifecycle, dataset import/validation, signature verification if implemented, version/rollback policy, interrupted updates, atomic activation, last-known-good preservation, and offline custom domains.

### Physical-device validation

Use a harmless test domain or controlled DNS test setup:

1. Install and open MIZAN.
2. Review VPN education.
3. Request and grant VPN authorization through Android's system prompt.
4. Confirm actual VPN status in MIZAN.
5. Activate a test commitment with one known test domain.
6. Verify that domain is blocked using a safe test method.
7. Verify an unrelated domain still resolves.
8. Verify a subdomain is matched.
9. Verify a suffix lookalike is not accidentally blocked.
10. Confirm normal network traffic still works.
11. Activate another VPN and confirm MIZAN reports the conflict/stop state.
12. Stop/revoke MIZAN VPN and confirm the UI no longer reports active filtering.
13. Complete or terminate the test commitment and confirm filtering stops.
14. Confirm DNS query history is neither stored nor transmitted.

Do not test by blocking banking, emergency, operating-system, or primary communication services. If physical-device testing is unavailable, say so; do not claim it passed.

## 12. Explicitly out of scope

Do not implement:

- A general-purpose VPN, remote VPN server, or traffic proxy.
- Full-device packet inspection beyond what the DNS architecture strictly requires.
- HTTPS interception, TLS certificate installation, MITM, or content decryption.
- URL path, page text, keyword, or page-content filtering.
- Browser history collection or DNS query analytics.
- Claims of comprehensive adult-content detection without a verified dataset.
- YouTube Shorts-only filtering.
- Device Owner mode, uninstall prevention, or guaranteed anti-tampering.
- New commitment modes, automatic renewal, cloud accounts, sync, ads, or telemetry.
- Unrelated UI redesign.

## 13. Android policy and user trust

Clearly disclose VPN use and purpose before requesting authorization. Identify any upstream resolver. Respect the user's ability to stop the service or use another VPN. Review current Android VPN and Google Play policy if Play Store publication is intended. Document restrictions discovered.

MIZAN is a voluntary self-discipline tool. Do not claim perfect coverage, tamper-proof enforcement, or guaranteed blocking.

## 14. Definition of done

- [ ] Milestones 0–5 verified before implementation.
- [ ] Existing build and tests pass.
- [ ] Domain matching is pure and tested.
- [ ] VPN authorization uses Android's system flow.
- [ ] Local VPN routes and packet architecture are documented and valid.
- [ ] DNS parser is bounded and tested against malformed input.
- [ ] Existing domain rules and commitment lifecycle are respected.
- [ ] Allowed DNS works without routing loops or traffic blackholing.
- [ ] Permission/service status reflects actual state.
- [ ] Dataset source, license, and trust model are documented.
- [ ] Custom domains work offline.
- [ ] Room migrations preserve existing data.
- [ ] No DNS query history is stored or transmitted.
- [ ] Existing app blocking and usage tracking still work independently.
- [ ] New tests pass and physical-device validation is reported honestly.
- [ ] Android/OEM, DNS, and policy limitations are documented.
- [ ] No out-of-scope features were added.

## 15. Required completion report

Provide:

1. Preflight results for Milestones 0–5, build, and tests.
2. VPN architecture: interface, routes, DNS handling, upstream resolver, and traffic not inspected.
3. Permission flow and revocation behavior.
4. Domain normalization, matching, and precedence.
5. Supported DNS types, parser behavior, and limitations.
6. Dataset source/license/verification model, or why remote updates remain disabled.
7. Commitment and service lifecycle behavior.
8. Privacy: exact data collected, stored, and transmitted.
9. Exact test commands and actual results.
10. Physical-device tests performed and anything not tested.
11. Known limitations and Android/policy concerns.
12. Git status; do not commit or push unless the user explicitly asks.

After reporting, **stop**. Do not begin Milestone 7 or perform unrelated final hardening without a separate instruction.

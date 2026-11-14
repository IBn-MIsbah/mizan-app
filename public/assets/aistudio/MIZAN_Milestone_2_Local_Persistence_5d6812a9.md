# MIZAN — Milestone 2: Local Persistence

## 1. Role and operating instructions

You are implementing Milestone 2 of **MIZAN (ميزان)**, an offline-first native Android digital-discipline application.

Before changing code:

1. Read the repository-root `AGENTS.md` completely.
2. Read and follow the approved Milestone 2 scope in this document.
3. Inspect the actual repository, including the completed Milestone 0 foundation and Milestone 1 domain implementation.
4. Inspect existing Room/DataStore dependencies, Gradle version catalog, package structure, domain models, repository interfaces, and tests.
5. Confirm the project builds and existing tests pass before making changes.
6. Preserve approved architecture and existing behavior. Do not recreate or rename working structures without a concrete reason.
7. Present a concise implementation plan based on the actual codebase before editing.

If `AGENTS.md` or the Milestone 1 implementation is missing, or if the project does not build, stop and report the blocker. Do not guess at domain contracts or silently reimplement Milestone 1.

## 2. Objective

Implement MIZAN's **local persistence layer** using Room/SQLite and DataStore, connecting it to the existing domain layer through repository interfaces and implementations.

All user data must remain local on the device. No account, cloud sync, remote database, analytics, advertisements, or telemetry is permitted.

The persistence layer must preserve domain boundaries:
- Domain models must not depend on Room annotations or Android persistence APIs.
- Room entities and DataStore representations are data-layer implementation details.
- Repositories map between persistence models and domain models.
- Domain rules remain in the domain layer, not DAOs or database callbacks.

## 3. Scope

### 3.1 Room database

Use Room for structured local data. Inspect the existing domain model and persist the concepts that are part of the current product scope, as applicable:

- Commitments and lifecycle state.
- Commitment rules and restriction mode.
- Restricted app selections.
- Allowed app selections.
- Schedule windows and selected days.
- Daily shared allowance configuration and usage accounting.
- Usage records/events needed by the domain model.
- Security events.
- Accountability settings metadata, but never plaintext PINs or raw secrets.
- Removal request state if already represented in the approved domain model.

Do not invent new product concepts. If the Milestone 1 model does not yet contain a required concept, report the gap and ask before changing the domain contract.

Design normalized Room entities with:
- Stable primary keys.
- Explicit foreign keys where appropriate.
- Appropriate indexes for common lookups.
- Unique constraints where needed to prevent duplicate records.
- Explicit type converters for domain values that cannot be stored directly.
- Clear mapping functions between Room entities and domain models.

Avoid storing derived values redundantly unless there is a documented consistency reason.

### 3.2 Persistence correctness

Ensure:
- A commitment and its rules cannot become partially saved.
- Related app selections and schedule data are persisted consistently.
- Multi-step writes that must succeed together use Room transactions.
- Deleting or replacing related data follows explicit foreign-key/cascade behavior.
- Duplicate usage events are prevented when the domain model uses stable event identifiers.
- Shared daily allowance remains one commitment/day pool, not a per-app allowance.
- Date/time values are serialized consistently and decoded without silently changing their meaning.
- Enum or sealed-state persistence handles unknown/invalid stored values safely. Do not crash or silently reinterpret an unknown value as an unrelated valid state.

Use appropriate transaction boundaries and document any data consistency decisions.

### 3.3 DataStore

Use Preferences DataStore or Proto DataStore based on the existing project and actual needs. Do not add both without justification.

DataStore is intended for small application preferences, for example:
- Theme preference, if already part of the app.
- Onboarding completion state, if already part of the app.
- User preferences such as Wi-Fi-only filter updates, if represented in the current product model.
- Small non-sensitive UI/application settings.

Do not use DataStore as a replacement for Room's relational data.

Do not store plaintext PINs, password material, secret keys, or sensitive usage histories in preferences.

Expose settings through a small data-layer abstraction or repository appropriate to the current architecture. Handle defaults and corrupt/missing preference values explicitly.

### 3.4 Repository contracts and implementations

Inspect the existing domain repository interfaces.

- Implement existing contracts where they are approved and useful.
- If contracts are missing, propose the smallest set needed for current domain use cases before adding them.
- Keep Android and Room types out of domain interfaces.
- Expose appropriate suspend functions and Flow streams according to actual consumer needs.
- Define expected behavior for missing records, invalid IDs, and persistence failures.
- Map database exceptions to clear data-layer errors where appropriate.
- Do not swallow exceptions or report a successful save when the transaction failed.

Do not create generic repository frameworks or abstractions that have no current use.

### 3.5 Migrations and schema management

Configure Room schema export and migration strategy.

Requirements:
- Enable schema export to a version-controlled schema directory.
- Do not use destructive migration in production configuration.
- Provide explicit migrations whenever changing an existing database schema.
- If this is the first database version and no users can have prior data, establish version 1 cleanly and explain that no historical migration is needed.
- Test migrations when there are prior schema versions.
- Preserve user data across upgrades.
- Document how schema changes should be made in future milestones.

Do not add a migration from a nonexistent earlier schema merely to satisfy a checklist.

### 3.6 Security and privacy

- Keep all data local.
- Never log commitment details, app usage histories, PIN input, PIN hashes, cryptographic keys, or other sensitive user data.
- Never store a plaintext accountability PIN.
- Do not implement PIN creation, verification, hashing, or Keystore integration in this milestone unless an already-approved secure domain/data contract explicitly requires a non-secret metadata field. Actual PIN security belongs to a later dedicated implementation.
- Avoid exporting sensitive data through debug logs, crash messages, or test output.
- Use Android's normal app-private storage through Room/DataStore.
- Do not request new Android permissions for persistence.

## 4. Architecture requirements

Maintain the architecture established in Milestones 0 and 1.

Expected responsibilities:

- **Domain:** domain models, repository interfaces, and business rules.
- **Data:** Room database, entities, DAOs, DataStore, mappings, repository implementations.
- **App/DI:** Hilt bindings and database/DataStore provider configuration.
- **Tests:** repository, DAO, serialization, and migration tests.

Adapt paths to the actual repository. Do not add a separate Gradle module unless there is a clear technical justification.

Do not:
- Put Room annotations on domain models.
- Put SQL or Room access in ViewModels or Composables.
- Put business rules in DAOs.
- Make repositories depend on UI classes.
- Introduce network access or cloud services.
- Add enforcement services or Android permission handling.

## 5. Testing requirements

Add automated tests for persistence behavior.

### DAO/database tests
- Insert and retrieve each core record type.
- Update and delete behavior.
- Foreign-key and unique-constraint behavior.
- Correct relationship loading.
- Transaction rollback when a multi-step operation fails.
- Duplicate usage event prevention where supported.
- Correct date/time and enum conversion.
- Unknown or corrupt stored enum/state handling.
- Queries for commitments, app selections, daily usage, and security events.

### Repository tests
- Correct mapping between domain models and Room entities.
- Save and retrieve a commitment with its rules and related selections.
- Update commitment state without losing related configuration.
- Missing record behavior.
- Persistence errors are surfaced correctly.
- Flow observers receive expected updates.
- Shared allowance data remains associated with the commitment and local day.

### DataStore tests
- Default values.
- Updating and reading preferences.
- Missing keys.
- Invalid or unsupported stored values.
- Corrupt data behavior, as appropriate to the selected DataStore type.

### Migration tests
- Test every real migration from an existing schema version.
- Verify existing data is preserved.
- If this is a brand-new database version 1, document that no prior migration exists and test initial database creation instead.

Tests should be deterministic and must not require network access. Use suitable Android test infrastructure (for example, Room in-memory database tests) where appropriate.

## 6. Explicitly out of scope

Do **not** implement:
- AccessibilityService or actual app blocking.
- UsageStatsManager or real device usage measurement.
- VPNService, DNS filtering, or network interception.
- Filter database downloads, signature verification, or update scheduling.
- WorkManager jobs.
- Boot receivers, foreground services, permission monitoring, or anti-tampering.
- Complete Compose screens or product UI.
- Cloud sync, user accounts, remote APIs, analytics, advertisements, or telemetry.
- PIN setup, PIN verification, PIN hashing, or cryptographic key management.
- New product behavior not defined in `AGENTS.md` or the approved domain model.

Milestone 2 is persistence infrastructure only. Do not implement future milestone features early.

## 7. Implementation workflow

1. Read `AGENTS.md` and this document.
2. Inspect the current codebase and verify Milestones 0 and 1.
3. Run the existing build and tests before editing.
4. Report the planned entities, relationships, DAOs, repository implementations, DataStore settings, and migration strategy.
5. Implement Room entities, DAOs, converters, and database configuration.
6. Implement DataStore and its abstraction.
7. Implement repository implementations and Hilt bindings.
8. Add schema export/configuration.
9. Add database, repository, DataStore, and migration tests.
10. Run relevant Gradle build and test tasks.
11. Fix failures introduced by this milestone.
12. Review the diff for domain-boundary violations, accidental sensitive logging, destructive migrations, and scope creep.

If an important persistence decision is not supported by the current domain model, ask before changing it.

## 8. Completion report

When finished, provide:
- Summary of persistence capabilities implemented.
- Actual package/module structure.
- Room schema version and entity/relationship overview.
- DAO and repository contracts implemented.
- DataStore type and preference keys.
- Migration strategy and schema export location.
- Hilt/database configuration changes.
- Files created, changed, and deleted.
- Dependency changes, with versions and reasons.
- Exact build and test commands executed.
- Actual build/test results, including failures or skipped tests.
- Known limitations and unresolved decisions.
- Confirmation that all data remains local and no out-of-scope enforcement/network features were added.

Stop after Milestone 2. Do not begin Milestone 3 until the user reviews and approves the result.

# Finance Implementation Tasks

**Updated:** 2026-09-16
**Branch:** `feature/db-integration`

Statuses: `NOT_STARTED` · `IN_PROGRESS` · `BLOCKED` · `COMPLETE` · `NEEDS_REVIEW`

A task is `COMPLETE` only when it is implemented, compiles, its tests pass, and the
tracking documents are updated. "Code written" is not complete.

---

## Phase 0 — Architecture & Contracts

| ID | Description | Status | Depends on | Files | Tests |
|---|---|---|---|---|---|
| FIN-000 | Inspect backend/frontend, establish conventions to follow | COMPLETE | — | — | baseline `mvn compile` |
| FIN-001 | API contract for finance endpoints | COMPLETE | FIN-000 | `docs/finance/API_CONTRACT.md` | n/a |
| FIN-002 | Component ownership and boundary rules | COMPLETE | FIN-000 | `docs/finance/IMPLEMENTATION_OWNERSHIP.md` | n/a |

**Notes.** The architecture from the prior phase was checked for contradictions before
starting. One was found and resolved: `FINANCE_DATA_MODEL.md` §3.1 lists a `timezone`
column, which collides with nothing but reads ambiguously next to the audit timestamps —
implemented as `source_timezone`. No contract conflicts remain.

---

## Phase 1 — Finance Foundation

| ID | Description | Status | Depends on | Files | Tests |
|---|---|---|---|---|---|
| FIN-010 | Finance enum model (11 enums) | COMPLETE | FIN-000 | `entity/finance/enums/*.java` | via FIN-015 |
| FIN-011 | V110 migration — 7 foundation tables | COMPLETE | FIN-010 | `db/migration/V110__finance_foundation.sql` | Flyway apply verified on MySQL 8.0 |
| FIN-012 | Config entities (source system, capability, source-of-truth, mapping rule) | COMPLETE | FIN-011 | `entity/finance/Fin{SourceSystem,TempleCapability,SourceOfTruthDecl,MappingRule}.java` | FIN-015 |
| FIN-013 | Operational entities (sync batch, sync error, reconciliation result) | COMPLETE | FIN-011 | `entity/finance/Fin{SyncBatch,SyncError,ReconciliationResult}.java` | FIN-015 |
| FIN-014 | Repositories for all seven | COMPLETE | FIN-012, FIN-013 | `repository/finance/*.java` | FIN-015 |
| FIN-015 | Foundation repository test | COMPLETE | FIN-014 | `src/test/java/com/templeregistry/repository/finance/FinanceFoundationRepositoryTest.java` | 11/11 pass |
| FIN-016 | Sync-worker profile split (`@Profile("sync-worker")` / `@Profile("!sync-worker")`) | COMPLETE | FIN-014 | `config/FinanceProfiles`, `config/SchedulingConfig`, `TempleRegistryApplication`, `service/finance/sync/*`, `application-sync-worker.yml` | 32/32 pass across 5 classes |

**FIN-016 was the mechanism that makes ADR-001 structural rather than a convention (risk
R12), which is why it came before any connector existed to be wired into the wrong
process.** Delivered:

- `FinanceProfiles` — profile constants, so a mistyped `@Profile` is a compile error rather
  than a bean silently loading in the registry runtime.
- `SchedulingConfig` — `@EnableScheduling` moved off the application class and restricted to
  `!sync-worker` (FIN-D-007).
- `SyncWorkerConfig` — the single place the ingestion runtime is assembled; worker beans are
  explicit `@Bean` methods, never component-scanned (FIN-D-008).
- `SourceCredentialProvider` + environment-backed implementation — the Q5 seam, with no
  committed credentials and no fallback (FIN-D-009).
- `SyncWorkerBoundaryGuard` + `application-sync-worker.yml` — worker runs non-web, refuses to
  start otherwise, owns no schema (FIN-D-010).

**A hazard found and closed while doing this.** `@EnableScheduling` on the application class
would have started a second copy of every background job in the worker.
`EmailDeliveryService.processQueue()` claims outbox rows every ten seconds with no row
locking, so a second process would have **delivered duplicate emails to real recipients**.

---

## Phase 2 — Source Configuration

| ID | Description | Status | Depends on | Notes |
|---|---|---|---|---|
| FIN-020 | Credential resolution from environment/secret config, sync-worker only | NEEDS_REVIEW | FIN-016 | **Abstraction delivered** by FIN-016: `SourceCredentialProvider` with an environment-backed implementation, no committed credentials, no fallback. What remains is the **Q5 decision** on the permanent backing store; swapping it is one `@Bean` method in `SyncWorkerConfig`. |
| FIN-021 | Register Kollur source system (seed migration) | **COMPLETE** | FIN-011 | 1 row: `temple_id=300001`, `system_code=KOLSOHAM`, `source_temple_code=43`, `sync_enabled=0`, `credential_ref` alias only |
| FIN-022 | Declare Kollur capabilities with reasons | **COMPLETE** | FIN-021 | 19 rows — 10 AVAILABLE, 2 PARTIALLY_AVAILABLE, 7 NOT_AVAILABLE |
| FIN-023 | Declare Kollur source of truth for `REVENUE_AMOUNT` | **COMPLETE** | FIN-021 | 1 row, with all three measured rejected alternatives |
| FIN-024 | Seed Kollur mapping rules (category, metal type) | **COMPLETE** | FIN-021 | 9 rows |

**Migration.** `V111__kollur_finance_configuration.sql` — configuration data only. Creates no
table, alters no schema, enables no synchronization, stores no credential.

**Capability count corrected during implementation.** The task brief listed 18 capabilities;
the canonical `FinanceCapability` vocabulary has **19**. The missing one was
`IN_KIND_DONATION`, and Kollur has it — donated sarees are recorded with a donor-stated
value (182,675 records from FY2016-17). It is seeded `AVAILABLE`, with the reason recording
that the value is donor-declared rather than appraised and **must not be summed with auction
proceeds for the same articles**, since the auction realises the value of the identical
sarees.

**Capability breakdown as seeded**

| Availability | Capabilities |
|---|---|
| `AVAILABLE` (10) | `REVENUE`, `SEVA`, `DONATION`, `PRASADAM_SALE`, `CANCELLATION`, `PRECIOUS_METAL_COUNT`, `PRECIOUS_METAL_WEIGHT`, `IN_KIND_DONATION`, `NIRANTARA_SUBSCRIPTION`, `NIRANTARA_SCHEDULE` |
| `PARTIALLY_AVAILABLE` (2) | `NIRANTARA_PAYMENT`, `PAYMENT_MODE` |
| `NOT_AVAILABLE` (7) | `PRECIOUS_METAL_VALUE`, `NIRANTARA_EXECUTION`, `EXPENSE`, `EXPENSE_CATEGORY`, `GRANT`, `GRANT_UTILISATION`, `WORKS` |

**Coverage windows seeded:** revenue `2019-04-01 → 2026-07-26`; precious metals
`2015-04-01 → 2026-07-25` with FY2021-22 and FY2022-23 recorded as gaps; Nirantara payments
`2017-04-01 → 2024-03-31`; Nirantara schedule `2019-05-01 → 2027-07-26`, the future end date
being exactly why a scheduled row must never be reported as a performed seva.
`NIRANTARA_SUBSCRIPTION`, `EXPENSE` and the other unavailable capabilities carry **no**
coverage dates rather than invented ones.

**Mapping rules (9).** Four income buckets → `SEVA`, `SPECIAL_SEVA`, `DONATION`,
`PRASADAM_SALE`; one override → `HUNDI_DONATION`; two streams → `IN_KIND_DONATION`,
`ASSET_REALISATION`; two metal codes → `GOLD`, `SILVER`. No `PAYMENT_MODE` rules: for this
source payment mode is inferred from the absence of card details, which is connector logic,
not a value mapping.

Decisions: FIN-D-014 (conditional seed), FIN-D-015 (namespaced source values and
precedence), FIN-D-016 (provisional `connector_type`).

**Tests.** `KollurFinanceConfigurationMigrationTest` — 27 tests against a real MySQL 8.0
container with real Flyway, asserting database state rather than file contents. Verifies the
conditional guard (zero rows before the temple exists), every seeded row, and idempotency by
re-applying the seed.

---

## Phase 3 — Connector Framework

| ID | Description | Status | Depends on | Files | Tests |
|---|---|---|---|---|---|
| FIN-030 | `TempleFinanceConnector` contract and supporting types | **COMPLETE** | FIN-016 | `connector/finance/*.java` (11 types) | 33/33 pass |
| FIN-031 | Connector registry resolving `connector_bean` | NOT_STARTED | FIN-030 | | |
| FIN-032 | Probe and capability declaration wiring into onboarding | NOT_STARTED | FIN-030, FIN-031 | | |

Capabilities are declared per connector. No connector implements a capability its temple
does not have.

**FIN-030 delivered** — contract only: no implementation, no transport, no credential, no
schema change.

| Type | Purpose |
|---|---|
| `TempleFinanceConnector` | The contract: `metadata`, `describeCapabilities`, `probe`, `fingerprintSchema`, `extract`, `sourceTotals` |
| `ConnectorMetadata` | Identity; `connectorId` is what `fin_source_system.connector_bean` names |
| `SourceSystemDescriptor` | Which source, carrying a credential **alias** and no connection information |
| `SyncContext` | One extraction request; change axis and business-date axis kept separate |
| `DateRange` | Business dates, open-ended both sides for full-history checks |
| `RawRow` | One source record, values as raw strings |
| `SourceTotals` | Source-computed totals; an absent metric is NOT_AVAILABLE, never zero |
| `ReconMetric` | `RECORD_COUNT`, `GROSS_AMOUNT`, `CANCELLED_COUNT`, `CANCELLED_AMOUNT`, `QUANTITY` |
| `SchemaFingerprint` | Drift detection (R9); optional, because not every source has an inspectable schema |
| `SourceProbeResult` | Whether the source is usable — named for the question, not the mechanism |
| `UnsupportedCapabilityException` | Fails loudly, so an undeclared capability is never an empty result |

Decisions recorded as FIN-D-011 (reuse shared enums), FIN-D-012 (framework owns the
watermark; two axes), FIN-D-013 (raw strings, streamed).

**Architectural review, each answer backed by a test rather than a tick:**

| Question | Answer | Evidence |
|---|---|---|
| Assumes JDBC? | NO | `should_assumeNoTransport_when_scanned` |
| Assumes HTTP/API? | NO | same |
| Assumes the registry can reach temple DBs? | NO | contract is transport-free; implementations live in the worker |
| Contains credentials? | NO | `should_requireNoCredential_when_scanned` |
| Contains source-specific knowledge? | NO | `should_containNoTempleSpecificKnowledge_when_scanned` |
| Depends on JPA entities? | NO | `should_dependOnNoJpaEntity_when_scanned`, `should_beFrameworkFree_when_sharedEnumsInspected` |
| Bypasses reconciliation? | NO | `should_exposeNoReconciliationVerdict_when_contractInspected` |
| Allows all four mechanisms? | YES | parameterized over every `ConnectorType` value |

The purity guard was verified by mutation: a probe interface importing `java.sql.ResultSet`,
naming a password parameter and mentioning the first temple made **3** tests fail.

---

## Phase 4 — Kollur Connector

| ID | Description | Status | Depends on | Notes |
|---|---|---|---|---|
| FIN-040 | Add `mssql-jdbc` dependency | NOT_STARTED | FIN-030 | Not currently in `pom.xml` |
| FIN-041 | `KollurFinanceConnector` skeleton + `testConnection` | BLOCKED | FIN-040 | Blocked on **Q4** — no agreed network path from the platform to Kollur |
| FIN-042 | Schema fingerprinting | NOT_STARTED | FIN-041 | |
| FIN-043 | Revenue extraction: live table + six FY archives | NOT_STARTED | FIN-041 | **Must exclude `DailySevaNewOld`** — it duplicates all six archives |
| FIN-044 | `sourceTotals` for reconciliation | NOT_STARTED | FIN-041 | Must be computed **by the source**, not by re-summing our extract |

`KollurFinanceConnector` is the only class permitted to name `KOLSOHAM_LOCAL`,
`TempleCode 43`, `DailySevaNew` or `HKanikeItems`.

---

## Phase 5 — Revenue Pipeline

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-050 | `fin_stg_revenue` staging table and entity | NOT_STARTED | FIN-043 |
| FIN-051 | Dimensions: `fin_revenue_category`, `fin_service_dim` | NOT_STARTED | FIN-011 |
| FIN-052 | `fin_revenue_fact` (daily grain) | NOT_STARTED | FIN-051 |
| FIN-053 | Validation stage, rejections to `fin_sync_error` | NOT_STARTED | FIN-050 |
| FIN-054 | Mapping stage, unmapped values routed to `UNMAPPED` | NOT_STARTED | FIN-024, FIN-053 |
| FIN-055 | Normalization to daily grain | NOT_STARTED | FIN-054 |
| FIN-056 | Idempotent load keyed on the grain unique constraint | NOT_STARTED | FIN-052, FIN-055 |

---

## Phase 6 — Reconciliation

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-060 | Reconciliation service: source total vs central total | NOT_STARTED | FIN-044, FIN-056 |
| FIN-061 | Publication gate — a FAILED result blocks aggregate publication | NOT_STARTED | FIN-060 |

---

## Phase 7 — Aggregations

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-070 | `fin_agg_revenue_period` | NOT_STARTED | FIN-056 |
| FIN-071 | `fin_agg_revenue_service` | NOT_STARTED | FIN-056 |
| FIN-072 | Deterministic rebuild of affected periods only | NOT_STARTED | FIN-070, FIN-071, FIN-061 |

---

## Phase 8 — Finance APIs

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-080 | Response DTOs carrying the availability envelope | NOT_STARTED | FIN-001 |
| FIN-081 | `DcFinanceController` — summary, trend, monthly, categories, sevas | NOT_STARTED | FIN-072, FIN-080 |
| FIN-082 | Capabilities endpoint | NOT_STARTED | FIN-080 |
| FIN-083 | Reconciliation endpoint | NOT_STARTED | FIN-060 |
| FIN-084 | RBAC and DACVM wiring, reusing existing infrastructure | NOT_STARTED | FIN-081 |

---

## Phase 9 — Dynamic Dashboard

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-090 | Finance API client in the frontend | NOT_STARTED | FIN-081 |
| FIN-091 | Replace the static iframe with a real component | NOT_STARTED | FIN-090 |
| FIN-092 | `AvailabilityNotice` component — renders a reason, never a zero | NOT_STARTED | FIN-090 |
| FIN-093 | Delete hardcoded constants and the temple-300001 gate | NOT_STARTED | FIN-091 |

FIN-093 includes removing `ASSUMED_VALUE_PER_ITEM_RS = 12000` and
`ASSUMED_WEIGHT_PER_ITEM_GRAMS = 15`, and correcting the two false caveats in the current
dashboard (metal weight *is* recorded; monthly revenue *is* available).

---

## Phases 10–17 — Later

| ID | Description | Status |
|---|---|---|
| FIN-100 | Seva revenue reports | NOT_STARTED |
| FIN-110 | Precious metals — counts and real weights, value `NOT_AVAILABLE` | NOT_STARTED |
| FIN-120 | Nirantara — four lifecycle tables, execution empty by design | NOT_STARTED |
| FIN-130 | Capability system end to end in the UI | NOT_STARTED |
| FIN-140 | Generic multi-temple onboarding | NOT_STARTED |
| FIN-150 | Incremental sync with watermarks | NOT_STARTED |
| FIN-160 | Sync monitoring and observability | NOT_STARTED |
| FIN-170 | Production hardening | NOT_STARTED |

---

## Blocked Summary

| ID | Blocked by | Needed from |
|---|---|---|
| FIN-020 | Q5 — permanent credential store (abstraction already delivered) | Business / infra decision |
| FIN-041 | Q4 — network path to the Kollur database | Infra / temple IT |

Phase 1 is complete. Neither blocker stops FIN-021…FIN-024 (Kollur configuration seed) or
FIN-030 (connector framework contract), which are the next available work.

**Q4 costs no rework whichever way it resolves.** `ConnectorType` already models
`PULL_JDBC`, `PUSH_AGENT`, `SOURCE_API` and `FILE_DROP` as equals, and `SourceCredentials`
carries an optional principal precisely so a token- or shared-key mechanism fits the same
shape as a database user. If inbound JDBC to Kollur is refused, the answer is a
`connector_type` value and a different connector implementation — the canonical model,
aggregation, APIs and dashboard are untouched.

---

## Non-Task Defect Found

**FIN-X-001 — pre-existing schema drift, not introduced by finance work.**
`DeclarationClarification` maps `field_names_json`, but `declaration_clarifications` as
created in `V1__initial_schema.sql:657` has no such column and no migration adds it. The
column exists in deployed environments only because `ddl-auto: update` creates it.

Consequence: the two test classes that load the full context after Flyway —
`ApplicationContextIntegrationTest` (1 error) and `TrustIntegrationTest` (17) — fail at
`ddl-auto: validate` for reasons unrelated to finance. That accounts for all 18 errors in
the full suite. `ApplicationContextIntegrationTest` is also the only test that verifies
migrations against the entity model, which is the check every future finance migration
wants.

This is risk **R7** from the architecture document, now observed rather than predicted.
Confirmed pre-existing by stashing all finance code and reproducing the identical failures.

Not fixed here: it belongs to the declaration module, and whether deployed databases
already carry the column determines whether the corrective `ALTER` is a no-op or a real
change. Recommended fix is a one-line additive migration, owned by that module.

**FIN-X-002 — the `test` profile cannot boot a full application context.**
Two independent, pre-existing causes, both unrelated to finance:

1. `src/test/resources/application-test.properties` sets
   `app.jwt.public-key-path=classpath:jwt-test.pub`, and that file is a placeholder
   (`...Qw1Qw1Qw1...`) rather than a real RSA key. It fails to parse, so `ScopeHelper`
   cannot be constructed.
2. `application.yml` sets `spring.datasource.hikari.connection-init-sql` to the TiDB-only
   `SET tidb_enable_noop_functions=1`, which H2 rejects. `ApplicationContextIntegrationTest`
   already works around this for plain MySQL.

Neither is caused by finance work, and neither was visible before because every existing
full-context test fails earlier on FIN-X-001. `SyncWorkerRuntimeContextTest` and
`RegistryRuntimeContextTest` override both locally via `@TestPropertySource`, introducing no
new key material and changing nothing in production configuration.

Worth fixing centrally, because the project currently has **no** working full-context test
on the `test` profile, and the finance boundary tests are the first to need one.

---

## Next Available Work

There is no `FIN-017` in this numbering — Phase 1 ends at FIN-016. The next tasks are:

| ID | Description | Status | Why it is next |
|---|---|---|---|
| ~~FIN-030~~ | `TempleFinanceConnector` contract | **COMPLETE** | Delivered |
| ~~FIN-021…024~~ | Kollur configuration seed | **COMPLETE** | Delivered as `V111` |
| **FIN-031** | Connector registry resolving `connector_bean` to a bean | **RECOMMENDED NEXT** | The configuration now names `kollurFinanceConnector`; the registry is what turns that string into a bean, and it is the last piece of framework that can be built before a connector exists |

Unblocked by Q4 and Q5.

**One item deliberately deferred, and worth a decision.** A source-of-truth declaration for
`PRECIOUS_METAL_WEIGHT` (`HKanikeItems.Qty`, grams) was **not** seeded, because FIN-023 was
scoped to `REVENUE_AMOUNT`. It is the natural defence against the discarded
"assume 15 g and ₹12,000 per item" approach returning, and is a one-row addition whenever the
reviewer wants it.

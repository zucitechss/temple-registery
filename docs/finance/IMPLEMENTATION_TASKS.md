# Finance Implementation Tasks

**Updated:** 2026-09-24 (FIN-058)
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
| FIN-031 | Connector registry resolving `connector_bean` | **COMPLETE** | FIN-030 | `connector/finance/ConnectorRegistry.java`, `ConnectorConfigurationException.java`, a bean method in `SyncWorkerConfig` | 13/13 pass |
| FIN-032 | Probe and capability declaration wiring into onboarding | NOT_STARTED | FIN-030, FIN-031 | | |
| FIN-033 | **Generic `JdbcTableConnector`** — the configuration-driven connector ADR-004 promises | **COMPLETE** | FIN-030, FIN-031 | `service/finance/sync/jdbc/*.java` (7 types), 3 bean methods in `SyncWorkerConfig` | 68/68 pass |

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

**FIN-031 delivered** — the step from *configured* to *registered*. Two types, one bean
method, no schema change, no credential, no transport.

| Type | Purpose |
|---|---|
| `ConnectorRegistry` | A name lookup: the identifier in `connector_bean` to the connector registered under it. Immutable, plain Java, no Spring import |
| `ConnectorConfigurationException` | The four ways configuration and code can disagree: not registered, no connector named, mechanism mismatch, name/metadata disagreement |

Registered as `connectorRegistry` in `SyncWorkerConfig`, built from
`getBeansOfType(TempleFinanceConnector.class)` — so a connector enters the registry by being
declared as a worker bean and by no other route. Nothing is component-scanned (FIN-D-008).

**The behaviour that matters is the failure.** Resolution returns a connector or throws;
there is no `Optional`, no null and no default implementation, because a caller handling
"absent" by skipping the source would produce a batch that succeeded having read nothing
(FIN-D-017). Kollur exercises this path today: it is fully configured, names
`kollurFinanceConnector`, and that bean does not exist.

| Question | Answer | Evidence |
|---|---|---|
| Missing connector fails explicitly? | YES | `should_fail_when_configuredConnectorIsNotRegistered` |
| Returns null / `Optional` / no-op? | NO | `should_offerNoAbsentResult_when_apiInspected` |
| Kollur resolves to something? | NO — it throws | `should_fail_when_resolvingTheConfiguredKollurConnector` |
| Source-specific branch in the registry? | NO | `should_stayGeneric_when_sourcesScanned` |
| Resolves a credential? | NO | `should_resolve_when_sourceCarriesNoCredentialReference` |
| Any transport or persistence? | NO | `should_stayGeneric_when_sourcesScanned`, `ConnectorContractPurityTest` |
| Present in the registry runtime? | NO | `RegistryRuntimeContextTest`, `should_registerNoConnectorRegistry_when_syncWorkerProfileInactive` |
| Declared `connector_type` verified? | YES | `should_fail_when_connectorTypeContradictsConfiguration` |

Mutation-verified: replacing the missing-connector throw with `return null` fails 4 tests,
including the Kollur one.

---

## Phase 4 — Kollur Connector

| ID | Description | Status | Depends on | Notes |
|---|---|---|---|---|
| FIN-040 | Add `mssql-jdbc` dependency | NOT_STARTED | FIN-030 | Not currently in `pom.xml`. **Not the generic connector** — that is FIN-033, which is complete and adds no driver. This row exists only for the SQL Server source behind Q4 |
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
| FIN-050 | `fin_stg_revenue` staging table and entity | **COMPLETE** | FIN-011 (dependency on FIN-043 was not real: the table needs no extract) |
| FIN-051 | Dimensions: `fin_revenue_category`, `fin_service_dim` | **COMPLETE** | FIN-011 |
| FIN-052 | `fin_revenue_fact` (daily grain) | **COMPLETE** | FIN-051 |
| FIN-052A | `uk_frf_grain` gains `source_system_id` (V118) | **COMPLETE** | FIN-052, FIN-070B |
| FIN-053 | Validation stage, rejections to `fin_sync_error` | **COMPLETE** | FIN-050 |
| FIN-054 | Mapping stage, unmapped values routed to `UNMAPPED` | **COMPLETE** | FIN-024, FIN-053 |
| FIN-055 | Normalization to daily grain | **COMPLETE** | FIN-054 |
| FIN-056 | Idempotent load keyed on the grain unique constraint | **COMPLETE** | FIN-052, FIN-055 |



**FIN-050 delivered** — `V113__finance_revenue_staging.sql`: one table, one entity, one enum.
No connector, no transport, no reader, no service, no repository.

**The dependency on FIN-043 in the table above was not real.** It was written when staging was
imagined alongside a working extract; the table needs only the foundation, and building it now
is what lets FIN-053…FIN-056 be developed and tested against synthetic staging rows instead of
waiting on the Q4 network answer.

**Grain: one row per record a connector delivered, within one sync batch** — one `RawRow`,
exactly as `extract()` produced it, never merged, split or reinterpreted (FIN-D-021).
Connectors group at the source (ADR-003), so a delivered record is usually already a
source-level grouping; staging does not assume that. Several staged rows may contribute to one
canonical daily fact, and that collapse belongs to FIN-055.

**Idempotency: `uk_fsr_batch_record (sync_batch_id, source_record_ref)`.** A replay stages the
same records again under a new batch — legitimate, and how a restatement is investigated. The
same record twice inside one batch is refused. Safe because `RawRow.sourceRecordRef` is
mandatory and non-blank by contract, so unlike `fin_revenue_fact` no NULL-key workaround is
needed; the same NULL-distinct index behaviour is a hazard there (FIN-D-018) and correct here.

| Column | Null | Why |
|---|---|---|
| `temple_id`, `source_system_id`, `sync_batch_id`, `source_record_ref` | NOT NULL | A staged figure whose origin is unknown cannot be investigated, which is the only reason to keep it |
| `raw_json` | NOT NULL | The record as delivered: connector field names, values as raw strings. Source vocabulary stops here |
| `source_business_date` | NULL | Connector-declared and **advisory**; NULL means "not declared at extraction", never "no date". Normalization derives the authoritative date |
| `validation_status` | NOT NULL, default `RECEIVED` | `RECEIVED` → `VALID`/`REJECTED` (FIN-053) → `LOADED` (FIN-056); monotonic, `REJECTED` terminal |
| `rejection_reason` | NULL | Human-readable; the coded, queryable form stays in `fin_sync_error` |
| `extracted_at`, `created_at`, `updated_at` | NOT NULL | Three different questions: when the connector read it, when we stored it, when its state last changed. None is a business date |

**No typed amount column, deliberately.** Amounts stay inside `raw_json` as strings until
normalization. A typed column would force parsing during extraction, and one malformed value
would cost the whole batch — which is precisely what staging exists to prevent. A test asserts
no `DECIMAL`, `FLOAT`, `DOUBLE` or `REAL` column exists in the table.

**Evaluated and deliberately omitted:** `source_modified_at` (the batch already carries the
change window, and a per-row value would require interpreting the payload), a row hash, a
`DUPLICATE` status (the constraint refuses one, and across batches it is a restatement, not a
duplicate), a `loaded_fact_id` back-link (FIN-056's to add if it wants one; a nullable column
later is a trivial migration), and any `CHECK` on the status vocabulary (TiDB accepted but
ignored `CHECK` before v7.2, so it would be enforcement that silently is not).

| Review question | Answer | Evidence |
|---|---|---|
| Generic across temples, and all four mechanisms? | YES | no transport or temple column exists; `should_stayGeneric_when_migrationScanned` |
| Kollur schema or source table names? | NO | same scan, plus `should_containNoSourceColumns_when_schemaInspected` |
| Provenance preserved and mandatory? | YES | `should_requireProvenance_when_rowStaged` |
| Business date separate from extraction time? | YES | `should_separateBusinessDateFromExtraction_when_oldRecordIsReExtracted` |
| Grain explicit, and compatible with daily facts? | YES | FIN-D-021; many staged rows → one fact via FIN-055 |
| Duplicate and replay documented and enforced? | YES | `uk_fsr_batch_record`, mutation-verified |
| Missing distinguishable from measured zero? | YES | nullable advisory date, payload preserved verbatim including empty values |
| Credentials or transport detail? | NO | scan covers password, credential, jdbc, host, port, endpoint, driver |
| Rejected records investigable? | YES | row retained with reason and payload; `should_retainRejection_when_rowFailsValidation` |
| Anything outside FIN-050 scope? | NO | no status transition is implemented; every row lands `RECEIVED` |
| Pipeline testable with synthetic rows, no temple database? | YES | the 20 tests do exactly that |
**FIN-051 / FIN-052 delivered** — `V112__finance_canonical_revenue.sql`: two dimensions, one
fact, twelve seeded category rows, three entities, two enums. No connector, no staging, no
transport, no service, no repository.

| Table | Shape |
|---|---|
| `fin_revenue_category` | Platform-wide taxonomy. **No `temple_id` column** — a taxonomy each temple invents for itself cannot produce a district total |
| `fin_service_dim` | Per temple (`uk_fsd_temple_service`), because service catalogues genuinely differ; every service still resolves to a platform-wide category |
| `fin_revenue_fact` | Daily grain, seven-column unique key, every measure nullable |

**Canonical grain, and one correction to ADR-003.** The documented key is
`(temple, date, service, category, payment mode, counter)`. Two problems were found and both
are corrected in `uk_frf_grain`:

1. **NULL semantics.** Three grain columns are legitimately nullable — a hundi collection has
   no service, no counter and no operator — and MySQL and TiDB treat NULLs in a unique index
   as distinct. Written literally, the documented key accepts the same hundi fact twice.
   Generated key columns collapse those NULLs to concrete values (FIN-D-018), **verified by
   mutation**: with the literal key, the duplicate is silently accepted.
2. **`operator_ref` added to the key.** R27 reports revenue by counter *and operator* from
   this table, which the documented key cannot support; worse, a connector grouping by
   operator would emit rows that collide, so the loader would lose revenue or overwrite it.
   Whatever a connector groups by must be a subset of the key (FIN-D-019).

**Cancellation is three states, not two.** `cancelled_amount` NULL means the source does not
record cancellations; `0` means it does and there were none. `net_amount` is a stored
generated column (`gross − cancelled`) so no loader can disagree with it, and it is NULL when
cancellations are unknown — reporting gross as net would assert that nothing was cancelled.

**`UNMAPPED` is a seeded category.** FIN-054 routes unmapped source values there rather than
into `OTHER_INCOME`, whose own description forbids that use: unmapped revenue is real money
whose kind nobody has established, and it must stay visible until a mapping rule resolves it.

| Review question | Answer | Evidence |
|---|---|---|
| Generic across temples? | YES | `should_isolateTemples_when_grainsMatch`, `should_scopeServicesPerTemple_when_codesRepeat` |
| Source schema copied in? | NO | `should_containNoSourceVocabulary_when_migrationScanned`, `should_containNoSourceColumns_when_schemaInspected` |
| Transport or credentials represented? | NO | same scan |
| Business date separate from sync time? | YES | `should_keepBusinessDateSeparate_when_factIsRestated` |
| Cancellation distinguishable from zero and unknown? | YES | `should_distinguishCancellationStates_when_stored` |
| Provenance retained and mandatory? | YES | `should_requireProvenance_when_factInserted`, `should_retainProvenance_when_stored` |
| Grain explicit and database-enforced? | YES | `uk_frf_grain`, mutation-verified |
| Future connectors write without schema change? | YES | no temple-specific column exists |
| Dashboard can operate without source access? | YES | every catalogued revenue report resolves from these three tables |


**FIN-053 delivered** — `RevenueStagingValidator`, `FinStgRevenueRepository`, one bean method.
No migration, no schema change, no connector, no transport, no credential, no loader.

**Validation boundary.** Six rules, all statable without knowing any source system
(FIN-D-022): `BLANK_RECORD_REF`, `PROVENANCE_MISMATCH`, `MISSING_PAYLOAD`,
`MALFORMED_PAYLOAD`, `PAYLOAD_NOT_OBJECT`, `EMPTY_PAYLOAD`, `NON_SCALAR_FIELD`.

Dates and amounts are **not** validated here: they live in `raw_json` under connector-specific
field names, and FIN-055 reads them against the source-of-truth declaration. Checking them at
this stage would require teaching a shared pipeline stage one temple's vocabulary.

`PROVENANCE_MISMATCH` is the rule the database cannot enforce and the one that matters most —
a staged row whose temple disagrees with its batch would attribute one temple's money to
another with nothing downstream noticing.

**Rejection.** One `fin_sync_error` per rejected row at stage `VALIDATE` (FIN-D-023), so
`rows_rejected = 143` is 143 openable rows. Several failures on one row produce one error
naming all of them, coded by the first rule in a fixed order. `rows_rejected` is **derived**
from the error count rather than incremented, so retries cannot inflate it. The error's
`raw_payload_json` stays null — staging already holds the payload, and a second copy would
spread whatever personal data a temple's records contain.

**Transactions.** One per row (`REQUIRES_NEW`), status change and error insert committing
together, with the status change written as a conditional claim (FIN-D-024). That single
mechanism gives atomic rejection, terminal `REJECTED`/`LOADED`, and safety under two
concurrent validators. A persistence failure aborts the run rather than skipping rows.

**Termination (FIN-D-026, repaired after the task was first marked complete).** The chunked
read was keyed on offset, always asking for the first page of `RECEIVED` rows, so the loop
ended only if every row it read left that state — and a row that did not, the ordinary result
of losing a claim race, came back for ever. It is now keyed on an advancing id cursor, so each
row is offered once and the query is guaranteed to run out. A quadratic re-scan went with it:
the test class runs in 74.8 s against 221.4 s.

| Review question | Answer | Evidence |
|---|---|---|
| Source vocabulary, transport or credentials? | NO | `should_stayGeneric_when_sourceScanned` |
| Payload rewritten? | NO | `should_preservePayload_when_validated` |
| Missing turned into zero? | NO | `should_distinguishMissingFromZero_when_validating` |
| Rejection without a record, or vice versa? | NO | M1 (12 failures) and M2 (14), both freshly measured |
| Terminal rows reprocessed? | NO | `should_notReprocess_when_rowAlreadyRejected`, `should_notRevalidate_when_rowAlreadyLoaded` |
| Two validators double-count? | NO | `should_processEachRowOnce_when_twoValidatorsRunTogether`; M3 kills on this test alone |
| Batch counter explainable? | YES | `should_keepBatchCounterExplainable_when_validated` |
| Anything normalized or loaded here? | NO | `should_leaveRowInStaging_when_validated` |
| Can the run fail to terminate? | NO | `should_terminate_when_noRowCanBeClaimed`, `should_processClaimableRows_when_othersCannotBeClaimed`; M5 kills on both |

**What was wrong when this task was first marked COMPLETE**, recorded because the tracking
documents asserted all of it:

| Claim previously made | Actual state |
|---|---|
| `should_terminate_when_noRowCanBeClaimed` passes | Failed on a 20 s timeout; the guard it tested was in no source file |
| Four mutations verified the rejection guarantees | M1 measured; M2 carried M1's report verbatim; M3–M5 never ran (FIN-D-027) |
| Finance suite 155 passing, 0 failures | 155 run, 3 failures, 2 errors — the new bean broke two worker-context tests |
| The suite covered FIN-053 | The documented filter matched none of its 25 tests |

Corrected baseline: **180 tests, 0 failures, 0 errors**, filter extended with `*RevenueStaging*`.
---
---


---

## FIN-054 — Implementation Plan (recorded before implementing)

**Two mapping layers, and which one this task is.** ADR-004 draws the line: *which rows and
which columns* is code; *what a value means* is configuration.

| Layer | Question | Where it lives | Status |
|---|---|---|---|
| A — structural extraction | which source table and column produce a staged field | connector code, `fin_source_of_truth_decl` | FIN-041/043, blocked on Q4 |
| B — semantic business mapping | what a staged source value means canonically | `fin_mapping_rule` | **FIN-054, this task** |

FIN-054 implements layer B only. It never names a source table or column.

**The gap that must be closed first.** Layer B cannot run without knowing which staged field
carries the value a rule matches on — and the stage is forbidden to know source field names.
Two facts resolve it:

1. `fin_mapping_rule.source_value` is already namespaced (`SANNIDHI:DS`, `SEVA_CODE:430`,
   `STREAM:SAREE_AUCTION`) per FIN-D-015. **The namespace is the name of the staged field the
   rule reads.** The engine derives its candidate fields from the rules themselves, so it holds
   no source vocabulary — the vocabulary is entirely in configuration rows.
2. FIN-D-015 states that the more specific rule wins, but the table has **no priority column**,
   so the engine has no way to know `SEVA_CODE` outranks `SANNIDHI`. FIN-D-015's own consequence
   line pushes this to "the connector must apply the precedence rule", which would put a
   business decision inside a per-temple class. A stored `priority` makes it configuration,
   deterministic, and auditable.

**Deliverables.**

| # | Item |
|---|---|
| 1 | `V114` — add `fin_mapping_rule.priority`; create `fin_stg_revenue_mapping` |
| 2 | `MappingOutcome` enum — `MAPPED`, `UNMAPPED`, `AMBIGUOUS`, `NOT_APPLICABLE`, `INVALID_CONFIGURATION` |
| 3 | `FinStgRevenueMapping` entity + repository |
| 4 | `RevenueCategoryMapper` — the deterministic resolver, pure and unit-testable |
| 5 | `RevenueMappingStage` — reads `VALID` staged rows, writes one mapping row each |
| 6 | Tests against a real MySQL 8.0 container, plus mutations |

**Resolution, deterministically.** Load active rules for the source system and mapping type.
For each distinct namespace among them, read the staged field of that name and form the
candidate key `NAMESPACE:value`. Match candidates against rules, keep the highest `priority`,
and then:

- exactly one winner → `MAPPED`
- two or more winners at the same priority → `AMBIGUOUS`, never an arbitrary pick
- a value present but no rule matches → `UNMAPPED`, routed to the seeded `UNMAPPED` category
- no rule namespace present in the payload, or present but blank → `NOT_APPLICABLE`
- a winning rule naming a canonical category that does not exist → `INVALID_CONFIGURATION`

`UNMAPPED` and `NOT_APPLICABLE` are different questions with different owners: the first needs
a new mapping rule, the second means the source supplied nothing to map. Collapsing them would
hide a broken extraction behind a configuration gap.

**Scope: `REVENUE_CATEGORY` only.** It is the only mapping type with both seeded rules and a
seeded canonical target. `SERVICE` needs 164 rules and `fin_service_dim` rows that do not
exist; `PAYMENT_MODE` for the first source is inferred from field presence, which ADR-004 puts
in connector code, and has no seeded rules; `METAL_TYPE` belongs to precious metals (FIN-110).
The resolver is generic over `MappingType` so those arrive as configuration, not code.

**Staging is not modified.** Results go in a separate table keyed
`(stg_revenue_id, mapping_type)`. `StagingStatus` gains no `MAPPED` value: staging stays the
immutable evidence FIN-050 designed it to be, and re-running mapping after a rule correction is
an update of the mapping row, not a rewrite of what the source said.

**Out of scope, deliberately:** no canonical fact write (FIN-056), no date or amount handling
(FIN-055), no financial-year derivation (FIN-055), no orchestration, no connector, no API.

### FIN-055 — Normalization to daily grain

**Delivered.** `FinancialYear`, `RevenueField`, `StagedPayload`, `RevenueNormalizer` and
`RevenueNormalizationStage`, plus `V115` declaring the business-date field for the first source.
The two things every earlier stage deferred: amounts and dates.

**It reads a payload only where a declaration says to.** This is the first stage entitled to
look at a money value, and ADR-008 is why it does not pick the field itself: for the first
onboarded source three columns plausibly represent revenue and disagree by 41%, and the one
that looks like an improvement is wrong. The code knows metric names; `fin_source_of_truth_decl`
names the fields. `V115` adds the declaration for the business date, which nothing had declared
— the amount could be read and never placed in time.

**The open question from FIN-053 is settled** (FIN-D-033): a declaration's `source_field` names
the *staged* field, the same vocabulary a mapping rule's namespace uses.

**Five refusals, each with a code:** an undecided mapping outcome, a canonical category outside
the taxonomy, a missing or empty declared field, an ambiguous date, a non-numeric amount, and an
amount too precise for `DECIMAL(18,2)`. None of them is repaired into a value (FIN-D-039).

**The collapse happens here** because it needs the whole batch and must be visible and testable.
Grouping is on `uk_frf_grain`'s six columns; the in-memory key and the database constraint must
stay in step or the same fact lands twice.

**Absence survives it.** An undeclared measure is NULL on every fact, never zero (FIN-D-035),
and a group with any unknown contributor totals to NULL rather than a partial sum (FIN-D-036).

**Out of scope, deliberately:** no `fin_revenue_fact` write (FIN-056), no staging status change
(FIN-D-038), no service resolution, no payment-mode mapping, no orchestration, no connector, no
API.


### FIN-056 — Idempotent load

**Delivered.** `FinRevenueFactRepository` with a native upsert through `uk_frf_grain`,
`FinStgRevenueRepository.markLoaded`, and `RevenueLoadStage`. The first write to
`fin_revenue_fact`, and the first figure this platform will stand behind.

**One line carries most of the risk.** `ON DUPLICATE KEY UPDATE gross_amount =
VALUES(gross_amount)` — assignment, not accumulation (FIN-D-041). An accumulating upsert
satisfies the unique constraint perfectly and doubles a temple's revenue on every replay, so
nothing in the schema can catch it; only a test that checks the number can.

**A retry and a restatement are the same mechanism.** Loading a batch twice leaves the totals
unchanged. A later batch covering days already loaded replaces them, because the source was
re-read and that is what it now says. `created_at` is excluded from the update list so a
restatement of an old day stays distinguishable from a first load.

**Failure keeps what succeeded and still fails the batch** (FIN-D-042). Per-fact transactions, so
a retry does not redo work that worked; `LoadFailedException` and errors at stage `LOAD`, so no
batch reports success having written half its facts.

**`rows_loaded` is derived** from the staged rows actually `LOADED`, never incremented — the rule
FIN-D-023 imposed on `rows_rejected`, for the same reason.

**Out of scope, deliberately:** no reconciliation (FIN-060), no aggregation (FIN-070/071), no
orchestration, no connector, no API. And no deletion detection — an incremental window cannot
support it (FIN-D-044).


### FIN-052A — The canonical grain distinguishes source systems

**Migration:** `V118__finance_fact_grain_source_system.sql`. **Decision:** FIN-D-067, acting on
FIN-070B D1. **Closes:** limitation 47.

`uk_frf_grain` was seven columns and omitted `source_system_id`, although the column has always
been `NOT NULL` and populated. Two sources reporting the same temple, day, service, category,
payment mode, counter and operator collided, and the loader's `ON DUPLICATE KEY UPDATE` replaced
the first source's figures with the second's rather than keeping both. It is now eight columns,
with `source_system_id` second:

```
uk_frf_grain (temple_id, source_system_id, transaction_date, grain_service_key,
              category_id, payment_mode, grain_counter_key, grain_operator_key)
```

Second, not last, so the index also serves the `(temple_id, source_system_id)` prefix that every
source-scoped reconciliation query already filters on — a prefix that did not exist before. No
benchmark was taken and none is claimed.

**The migration is one `ALTER TABLE` and touches no data.** Widening a UNIQUE key cannot be
violated by existing rows, and `source_system_id` needed no backfill, so nothing was deleted,
rewritten or restated. This corrects FIN-070A's reasoning, which had argued the change was cheap
only while the table was empty; the real deadline was the second source system, not the first fact
(FIN-D-067).

**Production code changed in two places, both small.** `source_system_id = VALUES(source_system_id)`
left the upsert's update list, because a matched row now necessarily already holds the value — and
that assignment was precisely how one source used to take ownership of another's figures.
`RevenueNormalizer.GrainKey` needed no change at all: normalization runs over one batch, and a
batch has one temple and one source system, so both are constant across every key it builds.

**What it does not do.** It recovers nothing already overwritten — those figures were replaced in
place and no history of prior values exists. It does not make the platform multi-source-capable:
`uk_ftc_temple_capability` and `uk_fsd_temple_service` keep the single-source assumption and stay
deferred under FIN-070B D9 (limitation 67). And **ADR-003 was not edited**, though it now describes
a grain the schema no longer has: amending an ADR is a governance act and was explained rather than
performed.

Verified on MySQL 8.0: `FinanceCanonicalRevenueMigrationTest` 24 (5 new, including the index read
back from `information_schema` and a check that no column was relaxed), `RevenueLoadStageTest` 20
(2 new, through the real loader), `RevenueReconciliationStageTest` 26 — whose two-source scoping
test was moved back onto a single shared day, which the old seven-column grain could not have kept apart. Finance regression **437
run, 0 failures, 0 errors, 0 skipped**. TiDB is not verified.

---

## Phase 5b — Pipeline Orchestration

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-057 | Finance pipeline orchestrator | **COMPLETE** | FIN-053…FIN-056 |
| FIN-058 | **Manual worker sync trigger** — the first thing that creates a batch and runs the pipeline | **COMPLETE** | FIN-057, FIN-033, FIN-140-D |

**Why here.** FIN-056 finished the last stage, and five stages that each run alone are not a
pipeline. It sits before FIN-060 because reconciliation compares what a *run* produced against
source totals, and until something executes a run there is nothing to reconcile. It is also the
last substantial piece buildable without Q4.

### Pre-implementation investigation — what actually exists

Verified by reading source, not documentation.

| Stage | Entry point | Implemented? |
|---|---|---|
| Extract | — | **NO.** No production code writes `fin_stg_revenue`. Confirmed by searching every `save` against it: the only hits are tests |
| Validate | `RevenueStagingValidator.validateBatch(long)` | YES (FIN-053) |
| Map | `RevenueMappingStage.mapBatch(long)` | YES (FIN-054) |
| Normalize | `RevenueNormalizationStage.normalizeBatch(long)` | YES (FIN-055) |
| Load | `RevenueLoadStage.loadBatch(long)` | YES (FIN-056), and it calls normalize itself |

**The finding that shaped this task: there is no staging writer.** Limitation 11 said extraction
"is FIN-043", but FIN-043 is scoped as *Kollur's* extraction — the live table plus six financial-
year archives. Draining a connector's `Stream<RawRow>` into staging is generic, belongs to no
temple, and nothing owned it. Without it the orchestrator has no `EXTRACT` step at all and the
pipeline can never run in production, only in tests.

So FIN-057 adds `RevenueExtractionStage`: generic, driven by `ConnectorRegistry` and the existing
`RawRow` contract, naming no source table or column. FIN-043 remains what it always was — the
Kollur connector's `extract()` — and is still blocked on Q4.

### Stage contracts, as they actually are

| Stage | In | Out | Idempotent | Retry | Transaction |
|---|---|---|---|---|---|
| Extract | batch id | rows staged, rows refused | per batch: `uk_fsr_batch_record` refuses a repeat within one batch | yes, into a new batch | one per chunk |
| Validate | batch id | validated / rejected / already-claimed | yes — conditional claim, terminal rows skipped | yes | one per row |
| Map | batch id | counts by outcome, contended | yes — `uk_fsrm_row_type`, decision replaced | yes | one per row |
| Normalize | batch id | facts + rejections | yes — pure over staging, writes only errors | yes | one for the error summary |
| Load | batch id | facts written, rows loaded | yes — upsert assigns (FIN-D-041) | yes | one per fact |

Every stage takes a batch id and nothing else, which is what makes composition trivial: the
orchestrator passes an id and a status, never a payload.

**Load already calls normalize.** Rather than change a working stage to fit a new abstraction,
the orchestrator treats `LOAD` as the step that also normalizes, and reports both. Documented
rather than refactored (FIN-D-047).

### Batch state machine — existing statuses, no new enum

`SyncStatus` already models this, and mirrors the `email_outbox` machine this codebase runs:

```
PENDING --claim--> RUNNING --+--> SUCCESS
                             +--> FAILED   (retryable until max_retries)
```

Per-stage statuses (`EXTRACTING`, `MAPPING`, …) are deliberately **not** added. They would be a
second lifecycle vocabulary for information `fin_sync_error.error_stage` already carries at a
finer grain, and every one would need a migration, an enum value and a transition rule. The
current stage lives on the batch only while it runs, in the log and in the failure record.

### Out of scope, deliberately

No reconciliation (FIN-060), no aggregation, no API, no scheduler, no cancellation, no retry
driver, no watermark advancement, and no connector implementation.


### Delivered

`FinancePipelineOrchestrator` + `RevenueExtractionStage`, both registered as explicit `@Bean`s in
`SyncWorkerConfig` (FIN-D-008). `claimForRun` added to `FinSyncBatchRepository`,
`countBySyncBatchId` to `FinStgRevenueRepository`. No migration — the state machine uses the
statuses that already existed, and no new enum value was introduced.

Verified by `FinancePipelineOrchestratorTest`, 13 tests on MySQL 8.0, wiring the real stages
behind a synthetic in-test connector. Finance regression 343 green. Decisions FIN-D-045…049.

One thing the plan did not anticipate: rebuilding entities on the row-by-row retry (FIN-D-046).
The test found it; reasoning had not.

### FIN-058 — Manual sync trigger

**The pipeline had never run.** FIN-057 composed six stages, FIN-033 registered a real connector and
FIN-140-D gave an administrator a switch — and nothing read the switch, nothing created a
`fin_sync_batch`, and nothing called the orchestrator. A fully configured platform read no rows.

**What it delivers.** `ManualSyncTrigger`, a worker `@Bean`: validate → lock → create batch → run
the existing orchestrator. Plus `SyncRefusedException`, `OnboardingConfigurationReader` (the
readiness assembly extracted from the registry service so one implementation serves both runtimes),
two repository finders, two bean methods. **No migration**, no new status, no new enum value, no
frontend.

```
MANUAL REQUEST -> sync_enabled? -> readiness? -> lock -> fin_sync_batch (PENDING, MANUAL)
                                                            |
                                    FinancePipelineOrchestrator (existing, unchanged)
                                                            |
                    extract -> validate -> map -> normalize -> load -> reconcile -> aggregate
                                                            |
                                        JdbcTableConnector -> SOURCE DATABASE
```

**Manual, and only manual** (FIN-D-094). No `@Scheduled`, no cron, no polling, no scan of
`sync_enabled`. `SchedulingConfig` is untouched and `financeSyncScheduler` is given no job.
Activation is still a permission, not an action.

**Proven end to end.** `ManualSyncTriggerE2ETest`: a synthetic H2 receipts table read by the real
`JdbcTableConnector` through the real settings and credential providers, into a real MySQL 8.0
registry built by the real migrations — three receipts becoming three canonical facts totalling
₹2,250.00, reconciled against the source's own total and published as a period aggregate. Everything
except the temple is production code. **No temple database was contacted; Q4 and Q5 are unresolved.**

Also proven: a failed run does not become the position the next one resumes from (FIN-D-005), a
disabled source and a blocked readiness verdict are refused with no batch written (FIN-D-098), and
two racing requests produce exactly one run (FIN-D-096).

Decisions FIN-D-094…098. Verified by 13 E2E tests; finance regression green.

**What it does not do:** no scheduler, no operator-facing entry point (FIN-D-095), no retry
processing, no stale-batch reaper, no Kollur.

## Phase 6 — Reconciliation

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-060 | Reconciliation service: completeness, consistency, and honest deletion handling | **COMPLETE** | FIN-056, FIN-057 |
| FIN-061 | Publication gate — a FAILED or unverified result blocks publication | **COMPLETE** | FIN-060 |

### FIN-060 — Pre-implementation investigation

Verified by reading source and migrations, not documentation.

**Already exists, and is reused rather than re-created:**

| Thing | Where | State |
|---|---|---|
| `fin_reconciliation_result` | `V110` | table exists, entity + repository exist, **nothing writes it** |
| `ReconciliationStatus` | enum | `PASSED`, `FAILED`, `NOT_AVAILABLE` |
| `SyncStatus.RECONCILE_FAILED` | enum | exists, unused |
| `SyncStage.RECONCILE` | enum | exists, unused |
| `PeriodType` | enum | `DAY`, `MONTH`, `FINANCIAL_YEAR`, `FULL_HISTORY` |
| `ReconMetric` | connector | `RECORD_COUNT`, `GROSS_AMOUNT`, `CANCELLED_COUNT`, `CANCELLED_AMOUNT`, `QUANTITY` |
| `SourceTotals` | connector | absence means *not available*, never zero |
| `TempleFinanceConnector.sourceTotals(capability, source, DateRange)` | contract | declared; **no production implementation exists** |

**No new table, no new status enum, and no new lifecycle vocabulary is required.** The one
schema change needed is a unique constraint for idempotency — `fin_reconciliation_result` has
none today.

### The boundary, stated before any check is written

| Question | Can the platform answer it? |
|---|---|
| **A. Extraction completeness** — did the connector get every source record in scope? | **Only if the source answers `sourceTotals`.** No production connector implements it, so today this is `NOT_AVAILABLE`, not a pass |
| **B. Processing completeness** — did every extracted row reach a terminal state? | **Yes, authoritatively.** Staging statuses and the batch counters are all local and all derived |
| **C. Canonical consistency** — do the facts correspond to the rows that produced them? | **By batch, yes. By record, no** — a grouped fact deliberately carries no `source_record_ref` (FIN-D-040) |
| **D. Source deletion detection** | **No, and it must say so.** See below |

### FIN-D-044, examined

1. **Extraction is incremental**, along a change axis (`SyncContext.changedSince`), not a
   business-date snapshot. ADR-006.
2. **Stable identifiers exist** — `fin_stg_revenue.source_record_ref`, unique within a batch.
3. **No deletion marker exists** anywhere in the contract. `RawRow` carries values, not tombstones.
4. **`extract()` never returns an authoritative period snapshot.** It returns what changed.
5. **The watermark does not support period comparison** — and is not even advanced yet
   (FIN-D-049).
6. **The source *can* be asked for a bounded business-date range** — but only through
   `sourceTotals(capability, source, DateRange)`, which returns *totals*, not records.
7. **Prior source-record identities are retained** in `fin_stg_revenue`, subject to Q7 retention.
8. **A deletion cannot be distinguished** from extraction failure, network failure, a partial
   source response, a source filter error, late-arriving data, or a source-side correction —
   every one of them produces the same observable: fewer records than before.

**Conclusion.** With only totals and no record-level snapshot, a shortfall is *evidence worth
recording*, never proof. FIN-060 will record a suspicion and will **never delete a canonical
fact**. Confirmed deletion detection requires either a deletion signal or a record-level
authoritative snapshot from the connector; neither exists, and inventing one is out of scope.

### Checks to implement

| # | Check | Authority | Input | Key |
|---|---|---|---|---|
| 1 | Stage-to-stage completeness | **authoritative** | `fin_stg_revenue` statuses vs batch counters | batch id |
| 2 | Rejected-row accounting | **authoritative** | `rows_rejected` vs `fin_sync_error` at each stage | batch id |
| 3 | Canonical count vs source | advisory | `sourceTotals(RECORD_COUNT)` vs facts in period | period |
| 4 | Canonical gross vs source | advisory | `sourceTotals(GROSS_AMOUNT)` vs `SUM(gross_amount)` | period |
| 5 | Suspected source deletion | **advisory, never destructive** | prior canonical count vs current source count, same closed period | period |

Checks 3–5 record `NOT_AVAILABLE` with a reason when the connector cannot answer. **Absence is
never a pass** (ADR-007).

### Idempotency

Unique on `(sync_batch_id, capability, metric, period_type, period_key)`.

`sync_batch_id` is nullable, and NULL ≠ NULL in a unique index — which is *wanted* here, and is
the opposite of the problem FIN-D-018 had to engineer around. A batch-scoped result deduplicates
on re-run; a scheduled re-verification (null batch) appends, which is what an append-only audit
history of a period needs.

### Out of scope, deliberately

No publication gate (FIN-061), no aggregation, no API, no scheduler, no alerting, no canonical
deletion or restatement of any kind, and no connector implementation.

### Delivered

`RevenueReconciliationStage` + `ReconciliationCheckType` + `V116`, wired as the orchestrator's
last stage and registered as one explicit `@Bean` (FIN-D-008). No new table and no new lifecycle
enum: `fin_reconciliation_result`, `ReconciliationStatus`, `SyncStatus.RECONCILE_FAILED` and
`SyncStage.RECONCILE` all existed and were all unused.

All four planned checks are implemented. Two are authoritative today; two record `NOT_AVAILABLE`
until a connector implements `sourceTotals()`, which is the honest answer rather than a gap.

Verified by `RevenueReconciliationStageTest`, 26 tests on MySQL 8.0, and eight mutations all KILLED
with fresh evidence (FIN-D-027). Finance regression 370 green; full suite 1,200 with 0 failures and
the 18 pre-existing FIN-X-001 errors. TiDB untested. Decisions FIN-D-050…055.

Three things the plan did not anticipate, all found by tests: a row rejected at normalization keeps
its `VALID` status and would otherwise have counted as lost (FIN-D-055); `uk_frf_grain` does not
include `source_system_id`, so two sources writing one grain overwrite each other (limitation 47);
and making `check_type` non-null broke two pre-existing H2 tests, which is recorded in HANDOFF.md
rather than hidden -- along with the fact that the constraint had no test until this task added one.

---

### FIN-061 — Scope, confirmed against the plan

The plan's one line was *"Publication gate — a FAILED result blocks aggregate publication"*, and
FIN-072 depends on it. Reading the code found the tension that shapes this task:

**There is nothing to gate yet.** FIN-070/071 (the aggregates) and Phase 8 (the APIs) are both
`NOT_STARTED`, so no code publishes anything. Two readings were possible: defer FIN-061 until
there is a consumer, or build the decision now so the consumer is written against it. The plan
chose the second — FIN-072 lists FIN-061 as a dependency, not the reverse — and building the rule
first is also what stops each future consumer inventing its own.

So FIN-061 delivers **the decision, not an enforcement point**: a deterministic, tested answer to
"may this temple's figures for this financial year be published?", plus a guard that throws. The
callers are FIN-070/072 and the Phase 8 APIs. That boundary is recorded as limitation 52 rather
than implied.

**In scope:** the decision rule, its four outcomes, batch-and-period scoping, the guard.
**Out of scope, deliberately:** aggregates, APIs, dashboard, scheduler, alerting, override
(FIN-D-060), and any canonical restatement. Nothing in this task deletes or rewrites a fact.

### Delivered

`ReconciliationGate` in a new `service.finance.publication` package — a plain `@Service` available
to both runtimes (FIN-D-061), plus `ReconciliationStatus.PENDING` and two repository queries. **No
migration and no new table:** the decision is derived from evidence FIN-060 already persists
(FIN-D-057).

Verified by `ReconciliationGateTest`, 16 tests on MySQL 8.0.

The finding that shaped the rule: a period is not trustworthy merely because its own totals agree.
A batch that lost rows taints every period it fed, and a batch that loaded facts and then died
before reconciliation leaves figures nothing has verified — the `PENDING` case, which a naive
"nothing failed, so publish" gate waves through (FIN-D-059).

---

## Phase 7 — Aggregations

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-070 | `fin_agg_revenue_period` | **COMPLETE** | FIN-056 |
| FIN-071 | `fin_agg_revenue_service` | **BLOCKED** — no fact carries a `service_id` (FIN-D-069, limitation 73) | service resolution, not yet a task |
| FIN-072 | Deterministic rebuild of affected periods only | **COMPLETE** for period aggregates; service aggregates wait on FIN-071 | FIN-070, FIN-061 |
| FIN-070A | Aggregation architecture and implementation plan | **COMPLETE** | FIN-056, FIN-061 |
| FIN-070B | Aggregation decisions — D1, D3, D5, D8 resolved | **COMPLETE** | FIN-070A |

### FIN-070B — Aggregation decisions

Decisions in [FIN-070B_AGGREGATION_DECISIONS.md](FIN-070B_AGGREGATION_DECISIONS.md), which
supersedes FIN-070A §18 for D1, D3, D5 and D8. Decision document only: no Java, SQL or migration
was written, the fact model is unchanged, and FIN-070/071/072 remain `NOT_STARTED`.

**None of the four blocks FIN-070**, which is the substantive outcome — FIN-070A had listed three
of them as blockers, and closer source inspection shows none is.

**D1 accepted** (`source_system_id` joins `uk_frf_grain`) but on a corrected schedule. FIN-070A
argued it had to happen while the table was empty; widening a UNIQUE key cannot be violated by
existing rows, and `source_system_id` is already `NOT NULL` and populated, so the DDL is safe with
or without data. What expires is the recovery of facts already destroyed by a second source
overwriting the first — so the deadline is the **second source system**, not FIN-070. Own task.
`uk_ftc_temple_capability` and `uk_fsd_temple_service` carry the same assumption and are deferred:
neither is a mechanical widening, because both force an unanswered question about what a
temple-level answer means when two sources disagree (new decision D9).

**D3 resolved as Option A** — facts immutable, mapping changes affect future extraction only, no
aggregation path corrects history. Option B is rejected on a schema fact: a grouped fact carries
`source_record_ref = NULL` by design (FIN-D-040) and the fact-to-staged-row link is never
persisted, so a snapshot policy cannot say which facts a source record fed. Option D is rejected as
unenforceable and harmful — it would strand `UNMAPPED` revenue permanently. Option C is the
approved successor with five prerequisites, none of which exists.

The correction that matters for whoever builds it: a remap needs **no source access**.
`uk_fsrm_row_type` exists so a re-run after a rule correction updates the decision rather than
duplicating it, and staging retains `raw_json`. The missing piece was never re-extraction; it is a
fact retirement path.

**D5 resolved**: `category_id` is `NOT NULL` and part of the aggregate unique key, totals computed
at read time. The documented "nullable, null = all" design would reproduce FIN-D-018's defect one
layer up — MySQL and TiDB do not constrain a NULL in a unique key, so every run would insert
another total row into a table whose defining property is idempotency.

**D8: accept ADR-011 with three amendments**, though the status change is the architect's act and
this task did not edit the ADR. All **eleven** finance ADRs are `Proposed`, so ADR-011's status is
not an anomaly and accepting one of eleven needs the governance question answered too (new decision
D10). The amendments: lead with publication gating rather than unmeasured performance, replace
`sync_batch_id` on an aggregate row with a contributing-batch reference, and state what a blocked
period looks like in the table.


### FIN-070 — Aggregation foundation

**COMPLETE, verified against MySQL 8.0.** Decision FIN-D-068.

**Migration `V119__finance_revenue_period_aggregate.sql`** creates `fin_agg_revenue_period` at the
grain FIN-070B decided:

```
uk_farp_grain (temple_id, source_system_id, period_type, period_key, category_id, payment_mode)
```

Every column `NOT NULL`, so no generated stand-ins are needed. `period_type` is `FINANCIAL_YEAR` or
`MONTH` — no `DAY`, because no catalogued report reads one. Totals are a `SUM` over category rows at
read time; there is deliberately no nullable-category "all" row, because MySQL and TiDB do not
constrain a NULL in a unique index and every run would insert another total (FIN-D-018 one layer up).

**Four classes, and the split between them is the design.** `RevenueAggregator` is pure — no Spring,
no repository, no clock — so every rule that could silently produce a wrong figure is testable in
milliseconds. `RevenueAggregate` and `RevenueAggregateKey` are value objects. `AggregationPeriod`
delegates the financial year to the existing `FinancialYear` rather than deciding again when a year
starts. `RevenueAggregationWriter` is the only thing that writes, and it **requires** a publishable
`ReconciliationGate.Decision` without ever calling the gate: deciding when to recompute is FIN-072's,
but existing in this table is what publication means, so a writer callable without a verdict is one
forgotten call away from publishing figures reconciliation does not stand behind.

**82 tests, 0 failures, 0 skipped** — 66 needing no database, 16 against MySQL 8.0 Testcontainers
with the real migrations. The database assertions read `information_schema` directly rather than
trusting a mock: the unique key's six columns in order with `non_unique = 0`, every grain column
`NOT NULL`, `net_amount` as the only generated column, both indexes, and `decimal` money types.

**All five required mutations applied and killed**, each reverted and diffed byte-identical
afterwards (FIN-D-027). The two that needed a database are the two that matter most: an accumulating
upsert (which satisfies the unique key perfectly and doubles revenue on every rebuild) and a
`uk_farp_grain` without `source_system_id`.

**What FIN-070 does not do:** no rebuild orchestration, no trigger, no scheduler, no reporting API,
no controller, no historical restatement, no `fin_agg_run` table, no `availability` column. It
changes no canonical fact, and a test on a real database asserts that.

**Not measured:** TiDB, and any performance figure at all.

---
### FIN-070A — Aggregation plan

Analysis in [FIN-070A_AGGREGATION_PLAN.md](FIN-070A_AGGREGATION_PLAN.md). Design only: no Java, SQL
or migration was written, and FIN-070/071/072 remain `NOT_STARTED`.

The plan confirms ADR-011's aggregate tables, but on the **publication-gating** argument rather
than the performance one — a query-time total always reflects the newest facts, including facts
`ReconciliationGate` says must not be published, and no performance figure for this platform has
ever been measured. Recommended grain is
`(temple, source system, period type, period key, category, payment mode)`, which is what makes
`requirePublishable(temple, source, financialYear)` applicable to an aggregate row at all.

Three things the plan found that must be decided before FIN-070 is written:

`uk_frf_grain` still omits `source_system_id` (limitation 47), and `fin_temple_capability` carries
the same single-source assumption in `uk_ftc_temple_capability` — so aggregating *by* source would
produce confident, wrong attribution. The fact table is empty in production, which makes this the
cheapest moment the grain will ever be fixable (decision D1).

Reconciliation writes results only at `FULL_HISTORY` and `FINANCIAL_YEAR` scope, so **monthly
aggregates cannot be gated on their own evidence** and must inherit the parent year's verdict,
recorded on the row as inherited rather than measured.

And a new finding, not previously recorded: **re-extraction after a mapping change orphans a fact
and double-counts the money.** `category_id` is part of the fact grain, the upsert has no delete
path, and nothing else deletes facts — so a value remapped from `UNMAPPED` to `SEVA` produces a
second row and leaves the first. Reachable now that FIN-054B has shipped the editing screen
(decision D3, risk R2).

---

## Phase 8 — Finance APIs

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-080 | Response DTOs carrying the availability envelope | **COMPLETE** | FIN-001 |
| FIN-081 | `DcFinanceController` — summary, trend, monthly, categories | **COMPLETE** except `sevas` (blocked on FIN-071, FIN-D-069) | FIN-072, FIN-080 |
| FIN-082 | Capabilities endpoint | **COMPLETE** | FIN-080 |
| FIN-083 | Reconciliation endpoint | **COMPLETE** | FIN-060 |
| FIN-084 | RBAC wiring, reusing existing infrastructure | **COMPLETE** — `CAN_READ_FINANCE_CONFIG` route guard + `JurisdictionGuard` district scope (FIN-D-071); DACVM field-level filtering not wired, see limitation below | FIN-081 |
| FIN-054A-BE | Source Mapper **backend** — mapping administration API | **COMPLETE** | FIN-054 |
| FIN-054A-FE / FIN-054B | Source Mapper **screen** — the administrative UI itself | **COMPLETE** | FIN-054A-BE |

### FIN-054A — Source Mapper

Analysis in [FIN-054A_SOURCE_MAPPER_SCREEN_PLAN.md](FIN-054A_SOURCE_MAPPER_SCREEN_PLAN.md).
Split into **FIN-054A-BE** (backend) and **FIN-054B** (screen), both complete, because
the backend is not a step towards the screen so much as the whole missing layer beneath it: the
blocking dependency was never the mapping engine, which was already complete, but the absence of
any finance API at all.

**FIN-054A-BE delivers** nine endpoints under `/api/v1/finance` — see
[API_CONTRACT.md §7](API_CONTRACT.md) — with authorization on the service implementation,
server-side resolution of `sourceSystemId` to its temple and district, same-transaction audit,
optimistic locking (V117), an allow-listed sort, and source-value format validation shared with the
mapping engine.

Decisions: FIN-D-062 (namespace validation), FIN-D-063 (audit in the caller's transaction),
FIN-D-064 (optimistic locking), FIN-D-065 (writable mapping type, newest-batch counts),
FIN-D-066 (a saved rule does not correct published figures).

Of the plan's seven open decisions, five were resolved from existing requirements and the task's
own conservative principles (D1, D2, D3, D5, D7); **D4** (a re-run trigger) and **D6** (editing
source-of-truth declarations) remain deliberately out of scope and unimplemented.

**FIN-054B delivers the screen.** Route `/finance/source-mapper`, guarded by `RoleRoute` for
SUPER_ADMIN, DISTRICT_COLLECTOR, DC_STAFF and AUDITOR — the same four roles as the backend read
expression, with write controls gated separately on `CAN_ACT_DC`. Feature module at
`frontend/src/features/finance/`.

The screen offers **semantic mapping only**. There is no structural source-to-staging UI, no SQL,
no re-run trigger and no history tab, because the architecture supports none of them (ADR-004) and
offering one would promise something the platform does not do. Every write path states, at the
point of edit, that published figures keep their classification until their batch is processed
again.

---

## Phase 9 — Dynamic Dashboard

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-090 | Finance API client in the frontend | **COMPLETE** | FIN-081 |
| FIN-091 | Replace the static iframe with a real component | **COMPLETE** | FIN-090 |
| FIN-092 | `AvailabilityNotice` component — renders a reason, never a zero | **COMPLETE** | FIN-090 |
| FIN-093 | Delete hardcoded constants and the temple-300001 gate | **COMPLETE** | FIN-091 |

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
| FIN-140 | Generic multi-temple onboarding | **IN_PROGRESS** — slice 140-A COMPLETE, see Phase 13 below |
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
| ~~FIN-031~~ | Connector registry resolving `connector_bean` to a bean | **COMPLETE** | Delivered; Kollur's configured connector now fails resolution explicitly |
| **FIN-051 / FIN-052** | Canonical dimensions and `fin_revenue_fact` at daily grain | **RECOMMENDED NEXT** | The only substantial work needing neither Q4 nor a connector: FIN-051 depends on FIN-011 alone, and the fact table's grain and unique constraint are what make loading idempotent. Every later stage — validation, mapping, aggregation, reconciliation — writes into these tables, so their shape should be settled before a connector starts producing rows |
| FIN-032 | Probe and capability wiring into onboarding | AVAILABLE | Smaller, but it validates configuration against connectors that do not exist yet, so it can only be exercised against fakes until FIN-040 |

Unblocked by Q4 and Q5.

**One item deliberately deferred, and worth a decision.** A source-of-truth declaration for
`PRECIOUS_METAL_WEIGHT` (`HKanikeItems.Qty`, grams) was **not** seeded, because FIN-023 was
scoped to `REVENUE_AMOUNT`. It is the natural defence against the discarded
"assume 15 g and ₹12,000 per item" approach returning, and is a one-row addition whenever the
reviewer wants it.

---

## Phase 13 — Generic Multi-Temple Onboarding (FIN-140)

Plan in [FIN-140_ONBOARDING_PLAN.md](FIN-140_ONBOARDING_PLAN.md). FIN-032 is analysed inside that
plan rather than as a separate task, and its connector-dependent half is deferred — see below.

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-140-A | Source system registration, metadata configuration, registry-side readiness validation | **COMPLETE** | FIN-054A-BE |
| FIN-140-B | Capability declaration API and screen | **COMPLETE** | FIN-140-A |
| FIN-140-C | Source-of-truth declaration API and screen | **COMPLETE** | FIN-140-B |
| FIN-140-D | Activation — switch `sync_enabled` on, gated on readiness | **COMPLETE** | FIN-140-C |
| FIN-140-E | Full Temple B onboarding, end to end | NOT_STARTED | FIN-140-D |
| FIN-032 / FIN-140-F | Worker-side probe: reachability and schema drift | **DEFERRED** — needs a connector to probe with | FIN-040/041 (Q4) |

### FIN-140-A — Delivered

**The finding that shaped the whole task, established by reading the runtime boundary rather than
assuming it: the probe cannot be a button.** A `TempleFinanceConnector` exists only in the
sync-worker runtime; `RegistryRuntimeContextTest` asserts the registry holds no bean from
`com.templeregistry.connector.**` at all; the worker refuses to boot as a web application; and the
only channel between the two runtimes is the shared database. A synchronous connectivity check is
not a feature that was skipped — it is one the process serving HTTP cannot perform.

So FIN-032 splits. **Class A** — configuration coherence — needs no connector, reads only registry
tables and ships here. **Class B** — reachability and schema drift — needs the worker, and is
deferred rather than sequenced: with zero connector implementations in existence, every Class B
check would return the same answer for every source, and building transport for a constant is
infrastructure without a product.

**Delivered:** one migration, one pure validator, one service, one controller, four DTOs, and a
frontend feature module. No connector package touched, no worker touched, no ADR status changed.

| Piece | What |
|---|---|
| `V120__finance_source_system_version.sql` | One additive column — `version INT NOT NULL DEFAULT 0` on `fin_source_system`. Same reasoning as V117 applied to mapping rules: two administrators can load the same row and save different connector beans, and without a lock the second silently wins |
| `OnboardingReadinessValidator` | Pure, static, no Spring. 13 checks over a value record |
| `SourceSystemAdminService(Impl)` | Register, read, update, readiness. `ADMIN_ONLY` throughout (FIN-D-076) |
| `FinanceOnboardingController` | Four endpoints under `/api/v1/finance`. No activation endpoint, no probe endpoint, no delete |
| `frontend/src/features/finance-onboarding/` | Register form and readiness panel, `/finance/source-systems`, SUPER_ADMIN route |

**The checks that earn the task.** `MAPPING_CANONICAL_UNKNOWN` and `MAPPING_RULE_MALFORMED` are
failures the mapping engine already detects — as `INVALID_CONFIGURATION`, and as a rule that shows
Active while never matching anything — per row, during a run, long after whoever wrote the rule has
gone. Detecting them here costs one set lookup and moves the discovery to while the configuration is
still a draft. `SOURCE_OF_TRUTH_MISSING` reads its required-metric list from `RevenueField` rather
than restating it, because the pipeline is the authority on what it cannot run without.

**Required declarations are only demanded when revenue is reportable.** A source that declares
`REVENUE` as `NOT_AVAILABLE` — Temple B's expenses-only variant — needs no revenue declarations and
no mapping rules, and demanding them would have made such a source impossible to onboard. That is
the single most important genericity property in the validator and it has its own test.

**A defect found by the tests, not by reasoning.** `uk_fss_temple_system` is
`(temple_id, system_code)` and does **not** include `is_deleted`, so a soft-deleted source still
holds its code. The first duplicate check filtered deleted rows out, passed, and let the insert hit
the constraint — surfacing as a 500 rather than a refusal a caller could act on. The check now
matches the constraint, and says so when the row in the way is retired.

**Decisions:** FIN-D-073 (readiness computed, never stored), FIN-D-074 (one source per temple; D9
stays open), FIN-D-075 (credential alias write-only, absent from the response type), FIN-D-076
(SUPER_ADMIN only), FIN-D-077 (three readiness values, no NOT_READY), FIN-D-078 (possible ambiguity
warns, never blocks).

**Out of scope, deliberately:** no activation endpoint — readiness reports whether activation would
be permitted and slice 140-A ends at the check; no probe; no capability or source-of-truth write
API; no delete; no connector; no worker change; no re-application of the Kollur seed.

**Verified:** `OnboardingReadinessValidatorTest` 23 (no database), `SourceSystemAdminServiceTest` 26
and `SourceSystemAdminSecurityTest` 14 (MySQL 8.0 Testcontainers, full context), plus 30 frontend
tests. Finance regression **634 run, 0 failures, 0 errors, 0 skipped**. Worker-boundary tests
(`RegistryRuntimeContextTest`, `SyncWorkerRuntimeContextTest`, `SyncWorkerProfileBoundaryTest`,
`FinanceIntegrationBoundaryTest`) **23 run, 0 failures** — ADR-001 intact. TiDB not verified.

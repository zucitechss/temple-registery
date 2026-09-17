# Finance Implementation Tasks

**Updated:** 2026-09-17 (FIN-054)
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
| FIN-050 | `fin_stg_revenue` staging table and entity | **COMPLETE** | FIN-011 (dependency on FIN-043 was not real: the table needs no extract) |
| FIN-051 | Dimensions: `fin_revenue_category`, `fin_service_dim` | **COMPLETE** | FIN-011 |
| FIN-052 | `fin_revenue_fact` (daily grain) | **COMPLETE** | FIN-051 |
| FIN-053 | Validation stage, rejections to `fin_sync_error` | **COMPLETE** | FIN-050 |
| FIN-054 | Mapping stage, unmapped values routed to `UNMAPPED` | **COMPLETE** | FIN-024, FIN-053 |
| FIN-055 | Normalization to daily grain | **COMPLETE** | FIN-054 |
| FIN-056 | Idempotent load keyed on the grain unique constraint | NOT_STARTED | FIN-052, FIN-055 |



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
| ~~FIN-031~~ | Connector registry resolving `connector_bean` to a bean | **COMPLETE** | Delivered; Kollur's configured connector now fails resolution explicitly |
| **FIN-051 / FIN-052** | Canonical dimensions and `fin_revenue_fact` at daily grain | **RECOMMENDED NEXT** | The only substantial work needing neither Q4 nor a connector: FIN-051 depends on FIN-011 alone, and the fact table's grain and unique constraint are what make loading idempotent. Every later stage — validation, mapping, aggregation, reconciliation — writes into these tables, so their shape should be settled before a connector starts producing rows |
| FIN-032 | Probe and capability wiring into onboarding | AVAILABLE | Smaller, but it validates configuration against connectors that do not exist yet, so it can only be exercised against fakes until FIN-040 |

Unblocked by Q4 and Q5.

**One item deliberately deferred, and worth a decision.** A source-of-truth declaration for
`PRECIOUS_METAL_WEIGHT` (`HKanikeItems.Qty`, grams) was **not** seeded, because FIN-023 was
scoped to `REVENUE_AMOUNT`. It is the natural defence against the discarded
"assume 15 g and ₹12,000 per item" approach returning, and is a one-row addition whenever the
reviewer wants it.

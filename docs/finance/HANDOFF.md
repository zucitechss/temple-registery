# Finance Platform — Handoff

**Updated:** 2026-09-18 (FIN-054A-BE)
**Branch:** `feature/db-integration`
**Read first**, then [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md),
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Task Status

| Task | Status |
|---|---|
| FIN-054A-BE — Source mapping administration API (the screen is FIN-054A-FE, not started) | **COMPLETE** |
| FIN-061 — Publication gate (the decision; its callers are FIN-070/072) | **COMPLETE** |
| FIN-060 — Reconciliation (completeness now; source agreement when a connector exists) | **COMPLETE** |
| FIN-057 — Pipeline orchestrator (and the extraction stage nobody owned) | **COMPLETE** |
| FIN-056 — Idempotent load | **COMPLETE** |
| FIN-055 — Revenue normalization | **COMPLETE** |
| FIN-054 — Revenue mapping | **COMPLETE** |
| FIN-053 — Staging validation | **COMPLETE** (repaired; see FIN-D-026 and FIN-D-027) |
| FIN-050 — `fin_stg_revenue` staging | **COMPLETE** |
| FIN-051 — Canonical dimensions | **COMPLETE** |
| FIN-052 — `fin_revenue_fact`, daily grain | **COMPLETE** |
| FIN-031 — Connector registry | **COMPLETE** |
| FIN-030 — Connector contract | **COMPLETE** |
| FIN-021…024 — Kollur configuration | **COMPLETE** |
| FIN-016 — Registry / sync-worker split | **COMPLETE** |
| FIN-010…015 — Finance foundation | **COMPLETE** |

Each is complete because the invariants it exists to create were observed holding against a
real database, and observed failing when the constraint that creates them is removed.

**FIN-053 was marked complete once before it was.** An audit found that its loop could not
terminate, that the test written to prove otherwise was failing, and that three of its four
claimed mutation results had never been produced. Both defects are fixed and all five mutations
are now measured — but the more useful lesson is in FIN-D-027: the harness had been reporting a
leftover report as a result, so the documentation was confident, specific and wrong. Treat a
mutation table as evidence only if the run that produced it can be shown to have happened.

---

## Current State

**What works.** The finance foundation (FIN-010…015), the registry / sync-worker runtime
boundary (FIN-016), the generic connector contract (FIN-030), connector resolution (FIN-031),
the complete Kollur configuration (FIN-021…024), the canonical revenue model (FIN-051,
FIN-052), the staging table that feeds it (FIN-050), and now every stage between them:
extraction (FIN-057), validation (FIN-053), mapping (FIN-054), normalization (FIN-055), the load
(FIN-056), reconciliation (FIN-060) and the publication gate (FIN-061) — with an orchestrator (FIN-057) that runs one
`fin_sync_batch` through all of them and leaves it `SUCCESS`, `RECONCILE_FAILED` or `FAILED`,
never `RUNNING`.

The pipeline is end to end. A connector's rows land in staging without being interpreted; a
staged record is judged and its rejection explained row by row; a validated record has its
source values translated from configuration alone; the translation becomes a dated, canonical
figure; and the figure is written to `fin_revenue_fact` through a grain constraint that makes a
replay converge rather than double. The batch is then checked for completeness, with every
answer -- including the ones that could not be given -- recorded in `fin_reconciliation_result`;
and a gate turns those answers into a deterministic verdict on whether the period may be published.

**What does not work yet.** No connector implementation — `RevenueExtractionStage` drains
whatever connector a source names, but the only one that exists is a synthetic test fixture, so
**no row of real temple financial data has ever been read** and `fin_revenue_fact` is empty
outside tests. No credential exists anywhere -- and because reconciliation against a source needs
a connector too, the one check that could catch a wrong extraction is `NOT_AVAILABLE` everywhere
(limitation 44). The publication gate now decides correctly and **nothing calls it** (limitation
52). Beyond that: no aggregation (FIN-070/071), **no reporting API**, no scheduler, no retry driver,
and no watermark advancement (FIN-D-049). The dashboard is still the static HTML file.

The one API that now exists is administrative, not reporting: FIN-054A-BE configures how a source
system's values translate to canonical categories, and reads no temple financial figure. It has no
screen (limitation 55).

---

## FIN-054A-BE — Source Mapping Administration API

The finance pipeline's **first HTTP surface**, and therefore the first place finance authorization
exists at all. Everything from FIN-050 to FIN-061 is invoked by a worker or a test.

### Files

| File | Change |
|---|---|
| `service/finance/pipeline/SourceValueKey.java` | new — one definition of a valid `source_value`, shared with the engine |
| `service/finance/pipeline/MappingRuleResolver.java` | now parses through `SourceValueKey`; behaviour unchanged |
| `service/finance/mapping/MappingAdminService.java` | new — the contract |
| `service/impl/finance/MappingAdminServiceImpl.java` | new — authorization, scope, validation, audit |
| `controller/finance/FinanceMappingController.java` | new — 9 endpoints, thin |
| `dto/request/finance/` (3), `dto/response/finance/` (5) | new |
| `entity/finance/FinMappingRule.java` | `@Version` |
| `db/migration/V117__finance_mapping_rule_version.sql` | new — `version INT NOT NULL DEFAULT 0` |
| `security/RoleConstants.java` | `CAN_READ_FINANCE_CONFIG` |
| `repository/finance/` (4) | paged search, conflict lookup, latest-batch, field summary, payload sample, `findByDeletedFalse` |
| `test/.../mapping/MappingAdminServiceTest.java` | new — 28 tests |
| `test/.../mapping/MappingAdminSecurityTest.java` | new — 19 tests |
| `test/.../mapping/FinanceApiTestBase.java`, `MappingAdminTestFixture.java` | new — shared container and seeding |

### The three things worth knowing

**1. `AuditService` was not reused, and that is the point.** It writes the right table but it is
`@Async`, runs `REQUIRES_NEW`, and catches its own failures — correct for a declaration, wrong
here. A change to how a temple's revenue is classified must not survive the loss of the record of
who made it. The service writes `AuditDataEvent` through its repository in the caller's
transaction, with no `try`/`catch` (FIN-D-063), and a test makes the audit insert fail on its own
to prove the rule change goes with it.

**2. Scope is resolved from the server's own data, and refused as 404.** A caller names a
`sourceSystemId` and nothing else; the temple, and through it the district, is looked up here. A
source system outside the caller's district is "not found", not "forbidden" — a distinguishable
refusal enumerates every temple's integrations one id at a time.

`JurisdictionGuard.assertDistrictScope` is reused for the traversal, but **only for
`DISTRICT_COLLECTOR` and `DC_STAFF`**. It treats a null `districtId` on any other role as a
corrupted token, and an `AUDITOR` is statewide and legitimately carries none. A test asserts that
an auditor with no district claim is not treated as corrupted.

**3. One definition of what can fire.** `SourceValueKey` is used by the API that validates a rule
before saving it and by the engine that later matches it. Two copies would have been the real bug:
an API that accepts what the engine rejects produces a rule that is saved, shows as active, and
silently never matches — the exact failure this feature exists to prevent (FIN-D-062). A test
creates a rule through the API and then resolves it through `MappingRuleResolver`.

### What it refuses, and what it only warns about

| Refused (422) | Warned about |
|---|---|
| a `source_value` naming no field | a namespace no staged payload has been observed to carry |
| a field name containing the separator | |
| a canonical value that is not a live category | |
| creating or editing any type but `REVENUE_CATEGORY` | |
| a `sort` key outside the allow-list | |

The warning is not a refusal because **there is no registry of the fields a source emits** — the
connector chooses them, and the only evidence is staged payloads. Refusing on that evidence would
make a source impossible to configure before its first extraction and would start refusing correct
namespaces the moment staging is purged (Q7 unresolved). See FIN-D-062 and limitation 58.

### What it does not touch

No canonical fact, no staged row, no staged decision, no pipeline run. Two tests assert directly
that `fin_revenue_fact` is byte-identical after a successful edit and after a refused one, and a
third that an existing `UNMAPPED` decision still says `UNMAPPED` after the missing rule is created.
Every write response carries a fixed sentence saying published figures are unchanged until the
batch is re-run (FIN-D-066) — because there is no re-run trigger, and adding one was refused
(limitation 59).

### Concurrency

V117 adds `version`, and the service compares the submitted version against the loaded one
**before** saving. Hibernate's own check would not catch this feature's actual race: the entity
loaded inside the write transaction is fresh, so two administrators who opened the same list
minutes apart would both succeed and the earlier one's change would vanish silently (FIN-D-064).

### Tests

**47, all passing**, MySQL 8.0 Testcontainer with real migrations. The finance regression is
**408 run -- 0 failures -- 0 errors -- 0 skipped** (386 at FIN-061; the 22 added are these two
classes, and no prior suite changed). `MappingRuleResolverTest` 16/16 and `RevenueMappingStageTest`
22/22 are what make the resolver refactor safe to claim as behaviour-preserving.

Full suite: **1,263 run -- 0 failures -- 18 errors** (1,216 at FIN-061). **No new failure.** All 18
errors are FIN-X-001, unchanged in count, cause and location: `ApplicationContextIntegrationTest`
(1) and `TrustIntegrationTest` (1 + 5 + 11), every one of them
`missing column [field_names_json] in table [declaration_clarifications]`.

- `MappingAdminServiceTest` — 28. Composition and read-back through the real resolver; every
  refusal above; version increment and stale-version refusal; deactivation, including of an inert
  type; facts untouched after a successful and a refused edit; staged decisions untouched; audit
  rows written with actor and before/after; **audit failure rolling the change back**; paging,
  filtering, search, sort allow-list both directions; a malformed existing rule listed and flagged;
  unresolved values worst-first with the namespace to key on; newest batch only; no batch at all;
  the namespace catalogue and all three warning cases.
- `MappingAdminSecurityTest` — 19. Each of the four reading roles admitted and each of the two
  excluded roles refused, by invoking the service rather than inspecting an annotation; every write
  path refused for `DC_STAFF`, `AUDITOR`, `TEMPLE_AUTHORITY` and `VIEWER`; cross-district read,
  edit and create all 404 with the other district's rule verifiably unchanged; unresolved values,
  namespaces and single-rule reads scoped the same way; the source-system list filtered per
  district; a statewide role seeing both; an auditor with no district claim not failing.

`FinanceApiTestBase` does **not** extend `MySQLContainerBase`: that base sets `ddl-auto=validate`,
which still fails on FIN-X-001, so these tests use `ddl-auto=none` like every finance pipeline
suite. Consequence recorded as limitation 56. Its container is a started-once singleton rather
than a `@Container` field, because Spring caches one context across both classes while a
`@Container` field is stopped per class — the first attempt failed exactly that way.

### Decisions

FIN-D-062 … FIN-D-066.

---

## FIN-061 — Publication Gate

### Files

| File | Change |
|---|---|
| `service/finance/publication/ReconciliationGate.java` | new — the decision, and a guard that throws |
| `entity/finance/enums/ReconciliationStatus.java` | `PENDING`, derived only, never persisted |
| `repository/finance/FinRevenueFactRepository.java` | `findContributingBatchIds` |
| `repository/finance/FinReconciliationResultRepository.java` | two scoped lookups |
| `test/.../ReconciliationGateTest.java` | new — 16 tests |

**No migration, no new table, no new status column.**

### The scope question, answered before any code

The plan said *"a FAILED result blocks aggregate publication"*. **There are no aggregates**
(FIN-070/071 `NOT_STARTED`) and **no APIs** (Phase 8 `NOT_STARTED`), so nothing publishes anything
today. FIN-072 depends on FIN-061 rather than the reverse, so the rule is built first and its
consumers are written against it. FIN-061 therefore delivers **the decision, not an enforcement
point** — recorded as limitation 52, not implied.

### The policy

| Verdict | Publishes? | When |
|---|---|---|
| `PASSED` | yes | every check that ran agreed |
| `NOT_AVAILABLE` | yes, flagged | checks could not be made; what ran agreed |
| `FAILED` | **no** | a check found a real disagreement |
| `PENDING` | **no** | figures exist that nothing has verified |

These are the four values `API_CONTRACT.md` already documents for its `reconciliation` field, so no
vocabulary was invented (FIN-D-058). `NOT_AVAILABLE` publishes because no connector implements
`sourceTotals()` — blocking on it would publish nothing, ever, while withholding correctly loaded
figures; what is withheld is the *claim* of verification.

**Blocking means "keep the previous figures", not "fail".** Per the architecture, a blocked period
leaves the last good aggregates visible and marks the temple stale rather than wrong. The gate
withholds a replacement; it holds no repository that could delete or restate anything.

### Two scopes, because a period is not self-certifying

A period publishes only if its own checks pass **and** every batch that fed it passed its
batch-scoped checks. A batch that lost rows taints every period it contributed to, however well
that period's totals agree — they are summed from the rows that arrived, so they always agree with
themselves. A contributing batch that was never reconciled at all blocks the period outright; that
is the state a batch leaves when it loads facts and then dies (FIN-D-059).

Period verdicts supersede per question, so a corrected batch can clear an earlier variance. Batch
verdicts do not supersede — each batch's own completeness stands on its record.

### Authorization and override

**None, deliberately** (FIN-D-060). No requirement documents an override; the pipeline has no
authenticated entry point, since Phase 8 does not exist and RBAC is enforced at HTTP boundaries in
the registry runtime; and nothing is blocked yet that would need releasing. An override added now
would be reachable only from code with no principal attached. What it will need when it is real is
written down in FIN-D-060.

### Transactions, idempotency, concurrency

Read-only and derived. `evaluate` writes nothing, takes no lock, and returns the same verdict for
the same evidence however often it is called — asserted by a test that checks row counts are
unchanged after three evaluations. Two threads deciding at once reach the same verdict, because
neither mutates anything (FIN-D-057).

### Tests

16 in `ReconciliationGateTest`, MySQL 8.0 Testcontainer, real migrations: every check passed;
unavailable checks publishing flagged; a failed period check; an incomplete contributing batch
blocking a period whose totals agree; a suspected deletion blocking **while the facts stay
untouched**; a contributing batch never reconciled; nothing reconciled at all; no facts at all; a
later batch clearing an earlier variance; another source's failure not blocking this one; another
year's failure not blocking this one; the guard throwing; the guard returning; idempotence over
three calls; two threads agreeing; and the decision explaining itself in one line.

---

## FIN-060 — Reconciliation

### Files

| File | Change |
|---|---|
| `service/finance/pipeline/RevenueReconciliationStage.java` | new — four checks, one writer, no repairs |
| `entity/finance/enums/ReconciliationCheckType.java` | new — what a result compared, and how much it is worth |
| `db/migration/V116__finance_reconciliation_checks.sql` | `check_type`, `uk_frr_batch_check`, corrected column comments |
| `entity/finance/FinReconciliationResult.java` | `checkType`, the unique constraint |
| `repository/finance/FinReconciliationResultRepository.java` | idempotent `record()` upsert, scoped lookups |
| `repository/finance/FinRevenueFactRepository.java` | four source-scoped reconciliation queries |
| `service/finance/pipeline/FinancialYear.java` | `startOf`, `endOf`, `isClosed` — the year boundary stays in one place |
| `service/finance/pipeline/FinancePipelineOrchestrator.java` | `RECONCILE` as the last stage; `RECONCILE_FAILED` as an outcome |
| `service/finance/sync/SyncWorkerConfig.java` | one explicit `@Bean` (FIN-D-008) |
| `test/.../RevenueReconciliationStageTest.java` | new — 26 tests |

**No new table.** `fin_reconciliation_result` has existed since V110 and nothing ever wrote to it.
**No new status enum.** `ReconciliationStatus`, `SyncStatus.RECONCILE_FAILED` and
`SyncStage.RECONCILE` all existed and were all unused.

### The boundary, stated before the checks

| Question | Answer |
|---|---|
| **Extraction completeness** — did the connector get every source record? | **Only the source can say**, through `sourceTotals()`. No production connector implements it, so today: `NOT_AVAILABLE` |
| **Processing completeness** — did every extracted row reach a terminal state? | **Yes, authoritatively.** All local, all derived |
| **Canonical consistency** | **By batch and by period, yes. By record, no** — a grouped fact carries no `source_record_ref` (FIN-D-040) |
| **Source deletion detection** | **No.** See below |

### The checks

| Check | Authority | Compares | Fails when |
|---|---|---|---|
| `STAGE_COMPLETENESS` | authoritative | staged vs (loaded + rejected + normalization-rejected) | a row is neither in the figures nor explained |
| `REJECTION_ACCOUNTING` | authoritative | `REJECTED` rows vs `VALIDATE` errors | a row was dropped with no recorded reason |
| `SOURCE_VS_CENTRAL` | advisory today | source `GROSS_AMOUNT` / `RECORD_COUNT` vs canonical, per financial year | they differ at all — tolerance is exact |
| `SUSPECTED_SOURCE_DELETION` | advisory, never destructive | source record count vs canonical, closed years only | the source now holds fewer |

Matching keys: batch id for the local checks; (temple, source system, financial year) for the
source checks. Amounts compared with `BigDecimal.compareTo` at scale 2, percentages at scale 4,
`HALF_UP`, zero tolerance. Never `equals` — `100.00` and `100.0` are the same money.

### FIN-D-044, answered honestly

Extraction is incremental along a change axis; there is no deletion marker in `RawRow`; `extract()`
never returns an authoritative period snapshot; the watermark is not even advanced yet (FIN-D-049).
`sourceTotals()` *can* be asked for a bounded business-date range, which is why a count comparison
is possible at all — but a count is evidence, not proof.

So a closed-period shortfall is recorded as **suspected**, with the five other explanations named
in the row itself, and **nothing is deleted or overwritten** (FIN-D-052). An open-period shortfall
is `NOT_AVAILABLE`, not a pass: while a year is open, late data and deletion look identical.

A test asserts the canonical facts are unchanged, field by field, after a deletion suspicion. It is
the most important assertion in the class.

### Why a matching total is not enough

`RECORD_COUNT` is compared as well as `GROSS_AMOUNT`, and a test proves a count mismatch fails while
the money matches — three receipts missing and three double-counted give the same total. The
canonical side is `SUM(transaction_count)`, not `COUNT(*)`, because a fact is a daily grain that can
stand for thousands of receipts; and it is `NOT_AVAILABLE` if any contributing fact has a null count,
because a floor compared against a source count manufactures a shortfall (FIN-D-053).

### Idempotency, transactions, concurrency

`uk_frr_batch_check` makes a re-run replace its own answers; a null `sync_batch_id` appends instead,
which is what an append-only period history needs (FIN-D-051). Writes go through
`INSERT … ON DUPLICATE KEY UPDATE` with assignment, so two reconcilers racing converge rather than
colliding — no lock was added. `reconcileBatch` is not `@Transactional`; each finding persists in
its own `REQUIRES_NEW` transaction.

A batch that is `PENDING`, `FAILED`, `CANCELLED` or `DEAD_LETTER` is **refused by name** and no
result row is written — a failed batch reconciled as though complete is the false clean result this
task exists to prevent.

### Tests

26 in `RevenueReconciliationStageTest`, MySQL 8.0 Testcontainer, real migrations, real stages, with
a synthetic connector whose `sourceTotals()` returns what a test tells it. Covering: agreement;
amount mismatch with both totals preserved; count mismatch while money matches; scale-only
difference passing; one paisa failing; stuck rows; normalization rejections counted as accounted;
unexplained rejections; empty batch; closed-year shortfall suspected; **facts unchanged after a
suspicion**; open-year shortfall not suspected; no source totals; incomplete canonical count;
source ahead of canonical; four non-reconcilable batch states; unknown batch; two sources on one
temple; unregistered connector; a throwing source; idempotent re-run replacing its own row;
re-running leaving facts alone; a full pipeline run reconciling clean; a disagreeing source ending
`RECONCILE_FAILED` with figures intact; and a second run whose source omits a record.



### Completion evidence

Measured after `mvn clean`, with every Surefire report regenerated by the run that reports it.

| Suite | Tests | Failures | Errors | Skipped | Database |
|---|---:|---:|---:|---:|---|
| `RevenueReconciliationStageTest` | 26 | 0 | 0 | 0 | MySQL 8.0 |
| `FinanceFoundationRepositoryTest` | 12 | 0 | 0 | 0 | H2 |
| `FinancePipelineOrchestratorTest` (FIN-057) | 13 | 0 | 0 | 0 | MySQL 8.0 |
| `RevenueLoadStageTest` (FIN-056) | 18 | 0 | 0 | 0 | MySQL 8.0 |
| `RevenueNormalizationStageTest` (FIN-055) | 21 | 0 | 0 | 0 | MySQL 8.0 |
| `RevenueMappingStageTest` (FIN-054) | 22 | 0 | 0 | 0 | MySQL 8.0 |
| `RevenueStagingValidatorTest` (FIN-053) | 25 | 0 | 0 | 0 | MySQL 8.0 |
| **Finance regression** | **370** | **0** | **0** | **0** | mixed |
| **Full backend suite** | **1,200** | **0** | **18** | **0** | mixed |

Regression 13 m 13 s, full suite 15 m 53 s.

**The full suite is not green, and this does not claim it is.** All 18 errors are the FIN-X-001
baseline — `missing column [field_names_json] in table [declaration_clarifications]` under
`ddl-auto: validate` — in `ApplicationContextIntegrationTest` (1) and `TrustIntegrationTest` (17:
`TrustCrud` 11, `BoardMemberOperations` 5, `SecurityTests` 1). Same count, same classes and same
root cause as at FIN-055 and FIN-057. **Zero new failures and zero new errors.**

Test-count movement is accounted for: 1,199 → 1,200 is the one new constraint test; 369 → 370 in
the regression is the same test, which matches the `*Finance*` filter.
### The regression this task caused, and why it was not pre-existing

Making `FinReconciliationResult.checkType` non-null broke two tests in
`FinanceFoundationRepositoryTest` that build a result without one:
`should_storeNullTotal_when_reconciliationNotAvailable` and
`should_defaultToZeroTolerance_when_reconciliationRecorded`. Both failed with
`NULL not allowed for column "CHECK_TYPE"`.

**It was mine, and the evidence says so.** Both tests passed on the FIN-057 tree; the column they
tripped on was added by V116 in this task; and their failure names that column. Nothing about it
resembles FIN-X-001, whose signature is `missing column [field_names_json] in table
[declaration_clarifications]` under `ddl-auto: validate`.

**Corrected by giving each result a check type**, `SOURCE_VS_CENTRAL`, which is what both were
always recording. One builder line each; **no assertion was changed, relaxed or removed.**
`should_storeNullTotal_…` still asserts `NOT_AVAILABLE` with null source, central and difference
totals; `should_defaultToZeroTolerance_…` still asserts a zero tolerance. The alternative — giving
the entity a default check type — was rejected: a result that does not say what it compared is
exactly what FIN-D-050 exists to prevent, and a default would have made the column non-null in
name only.

**It surfaced on H2, not MySQL.** `FinanceFoundationRepositoryTest` is one of the few finance tests
not on Testcontainers (`[23502-232]` is an H2 error code). The MySQL-based FIN-060 tests could not
have caught it, because every result they write has a check type. Two engines in the suite is why
this was found before a commit rather than after.

**The constraint now has a test of its own.** `should_refuseResult_when_checkTypeIsMissing` asserts
that saving a result without a check type is refused. Before it, the non-null constraint was
enforced by the database and asserted by nothing — it was load-bearing and unguarded, which is how
a later "convenience" default would have removed it silently.

### Database coverage, and what is not claimed

| Test | Engine | How |
|---|---|---|
| `RevenueReconciliationStageTest` | **MySQL 8.0** | Testcontainers, real Flyway including V116 |
| `FinancePipelineOrchestratorTest` | **MySQL 8.0** | Testcontainers, real Flyway |
| `RevenueLoadStageTest`, `RevenueStagingValidatorTest`, the migration tests | **MySQL 8.0** | Testcontainers, real Flyway |
| `FinanceFoundationRepositoryTest` | **H2** | `@DataJpaTest` on the `test` profile |
| `SyncWorkerProfileBoundaryTest`, `ConnectorRegistryTest` | **none** | `ApplicationContextRunner`, mocked repositories, no database |

**TiDB has never been tested.** No migration in this project — V116 included — has ever run against
the deployment target. `ON DUPLICATE KEY UPDATE` with `VALUES()` and a nullable column inside a
unique index are both documented as MySQL-compatible and both are used by FIN-060; neither has been
executed on TiDB, so **no TiDB compatibility is claimed**. This extends limitation 9, which has been
open since FIN-052.
### Mutations

Eight, under the FIN-D-027 harness: report deleted before each run, run start time recorded, report
mtime required to postdate it, missing/stale report treated as a failure rather than a pass,
timeout detection, sources restored and md5-verified between every mutation, single-instance lock,
one mutation at a time. All artifacts live outside the repository.

| # | Mutation | Applied | Exit | Fresh report | Tests | Failures | Errors | Timed out | Result |
|---|---|---|---:|---|---:|---:|---:|---|---|
| R1 | source record-count comparison removed | YES | 1 | YES | 26 | 2 | 0 | NO | **KILLED** |
| R2 | source amount comparison removed | YES | 1 | YES | 26 | 3 | 0 | NO | **KILLED** |
| R3 | deletion detection disabled | YES | 1 | YES | 26 | 2 | 0 | NO | **KILLED** |
| R4 | partial processing reported as complete | YES | 1 | YES | 26 | 1 | 0 | NO | **KILLED** |
| R5 | any difference recorded as `PASSED` | YES | 1 | YES | 26 | 5 | 0 | NO | **KILLED** |
| R6 | source scope filter removed from the canonical sum | YES | 1 | YES | 26 | 1 | 0 | NO | **KILLED** |
| R7 | idempotency constraint removed | YES | 1 | YES | 26 | 1 | 0 | NO | **KILLED** |
| R8 | suspected deletion acted on destructively | YES | 1 | YES | 26 | 2 | 0 | NO | **KILLED** |

Which test caught each:

- **R1** — `should_fail_when_recordCountsDifferButAmountsMatch`,
  `should_passDeletionCheck_when_sourceHasMoreRecords`
- **R2** — `should_fail_when_grossAmountsDiffer`, `should_fail_when_totalsDifferByOnePaisa`,
  `should_endReconcileFailed_when_sourceDisagrees`
- **R3** — `should_suspectDeletion_when_closedYearShrankAtSource`,
  `should_keepPriorFact_when_laterSnapshotOmitsARecord`
- **R4** — `should_fail_when_stagedRowsAreStuck`
- **R5** — the four comparison tests plus `should_passDeletionCheck_when_sourceHasMoreRecords`
- **R6** — `should_keepResultsScoped_when_twoSourcesShareATemple`
- **R7** — `should_replaceOwnResults_when_reconciledTwice`
- **R8** — `should_deleteNothing_when_deletionIsSuspected`,
  `should_keepPriorFact_when_laterSnapshotOmitsARecord`

**R8 is the one that adds code rather than removing it.** There is no deletion call in this class
to delete, by design, so the only way to test the guarantee was to insert `facts.deleteAll()` into
the branch that raises a suspicion and confirm a test notices. One does.

**One known weakness in the harness, stated rather than glossed.** `timeout` kills the Maven
process it spawns but not orphaned Surefire fork JVMs, so a hung mutation could leave a JVM holding
a container. Nothing timed out in this run — every mutation returned exit 1 with a fresh report —
so no result here depends on it. Worth fixing before a harness run that does hang.

---

## FIN-057 — Pipeline Orchestrator

### Files

| File | Change |
|---|---|
| `service/finance/pipeline/FinancePipelineOrchestrator.java` | new — claims a batch, runs the stages, writes the terminal status |
| `service/finance/pipeline/RevenueExtractionStage.java` | new — the connector→staging drain that did not exist |
| `repository/finance/FinSyncBatchRepository.java` | `claimForRun` — conditional claim |
| `repository/finance/FinStgRevenueRepository.java` | `countBySyncBatchId` — source of `rows_extracted` |
| `service/finance/sync/SyncWorkerConfig.java` | two explicit `@Bean` registrations (FIN-D-008) |
| `test/.../FinancePipelineOrchestratorTest.java` | new — 13 tests, real stages, synthetic connector |
| `test/.../SyncWorkerProfileBoundaryTest.java`, `ConnectorRegistryTest.java` | mocks + bean assertions for the new beans |

### The state machine

```text
PENDING --claimForRun--> RUNNING --> EXTRACT --> VALIDATE --> MAP --> LOAD (normalize + write)
                                                                        |
                            SUCCESS <-------------------------------- all stages returned
                            FAILED  <-------------------------------- any stage threw
```

No new lifecycle enum was introduced. `SyncStatus` and `SyncStage` already modelled this, and a
second set would have to be kept in agreement with the first by hand.

Four calls for five stages: the load normalizes as its first step (FIN-D-037), so a normalization
failure surfaces at stage `LOAD` — the call an operator actually sees fail.

### Stage contracts

| Stage | Call | Owns |
|---|---|---|
| Extract | `RevenueExtractionStage.extractBatch(long)` | `rows_extracted`, `DUPLICATE_SOURCE_RECORD_REF` errors |
| Validate | `RevenueStagingValidator.validateBatch(long)` | `rows_rejected`, per-row rejection errors |
| Map | `RevenueMappingStage.mapBatch(long)` | mapping decisions, `UNDECIDED`/`UNMAPPED` errors |
| Normalize | called by the load | normalization rejections |
| Load | `RevenueLoadStage.loadBatch(long)` | `rows_loaded`, `FACT_WRITE_FAILED` errors |
| Orchestrator | `run(long)` | `status`, `started_at`, `finished_at`, `duration_ms`, `STAGE_FAILED` |

Every counter is *derived from a count*, never incremented, and the orchestrator writes none of
them (FIN-D-047, FIN-D-023).

### What the investigation found

**There was no staging writer in production code.** Limitation 11 recorded extraction as
FIN-043's; FIN-043 is scoped as the *Kollur* connector's `extract()`. The generic
connector→staging drain was unowned, and a search for any production write against
`fin_stg_revenue` returned only tests. Implemented here as `RevenueExtractionStage` rather than
faked in the harness — otherwise the orchestrator would "execute" while production still could
not stage a row (FIN-D-045).

### Transactions and concurrency

`run()` is **not** `@Transactional`. Claim, finish and failure-recording each take their own
transaction through `TransactionTemplate`; each stage keeps its own internal boundaries. A
multi-minute, multi-stage run inside one transaction would pin undo log, hit TiDB's transaction
size limits, and discard every staged row when the load failed on one fact (FIN-D-048).

The claim is `UPDATE ... WHERE id = ? AND status = PENDING` — a claim, not a check followed by a
write. Two racing runners cannot both win, and the loser is refused by name with the actual
status. **No distributed lock, scheduler or queue was added**; none is required by the current
architecture.

A failure is recorded *before* the status is written, in a fresh transaction, because the
stage's own transaction may already be doomed — writing the status inside it would roll back and
strand the batch in `RUNNING`, the one state nothing recovers from automatically. `recordFailure`
is best-effort: if it also fails, the original exception still propagates and the log carries
enough to find the batch by hand.

### The bug the tests found

`should_refuseDuplicate_when_connectorRepeatsARecordRef` failed with
`ObjectOptimisticLockingFailureException`, not the expected constraint violation. The failed
`saveAll` had assigned generated ids to the entity instances, the rollback did not reclaim them,
and the row-by-row retry re-saved those same instances — so Hibernate treated each as detached
and *merged* against rows that were never inserted. Fixed by holding `List<RawRow>` and
rebuilding entities at flush time (FIN-D-046). Without it, any chunk containing one duplicate
failed the whole batch with an error naming nothing useful.

### Tests

13 in `FinancePipelineOrchestratorTest`, MySQL 8.0 Testcontainer, real migrations, with the
**real** extraction, validation, mapping, normalization and load stages wired behind a synthetic
in-test connector. The synthetic connector supplies rows; it does not stand in for a stage. The
assertions are on staged rows, mapping decisions, canonical facts and batch counters that the
real stages produced — composition is proved, not asserted by verifying that methods were called.

No fixture is inserted into any production source table, and nothing in the test represents
Kollur: the temple id is 940001 and the connector bean is `syntheticTestConnector`.

### Boundaries recorded, not crossed

- **Watermark advancement is FIN-043's** (FIN-D-049). `watermark_after` stays null and a test
  asserts it. Incremental resumption is therefore not available; every batch needs its window.
- **Retry driving is not here.** `retry_count`, `max_retries` and `next_retry_at` exist and
  `findRetryable` reads them; nothing calls it. A `FAILED` batch stays failed until something
  asks for it again.
- **Re-processing means a new batch**, not a status reset — a finished batch refuses a re-run.

---

## FIN-056 — Idempotent Load

### Files

| File | Change |
|---|---|
| `repository/finance/FinRevenueFactRepository.java` | new — the native upsert, and the only writer of the canonical table |
| `repository/finance/FinStgRevenueRepository.java` | `markLoaded`, the bulk conditional claim |
| `service/finance/pipeline/RevenueLoadStage.java` | new — the stage |
| `service/finance/sync/SyncWorkerConfig.java` | `revenueLoadStage` bean |
| tests | `RevenueLoadStageTest` (18) |

### The line that carries the risk

```sql
ON DUPLICATE KEY UPDATE gross_amount = VALUES(gross_amount)   -- not gross_amount + VALUES(...)
```

Assignment, never accumulation (FIN-D-041). It is what makes a retry and a restatement both
safe, and an accumulating version satisfies `uk_frf_grain` perfectly — the row is unique either
way — while doubling a temple's reported revenue on every replay. No constraint can catch it.
Mutation L1 fails exactly one test, and that test is the entire defence.

`created_at` is deliberately absent from the update list. A delete-then-insert passes every
other test here and fails only that one, because it makes restating a two-year-old day
indistinguishable from loading it for the first time.

### Retry, restatement, deletion

| Event | What happens | Why |
|---|---|---|
| Same batch loaded twice | totals unchanged, `rows_loaded` unchanged | measures assigned; the staging claim is conditional so a `LOADED` row is not counted again |
| Later batch covers loaded days | those days are **replaced** | the source was re-read; this is what it says now |
| Source deletes records | earlier fact **stands**, overstating | an incremental window is a modification window, not a business-date range (FIN-D-044) |

The third is a real gap, tested so that it is visible. Closing it needs a full reload of a date
range or reconciliation against source totals (FIN-060), and it cannot be inferred from an
incremental batch without erasing correct history whenever a batch covers a narrower window than
the one before.

### Generated columns, first exercised

This is the first code path that writes through `grain_service_key`, `grain_counter_key` and
`grain_operator_key`. NULL is distinct from NULL in a unique index, so a fact with no counter and
no operator — the ordinary case for the first source — would insert twice and report the day
twice without them (FIN-D-018). `net_amount` is likewise the database's, and stays NULL where
cancellations are unrecorded rather than letting gross stand in for net.

### Failure

A partial load keeps what it wrote and still fails the batch (FIN-D-042). Per-fact transactions
mean a retry does not redo successful work; `LoadFailedException` plus errors at stage `LOAD`
mean no batch reports success having written half its facts. The orchestrator, when it exists,
must let that exception mark the batch `FAILED` rather than catching it into a "partially
loaded" outcome.

### Mutation results

Same contract as FIN-053…FIN-055 (FIN-D-027), single-instance locked.

| # | Mutation | Applied | Fresh report | Tests | Failed | Verdict |
|---|---|---|---|---|---|---|
| L1 | The upsert accumulates instead of replacing | YES | YES | 16 | 1 | **KILLED** |
| L2 | A restatement silently does not restate | YES | YES | 16 | 1 | **KILLED** |
| L3 | `created_at` is overwritten on restatement | YES | YES | 16 | 1 | **KILLED** |
| L4 | The staging claim is no longer conditional | YES | YES | 16 | 0 | **SURVIVED** |
| L5 | A failed load no longer fails the batch | YES | YES | 18 | 2 | **KILLED** (after) |
| L6 | `rows_loaded` counts rows that were not loaded | YES | YES | 16 | 2 | **KILLED** |

**L5 survived on the first run and that was a real gap.** Nothing in the suite had ever made a
write fail, so removing the throw that fails the batch changed no result — the whole failure path
was written and never executed. Two tests now force a genuine failure with an amount too large
for `DECIMAL(18,2)`, a plausible corrupt source value that reaches the write before anything
objects, and L5 re-measured is KILLED by exactly those two. The first-run figure is left in this
table rather than quietly replaced.

**L4 survives and is left surviving** (FIN-D-043). Normalization builds facts only from `VALID`
rows, so a second load finds nothing to claim whether or not the guard is there, and
`rows_loaded` is derived rather than incremented, so even two racing loaders converge on the same
number. The guard stays because it is correct and free, but no outcome this design produces can
distinguish its presence, and a test written to "cover" it would be asserting something another
mechanism already guarantees. Same finding as FIN-D-024, recorded rather than faked.

### Architectural review

| Question | Answer | Evidence |
|---|---|---|
| Can a replay double a temple's revenue? | NO | `should_replaceNotAccumulate_when_aLaterBatchCoversTheSameDay`, L1 |
| Is a correction silently ignored? | NO | L2 |
| Does a restatement look like a first load? | NO | `should_preserveCreatedAt_when_restating`, L3 |
| Can a NULL grain column duplicate a fact? | NO | `should_notDuplicate_when_grainColumnsAreNull` |
| Does a partial load report success? | NO | `should_failTheBatch_when_aFactCannotBeWritten`, L5 |
| Is successful work lost on failure? | NO | `should_keepWhatSucceeded_when_aLaterFactFails` |
| Is `rows_loaded` derived? | YES | `should_beIdempotent_when_theSameBatchIsLoadedAgain`, L6 |
| Is a rejected record ever loaded? | NO | `should_loadNothing_when_recordWasRejected` |
| Is gross ever reported as net? | NO | `should_leaveNetNull_when_cancellationsAreNotRecorded` |
| Can one temple's total include another's? | NO | `should_totalPerTempleAndYear_when_summing` |
| Is a source deletion detected? | **NO** | `should_leaveStaleFact_when_aLaterBatchNoLongerCoversIt` — known, FIN-D-044 |

## FIN-055 — Revenue Normalization

### Files

| File | Change |
|---|---|
| `db/migration/V115__kollur_revenue_date_declaration.sql` | new — the business-date declaration for the first source |
| `service/finance/pipeline/FinancialYear.java` | new — the one computation of a financial year |
| `service/finance/pipeline/RevenueField.java` | new — the canonical fields, each named by a declaration |
| `service/finance/pipeline/StagedPayload.java` | new — one payload reader, shared with mapping |
| `service/finance/pipeline/RevenueNormalizer.java` | new — decides one record, pure |
| `service/finance/pipeline/RevenueNormalizationStage.java` | new — the stage and the daily collapse |
| `repository/finance/FinStgRevenueMappingRepository.java` | decisions for a chunk in one query |
| `service/finance/sync/SyncWorkerConfig.java` | `revenueNormalizationStage` bean |
| tests | `FinancialYearTest` (12), `RevenueNormalizerTest` (36), `RevenueNormalizationStageTest` (21) |

### Where a figure comes from

ADR-008: which field is authoritative for a metric is declared, versioned and reviewable, not
buried in a connector. This is the first stage entitled to read a money value, and it reads the
field `fin_source_of_truth_decl` names. For the first source that declaration is the difference
between the authoritative total and one 41% short of it.

`V115` adds the missing half. FIN-023 declared the amount; nothing declared the date, so the
amount could be read and never placed in time. Its evidence is that FIN-023's own extraction
filter already cuts its window on `ReceiptDate` — good evidence, but inference from a filter, so
the declaration is seeded **unapproved** exactly as the amount one is.

### The open question from FIN-053, answered

Do declaration field references and connector field names share a vocabulary? **Yes**
(FIN-D-033). `source_field` is the key read from `raw_json`, matching FIN-D-028's rule for
mapping namespaces. It constrains connectors — a payload's keys are part of the contract — and
that is the point: a connector free to rename its output silently detaches every declaration and
every mapping rule at once.

### What it refuses

| Refusal | Code | Why not the tempting alternative |
|---|---|---|
| Mapping did not decide | `UNDECIDED_MAPPING` | `AMBIGUOUS`/`NOT_APPLICABLE`/`INVALID_CONFIGURATION` carry no category by design (FIN-D-031) |
| Category not in the taxonomy | `UNKNOWN_CANONICAL_CATEGORY` | inventing one is how a taxonomy stops meaning anything |
| Declared field absent or blank | `MISSING_*` / `EMPTY_*` | a connector that stopped emitting a field would otherwise be invisible |
| Date not unambiguous ISO | `UNPARSEABLE_TRANSACTION_DATE` | `03/04/2025` is 3 April or 4 March; guessing moves revenue between financial years |
| Amount not a number | `UNPARSEABLE_*` | zero asserts a measurement that never happened |
| Amount too precise | `PRECISION_LOSS_*` | rounding is a silent write-down that resurfaces as an unexplainable reconciliation gap |

### The collapse

Several staged records become one fact, grouped on `uk_frf_grain`'s six columns. It happens here
rather than in a connector because it needs the whole batch and must be visible and testable, and
not in staging because that would destroy the evidence staging exists for.

The in-memory `GrainKey` and the database constraint must stay in step. If they ever disagree the
same fact lands twice and revenue doubles, so anything added to one belongs in the other — note
that the database needed generated `grain_*` columns to get there (FIN-D-018) because NULL is
distinct from NULL in a unique index, while a Java record compares null by value.

Absence survives the collapse: an undeclared measure is NULL on every fact (FIN-D-035), and a
group with any unknown contributor totals to NULL rather than a partial sum (FIN-D-036).

### What it does not do

Writes nothing. `fin_revenue_fact` still has no writer, the facts are returned for FIN-056
(FIN-D-037), and staged rows are **not** marked `LOADED` — a record has not been loaded until
something loads it (FIN-D-038). `service_id` is always null: canonical service identity needs
`fin_service_dim` rows and a `SERVICE` mapping type, and a guessed service is worse than none.
`payment_mode` is always `UNRECORDED`, which is what the source says, not an inference.

### Mutation results

All six measured under the same contract as FIN-053 and FIN-054 (FIN-D-027): report deleted
first, absence is a failure rather than a pass, mtime must postdate the run's start, sources
restored and checksum-verified between. The harness is single-instance locked — two concurrent
copies once corrupted each other's tree and every verdict with it.

| # | Mutation | Applied | Fresh report | Tests | Failed | Verdict |
|---|---|---|---|---|---|---|
| P1 | Financial year boundary slips — 1 April falls in the closing year | YES | YES | 69 | 5 | **KILLED** |
| P2 | An unparseable amount is read as zero | YES | YES | 69 | 8 | **KILLED** |
| P3 | Money is rounded instead of refused | YES | YES | 69 | 1 | **KILLED** |
| P4 | An undecided mapping outcome is accepted | YES | YES | 69 | 4 | **KILLED** |
| P5 | The canonical category leaves the grain key | YES | YES | 69 | 2 | **KILLED** |
| P6 | An unknown contributor no longer makes the group total unknown | YES | YES | 69 | 1 | **KILLED** |

Which tests caught each one:

| # | Failing tests |
|---|---|
| P1 | `should_useTheNewYear_when_dateIsEarlyApril`, `should_startInApril_when_derivingTheYear`, `should_padToTwoDigits_when_yearEndsACentury`, `should_deriveFinancialYear_when_normalizing` |
| P2 | `should_refuse_when_amountIsNotANumber`, `should_excludeRejected_when_buildingFacts`, `should_beIdempotent_when_runTwice`, `should_leaveOtherStagesErrors_when_recordingItsOwn` |
| P3 | `should_refuse_when_amountWouldLosePrecision` |
| P4 | `should_refuse_when_mappingDidNotDecide`, `should_reject_when_mappingWasUndecided` |
| P5 | `should_groupOnTheCanonicalGrain_when_keying`, `should_keepFactsApart_when_grainDiffers` |
| P6 | `should_reportNull_when_oneContributorLacksAMeasure` |

**P2 is the one that shows the refusals are load-bearing.** Reading an unparseable amount as
zero fails eight tests, and only one of them is the direct parser test — the rest are the stage
tests that check a rejected record contributes nothing, that re-running does not accumulate
errors, and that this stage's error records exist at all. A zero would have flowed silently into
a daily total and taken the audit trail with it.

**P3 and P6 fail exactly one test each, which is the point of writing them.** Both defend a
single specific claim — money is never rounded to fit, and a partial sum never poses as a
complete one — and a narrow mutation that fails narrowly is evidence the test is testing the
thing it names rather than passing incidentally.

### Architectural review

| Question | Answer | Evidence |
|---|---|---|
| Can a missing amount become zero? | NO | `should_refuse_when_amountIsAbsent`, mutation P2 |
| Is a date format ever guessed? | NO | `should_refuse_when_dateFormatIsAmbiguous` |
| Is money ever rounded to fit? | NO | `should_refuse_when_amountWouldLosePrecision`, P3 |
| Is the financial year right at the boundary? | YES | `should_useTheNewYear_when_dateIsEarlyApril`, P1 |
| Can an undecided mapping produce a fact? | NO | `should_refuse_when_mappingDidNotDecide`, P4 |
| Is the grain the same as the constraint's? | YES | `should_groupOnTheCanonicalGrain_when_keying`, P5 |
| Does a partial sum ever look complete? | NO | `should_reportNull_when_oneContributorLacksAMeasure`, P6 |
| Can one source's declaration read another's payload? | NO | `should_isolateSources_when_anotherSourceHasTheDeclaration` |
| Is staging modified? | NO | `should_leaveStagingUntouched_when_normalizing` |
| Are earlier stages' errors disturbed? | NO | `should_leaveOtherStagesErrors_when_recordingItsOwn` |
| Is anything loaded here? | NO | no `fin_revenue_fact` write exists in the stage |

## FIN-054 — Revenue Mapping

### Files

| File | Change |
|---|---|
| `db/migration/V114__finance_revenue_mapping.sql` | new — `fin_mapping_rule.priority`, `fin_stg_revenue_mapping` |
| `entity/finance/enums/MappingOutcome.java` | new — five outcomes |
| `entity/finance/FinStgRevenueMapping.java` | new |
| `entity/finance/FinMappingRule.java` | `priority` |
| `repository/finance/FinStgRevenueMappingRepository.java` | new |
| `repository/finance/FinRevenueCategoryRepository.java` | new — the canonical taxonomy had no reader |
| `repository/finance/FinSyncErrorRepository.java` | stage-scoped count, find and delete |
| `service/finance/pipeline/MappingRuleResolver.java` | new — the resolver, pure |
| `service/finance/pipeline/RevenueMappingStage.java` | new — the stage |
| `service/finance/pipeline/RevenueStagingValidator.java` | `rows_rejected` scoped to `VALIDATE` |
| `service/finance/sync/SyncWorkerConfig.java` | `revenueMappingStage` bean |
| tests | `MappingRuleResolverTest` (16), `RevenueMappingStageTest` (22), three worker-context tests extended |

### The two mapping layers

ADR-004: *which rows and which columns* is connector code; *what a value means* is
configuration. FIN-054 is entirely the second.

| Layer | Question | Where | Status |
|---|---|---|---|
| A — structural extraction | which source table and column produce a staged field | connector, `fin_source_of_truth_decl` | FIN-041/043, blocked on Q4 |
| B — semantic business mapping | what a staged value means canonically | `fin_mapping_rule` | **this task** |

A purity scan fails the build if a source table, column, temple or credential name appears in
the resolver.

### How the stage finds a value without knowing a schema

A rule's `source_value` is namespaced — `SANNIDHI:DS`, `SEVA_CODE:430` — and **the namespace is
the name of the staged field the rule reads** (FIN-D-028). The fields consulted are derived from
the rules, so a rule in a new namespace starts a new field being read with no deployment. The
connector's side of the bargain is to emit its payload under those logical names; that is the one
place the two vocabularies must agree, and it is now written down rather than assumed.

### Two design gaps closed first

1. **Precedence existed only in prose.** FIN-D-015 decided the more specific rule wins and made
   it the connector's job, which buries a classification decision in per-temple code. Now a
   stored `priority` (FIN-D-029). Without `SEVA_CODE:430` outranking `SANNIDHI:KN`, the first
   source's donation-box collections — 13 records averaging over a crore — are reported as
   ordinary donations and dominate any ranking of purchased services.
2. **Nothing said where a rule's value is found.** Closed by the namespace convention, with no
   new column and no change to the connector contract.

### Outcomes

| Outcome | Canonical value | Meaning |
|---|---|---|
| `MAPPED` | the rule's | one rule won |
| `UNMAPPED` | `UNMAPPED` | a value no rule covers; add a rule |
| `AMBIGUOUS` | **none** | rules contradict each other at equal priority |
| `NOT_APPLICABLE` | **none** | no field to read, or present and empty |
| `INVALID_CONFIGURATION` | **none** | a rule names a category that does not exist |

Three of the five produce no canonical value at all, so FIN-056 has nothing it could load them
as. `UNMAPPED` versus `NOT_APPLICABLE` is the distinction that earns its keep: the first needs a
mapping rule, the second usually means extraction stopped supplying a field.

Ambiguity is never resolved by picking one. The only tiebreakers available are rule id and
whatever order the database returns, and both would make published revenue depend on an
implementation detail.

Values are matched exactly — nothing trimmed or case-folded into a match. A source that pads or
capitalises differently surfaces as unmapped, which is how somebody finds out.

### Idempotency and re-running

Keyed `(stg_revenue_id, mapping_type)`, enforced by `uk_fsrm_row_type`. Correcting a rule and
running again replaces the decision rather than adding one — this is the supported way to fix a
misclassification, and the main practical reason staging is retained. The stage's own errors are
cleared, scoped to `MAP`, before being rewritten.

Staging is not touched at all, and `StagingStatus` gains no `MAPPED` value (FIN-D-030).

### A counter defect found in FIN-053

`rows_rejected` was derived from *all* of a batch's errors. Mapping records at a different grain
— one error per distinct unresolved value, not per row — so the unscoped count would have folded
mapping's summaries into a rejected-row figure matching no set of rows. Now scoped to `VALIDATE`
(FIN-D-032). `fin_sync_error` consequently holds two grains, which is a real cost: anything
counting it must scope by stage, and `MAP` rows carry a NULL `source_record_ref` because they
are about a value rather than a record.

### Scope

`REVENUE_CATEGORY` only — the only mapping type with both seeded rules and a seeded canonical
target. `SERVICE` needs 164 rules and `fin_service_dim` rows that do not exist; `PAYMENT_MODE`
for the first source is inferred from field presence, which ADR-004 puts in connector code;
`METAL_TYPE` belongs to FIN-110 and its two seeded rules are **not namespaced**, so they are
reported as `UNUSABLE_MAPPING_RULE` rather than silently ignored. The resolver is generic over
`MappingType`, so those arrive as configuration.

Nothing is loaded: no amounts, no dates, no financial year, no `fin_revenue_fact` row.

### Mutation results

All six measured, each against a report the run itself produced (FIN-D-027): the report is
deleted first, its absence is a failure rather than a pass, and its timestamp must postdate the
run's start. Sources are restored between mutations and verified against a pristine checksum.

| # | Mutation | Applied | Fresh report | Tests | Failures | Verdict |
|---|---|---|---|---|---|---|
| N1 | Source scoping removed — every source's rules load | YES | YES | 38 | 2 | **KILLED** |
| N2 | Ambiguity detection removed — the first winner is taken | YES | YES | 38 | 2 | **KILLED** |
| N3 | Idempotency protection removed — a re-run inserts a second decision | YES | YES | 38 | 2 | **KILLED** |
| N4 | Unmapped reported as mapped | YES | YES | 38 | 9 | **KILLED** |
| N5 | Source-row traceability dropped | YES | YES | 38 | 1 | **KILLED** |
| N6 | Precedence inverted — the least specific rule wins | YES | YES | 38 | 3 | **KILLED** |

Which tests caught each one:

| # | Failing tests |
|---|---|
| N1 | `should_isolateSources_when_anotherSourceHasTheRule`, `should_isolateTemples_when_bothHaveRules` |
| N2 | `should_reportAmbiguous_when_twoRulesMatchAtTheSamePriority`, `should_carryNoCanonicalValue_when_undecided` |
| N3 | `should_beIdempotent_when_runTwice`, `should_replaceDecision_when_ruleIsCorrectedAndRerun` |
| N4 | `should_reportUnmapped_when_noRuleMatchesTheValue`, `should_summariseUnmapped_when_manyRecordsShareAValue` and seven more |
| N5 | `should_preserveSourceIdentity_when_mapping` |
| N6 | `should_preferHigherPriority_when_twoRulesMatch`, `should_beDeterministic_when_ruleOrderVaries`, `should_replaceDecision_when_ruleIsCorrectedAndRerun` |

**N6 is the one worth reading twice.** Priority is the column FIN-D-029 added, and inverting it
is the difference between donation-box collections being reported as collections and being
reported as ordinary donations. That it fails `should_beDeterministic_when_ruleOrderVaries` as
well as the direct precedence test is the point: the guarantee is not "the right rule usually
wins" but "the same rule wins whatever order the database returns".

**Two earlier N6 runs were recorded `NOT_EXECUTED` and are not in this table.** Neither failure
was the mutation's: the first was an in-progress FIN-055 source file referencing a repository
method that did not exist yet, the second a `target/` directory left corrupt after a process
kill. Both are exactly the kind of result FIN-D-027 exists to keep out of a table like this one,
so N6 was rerun on a verified-clean tree rather than reported from a build that never ran.

### Architectural review

| Question | Answer | Evidence |
|---|---|---|
| Source vocabulary, transport or credentials in the stage? | NO | `should_stayGeneric_when_sourceScanned` |
| Can an unknown value be silently accepted? | NO | `should_reportUnmapped_when_noRuleMatchesTheValue`, mutation N4 |
| Are ambiguous rules resolved arbitrarily? | NO | `should_reportAmbiguous_when_twoRulesMatchAtTheSamePriority`, N2 |
| Is precedence deterministic and order-independent? | YES | `should_beDeterministic_when_ruleOrderVaries`, N6 |
| Can one source's rules classify another's revenue? | NO | `should_isolateSources_when_anotherSourceHasTheRule`, N1 |
| Are disabled or deleted rules applied? | NO | `should_ignoreRule_when_inactive`, `should_ignoreRule_when_softDeleted` |
| Are unmapped records discarded? | NO | `should_keepRecord_when_valueIsUnmapped` |
| Can a retry duplicate a decision? | NO | `uk_fsrm_row_type`, `should_beIdempotent_when_runTwice`, N3 |
| Is source-row traceability preserved? | YES | `should_preserveSourceIdentity_when_mapping`, N5 |
| Is staging modified? | NO | `should_leaveStagingUntouched_when_mapping` |
| Is anything normalized or loaded here? | NO | no amount, date or fact code exists in the stage |

---

## FIN-053 — Staging Validation

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/templeregistry/service/finance/pipeline/RevenueStagingValidator.java` | new — the validator; terminates through an id cursor (FIN-D-026) |
| `backend/src/main/java/com/templeregistry/repository/finance/FinStgRevenueRepository.java` | new — cursor-keyed chunked reads and the guarded `transition` |
| `backend/src/main/java/com/templeregistry/service/finance/sync/SyncWorkerConfig.java` | `revenueStagingValidator` bean |
| `backend/src/test/java/com/templeregistry/service/finance/pipeline/RevenueStagingValidatorTest.java` | new — 25 tests |
| `backend/src/test/java/com/templeregistry/service/finance/sync/FinanceIntegrationBoundaryTest.java` | guards the new `pipeline` package too |
| `backend/src/test/java/com/templeregistry/service/finance/sync/SyncWorkerProfileBoundaryTest.java` | worker runner supplies the pipeline's persistence collaborators |
| `backend/src/test/java/com/templeregistry/connector/finance/ConnectorRegistryTest.java` | same, for its worker-assembly test |

**The two test-side changes were a regression, not housekeeping.** Registering a
repository-dependent bean in `SyncWorkerConfig` broke every `ApplicationContextRunner` that
assembles the worker without a database: `SyncWorkerProfileBoundaryTest$SyncWorkerRuntime`
(2 failures, 2 errors) and `ConnectorRegistryTest` (1 failure). The finance suite's true state
before this fix was **155 run / 3 failures / 2 errors**, not the "155 passing, 0 failures" the
next section used to promise.

**No migration.** No schema change, no entity change, no connector, no transport, no
credential, no API, no frontend, no dependency added.

### The validation boundary

Six rules, and the test for whether a rule belongs here is whether it can be stated without
knowing anything about any particular source system (FIN-D-022):

| Code | Rule |
|---|---|
| `BLANK_RECORD_REF` | the locator is blank, so the original cannot be found in the source |
| `PROVENANCE_MISMATCH` | the row's `temple_id` or `source_system_id` disagrees with its batch |
| `MISSING_PAYLOAD` / `MALFORMED_PAYLOAD` | `raw_json` absent or unparseable |
| `PAYLOAD_NOT_OBJECT` | not an object of source fields |
| `EMPTY_PAYLOAD` | an object carrying no fields |
| `NON_SCALAR_FIELD` | a field holds a nested structure the connector contract does not deliver |

**Dates and amounts are deliberately not validated.** They live inside `raw_json` under the
connector's own field names, so checking them here would mean teaching this class one temple's
source vocabulary — the exact knowledge that stops at the connector. FIN-055 reads them against
the source-of-truth declaration, which is where the platform learns which field is
authoritative for whom.

**`PROVENANCE_MISMATCH` is the rule that matters most.** The database cannot enforce it, and a
staged row whose temple disagrees with its batch would attribute one temple's money to another
with nothing downstream noticing.

**What VALID does not mean:** not mapped, not resolved, not authoritative, not reconciled, not
reportable, not loaded. It means the six rules passed.

### Rejection semantics

One `fin_sync_error` per rejected row at stage `VALIDATE` (FIN-D-023), so
`rows_rejected = 143` means 143 error rows an operator can open. Several failures on one row
produce one error whose message names them all and whose `error_code` is the first rule in a
fixed order — one defect, one code, so counting by code is meaningful.

The staged row keeps its payload and gains a `rejection_reason`. The error's
`raw_payload_json` is left **null**: a second copy would spread whatever personal information a
temple's records contain, and the batch plus `source_record_ref` locate the original. Messages
never quote payload content — a malformed-JSON message carries the parse position, not the text.

`fin_sync_batch.rows_rejected` is **set** to the batch's error count, never incremented, so a
re-run, an interrupted run or two workers cannot inflate it.

### Transactions, concurrency and failure

One transaction per row (`REQUIRES_NEW`), inside which the status change and the error insert
commit together (FIN-D-024). The status change is a **conditional claim** — it matches only a
row still `RECEIVED` — so:

- a rejection can never be recorded without its status change, or the reverse;
- two validators on one batch each process a row at most once (tested with two threads over 40
  rows: exactly 40 processed, exactly 20 errors);
- `REJECTED` and `LOADED` are terminal, because neither matches the expected `RECEIVED`.

A persistence failure **aborts the run** and leaves the remaining rows `RECEIVED` for the next
one. Catching and continuing would turn a database problem into silently skipped records.

Transactions are driven by an explicit `TransactionTemplate` rather than `@Transactional`,
because the per-row call is a self-invocation and the annotation would have been silently
ignored.

**The loop could not terminate, and this section previously claimed a fix that did not exist.**
The chunked read asked repeatedly for the *first page* of `RECEIVED` rows, so it ended only if
every row it read left that state. A row that did not — the ordinary result of losing a claim
race to a second validator — was handed back for ever. `validateBatch` never returned.

It is now read through an advancing id cursor (`id > :afterId`), so each row is offered exactly
once and the query is guaranteed to run out whatever this validator manages to claim. Safe
because the status axis is monotonic: nothing returns a row to `RECEIVED`, so a row passed over
cannot reappear behind the cursor. Recorded as **FIN-D-026**, with the rejected alternatives —
a per-pass progress counter, and a fixed iteration cap — and why each is worse.

Why this mattered more than an ordinary bug: the failure is not an exception, not a failed
batch and not a `fin_sync_error`. It is a worker holding a database connection for ever while
the batch stays `RUNNING`, `rows_rejected` is never written, and the dashboard shows figures
that are merely old. Everything else in FIN-053 exists to make a lost judgement impossible, and
all of it is bypassed by a run that never reaches its own final statement.

Two claims previously in this file are withdrawn. There was no guard that "stops when a pass
claims nothing" in any source file. And four mutations were listed as verification when one had
been measured — see FIN-D-027.

**A quadratic scan went with it.** Asking for page 0 each time re-read the batch from the start
on every chunk. The test class now runs in **74.8 s** against **221.4 s** before.

### Tests

`RevenueStagingValidatorTest` — **25**, `@DataJpaTest` against a real MySQL 8.0 container with
the real migrations, test methods non-transactional so that what is asserted is what committed.

Two cover termination. `should_terminate_when_noRowCanBeClaimed` hands the validator a
repository whose rows never leave `RECEIVED`. `should_processClaimableRows_when_othersCannotBeClaimed`
is the half-way case a careless fix gets wrong: two claimable rows and two contended ones,
interleaved so the contended ones are neither first nor last, where every claimable row must
still be judged and the run must still end.

**Mutation results — all five measured, each against a freshly generated report** (FIN-D-027).
Command in every case:
`mvn -o -Dtest=RevenueStagingValidatorTest -DfailIfNoTests=false test`

| # | Mutation | Applied | Fresh report | Tests run | Failures | Verdict |
|---|---|---|---|---:|---:|---|
| M1 | `fin_sync_error` creation removed | YES | YES | 25 | 12 | **KILLED** |
| M2 | rejection becomes a silent skip | YES | YES | 25 | 14 | **KILLED** |
| M3 | transition no longer a conditional claim | YES | YES | 25 | 1 | **KILLED** |
| M4 | blank-locator rule removed | YES | YES | 25 | 2 | **KILLED** |
| M5 | loop cursor never advances | YES | YES | 25 | 2 | **KILLED** |

Evidence: `log-M1.txt` … `log-M5.txt` alongside the harness. No run timed out; sources were
restored after each and checked against a checksum taken before the first patch.

**M2 is the one that shows the fix works.** This is the mutation that previously hung the build
for 26 minutes and produced no report at all. It now fails in normal time with 14 tests red,
`should_terminate_when_noRowCanBeClaimed` among them.

**M3 is the weak spot, and is worth knowing before touching the concurrency test.** Removing
the conditional claim fails exactly **one** test —
`should_processEachRowOnce_when_twoValidatorsRunTogether` — a single timing-dependent
two-thread test. The terminal-state tests do not catch it, because the chunk query already
filters on `validation_status = 'RECEIVED'` and a terminal row never reaches `transition`. If
that one test is ever disabled or quarantined as flaky, nothing else notices that the claim has
gone.

### Architectural review

| Question | Answer |
|---|---|
| Generic across temples, free of source vocabulary? | **YES** — asserted by a source scan |
| Reads a temple database, or any transport? | **NO** — it reads the registry's own staging table |
| Credentials anywhere? | **NO** |
| Raw payload rewritten? | **NO** — content preserved exactly (FIN-D-025) |
| Missing data converted to zero? | **NO** — nulls and empty strings pass through untouched |
| Rejected row preserved and explainable? | **YES** — row, reason, and one coded error |
| Rejection possible without a record? | **NO** — they commit together |
| Terminal states respected? | **YES** — `REJECTED` and `LOADED` are never reprocessed |
| Concurrency safe? | **YES** — claimed, and tested with two threads |
| Batch counter explainable? | **YES** — derived from error rows |
| Anything loaded or normalized here? | **NO** |

## FIN-050 — Revenue Staging (previous session)

### Files

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V113__finance_revenue_staging.sql` | new — 1 table, 4 indexes |
| `backend/src/main/java/com/templeregistry/entity/finance/FinStgRevenue.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/enums/StagingStatus.java` | new |
| `backend/src/test/java/com/templeregistry/migration/FinanceRevenueStagingMigrationTest.java` | new — 20 tests |

**Migration number:** `V113` — the repository's highest was `V112`. No existing table altered,
`V112` untouched, no dependency added, no repository, service, reader or writer created.

### Grain, and why not the alternatives

**One row per record a connector delivered, within one sync batch** — one `RawRow`, exactly as
`extract()` produced it (FIN-D-021). Staging never merges, splits or reinterprets.

Two other grains were possible and both lose something:

- *One canonical candidate fact* — collapsing to the daily grain during extraction would make
  staging a worse copy of `fin_revenue_fact` and destroy the mapping from source records to
  the figure they produced, which is the exact trace needed when a temple disputes a total.
- *One source receipt* — not available: connectors group at the source (ADR-003) precisely so
  22 M rows never cross the wire.

Several staged rows may therefore contribute to one canonical daily fact. That collapse is
FIN-055's, where it is visible and testable.

### Columns and nullability

| Column | Null | Purpose |
|---|---|---|
| `temple_id`, `source_system_id`, `sync_batch_id`, `source_record_ref` | NOT NULL | Provenance. A staged figure whose origin is unknown cannot be investigated, which is the only reason to keep it |
| `raw_json` | NOT NULL | The record as delivered: connector field names, values as raw strings. Source vocabulary stops at this column |
| `source_business_date` | NULL | Connector-declared, **advisory**. NULL means "not declared at extraction", never "no date"; normalization derives the authoritative date |
| `validation_status` | NOT NULL, default `RECEIVED` | See lifecycle below |
| `rejection_reason` | NULL | Human-readable; the coded, queryable form stays in `fin_sync_error` |
| `extracted_at` / `created_at` / `updated_at` | NOT NULL | When the connector read it / when we stored it / when its state last changed. None is a business date |

**No typed amount column, deliberately.** Amounts live inside `raw_json` as strings until
normalization; a typed column would force parsing during extraction, and one malformed value
would cost a batch of forty thousand good rows. A test asserts the table has no `DECIMAL`,
`FLOAT`, `DOUBLE` or `REAL` column at all — exact decimal money is `fin_revenue_fact`'s job.

### Idempotency

`uk_fsr_batch_record (sync_batch_id, source_record_ref)`.

A replay stages the same records again under a **new** batch — legitimate and necessary, since
comparing two extractions of one window is how a restatement is explained. The same record
twice inside **one** batch is refused: that is either a connector defect or a reference that
does not identify what it claims to, and both silently double-count downstream.

Safe because `RawRow.sourceRecordRef` is mandatory and non-blank by contract (FIN-030), so
every key column is `NOT NULL`. Note the contrast with FIN-D-018: in `fin_revenue_fact` the
NULL-distinct index semantics had to be worked around; here the plain constraint means exactly
what it says. Same database behaviour, opposite consequence — each was decided, not copied.

**Mutation-verified:** removing the constraint fails 2 tests.

> **Correction from FIN-053 (FIN-D-025).** This section originally said the payload is stored
> "exactly as delivered". That is true of its *content* and not of its bytes: `raw_json` is a
> `JSON` column, so MySQL and TiDB store a parsed representation and re-emit it with their own
> key order and spacing. Every field name, value and null survives — including empty strings
> and JSON nulls, which is what evidence requires — but a check of a staged payload must
> compare parsed content, not text. `V113` itself is not edited, because changing an applied
> migration's text changes its Flyway checksum.

### Status lifecycle, and who owns each transition

```
RECEIVED --validation (FIN-053)--> VALID --load (FIN-056)--> LOADED
RECEIVED --validation (FIN-053)--> REJECTED   (terminal, with a reason)
```

Monotonic: nothing returns to `RECEIVED`, and re-processing means a new batch rather than a
state reset, so the record of what was rejected and why survives the retry. **FIN-050
implements none of these transitions** — every row lands `RECEIVED` and stays there until
FIN-053 exists. There is no `DUPLICATE` state: within a batch the constraint refuses one, and
across batches it is a restatement.

A rejected row is never deleted. It keeps its reason and its payload, and FIN-053 will add the
coded `fin_sync_error` row that makes "rows_rejected = 143" explainable.

### Tests

`FinanceRevenueStagingMigrationTest` — **20**, real MySQL 8.0 container, real Flyway: migration
applies; entity columns all exist; provenance cannot be omitted (five columns, each tried);
two temples and two source systems isolated; one record traced across batches; duplicate in a
batch refused; replay under a new batch allowed; many distinct records in one batch; default
`RECEIVED` with the four documented states storable and the column comment documenting them;
a rejected row keeping reason and payload; business date separate from extraction time by
column and by type; an undeclared date staying NULL; an impossible source value (`0000-00-00`,
an empty string, a paisa-exact decimal) surviving verbatim; no typed money; two purity scans;
the four indexes; and V112's canonical grain still holding after V113.

### Architectural review

| Question | Answer |
|---|---|
| Generic across all temples? | **YES** |
| Supports all four integration mechanisms? | **YES** — staging does not know how data was obtained |
| Avoids Kollur-specific schema and source table names? | **YES** |
| Preserves source provenance? | **YES**, and mandatory |
| Business date separate from extraction/sync time? | **YES** |
| Staging grain explicitly defined? | **YES** (FIN-D-021) |
| Compatible with the canonical daily grain? | **YES** — many staged rows to one fact, collapsed in FIN-055 |
| Duplicate and replay behaviour documented and enforced? | **YES**, mutation-verified |
| Missing distinguishable from measured zero? | **YES** — payload verbatim, advisory date nullable, no defaults invented |
| Monetary values exact? | **YES** — as strings here, as `DECIMAL` in the fact; no float anywhere |
| Tenant and source isolation enforced? | **YES** |
| Constraints enforced by the database? | **YES** |
| Free of credentials and transport detail? | **YES** — scan covers password, credential, jdbc, host, port, endpoint, driver |
| Future loaders consume it without per-temple schema changes? | **YES** |
| Rejected records investigable? | **YES** — row, reason and payload all retained |
| Anything outside FIN-050 scope? | **NO** — no transition implemented, no reader, no writer |
| Pipeline testable with synthetic rows, no temple database? | **YES** |

## FIN-051 / FIN-052 — Canonical Revenue Model (previous session)

### Files

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V112__finance_canonical_revenue.sql` | new — 3 tables, 12 seeded category rows |
| `backend/src/main/java/com/templeregistry/entity/finance/FinRevenueCategory.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/FinServiceDim.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/FinRevenueFact.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/enums/PaymentMode.java` | new |
| `backend/src/main/java/com/templeregistry/entity/finance/enums/PaymentModeConfidence.java` | new |
| `backend/src/test/java/com/templeregistry/migration/FinanceCanonicalRevenueMigrationTest.java` | new — 19 tests |

**Migration number:** `V112` — the repository's highest was `V111`. No existing table is
altered. No connector, staging table, service, repository, API or frontend file is touched,
and no dependency was added.

### The canonical grain

```
(temple_id, transaction_date, service_id, category_id, payment_mode, counter_ref, operator_ref)
```

Enforced by `uk_frf_grain`. One row per day per service per category per payment mode per
counter per operator — **not** one row per receipt (ADR-003): 22.3 M receipts on the first
source become ~121 k rows, and no devotee name, address, mobile or email is copied centrally
at all.

**Two corrections to the documented key, both found by building it:**

1. **It did not enforce the grain.** `service_id`, `counter_ref` and `operator_ref` are
   legitimately nullable — a donation-box collection has none of them — and MySQL and TiDB
   treat NULLs in a unique index as distinct. The documented key, written literally, accepts
   the same hundi fact twice. Stored generated columns (`grain_service_key`,
   `grain_counter_key`, `grain_operator_key`) substitute concrete values so the constraint
   bites (FIN-D-018). **Mutation-verified:** with the literal key, the duplicate insert
   succeeds and nothing complains.
2. **`operator_ref` joined the key** (FIN-D-019). R27 reports revenue by counter *and*
   operator from this table, which the six-column key cannot answer — and a connector
   grouping by operator would emit colliding rows, so the loader would lose or overwrite one
   operator's takings. Whatever a connector groups by must be a subset of the key. ADR-003's
   "Consequences" section is superseded on that one point.

### Semantics worth knowing before writing the loader

- **Business date is `transaction_date`, and nothing else.** A source editing a two-year-old
  receipt corrects an old financial day; it does not move money into today. The load axis is
  `created_at` / `updated_at` plus the batch (FIN-D-012).
- **Cancellation has three states.** `cancelled_amount = 150.00` measured and deducted, `0`
  measured and none, `NULL` not recorded by this source. `net_amount` is a stored generated
  column, `gross − cancelled`, and is therefore NULL in the third case (FIN-D-020) — gross
  reported as net would assert that nothing was cancelled.
- **Every measure is nullable** (ADR-007). There is no sentinel zero anywhere in the table.
- **`UNMAPPED` is a seeded category.** FIN-054 routes unmappable source values there, never
  into `OTHER_INCOME`, whose own description forbids that use.
- **Provenance is mandatory.** `source_system_id` and `sync_batch_id` are `NOT NULL`: a
  published figure must always name the run that produced it.

### Tests

`FinanceCanonicalRevenueMigrationTest` — **19**, against a real MySQL 8.0 container with real
Flyway, asserting database behaviour rather than DDL text. Grain rejection including the
all-NULL case; six distinct dimensions coexisting on one date; upsert convergence (one row,
figure replaced, provenance following); two temples holding the identical grain
independently; business date surviving restatement; the three cancellation states; mandatory
and retained provenance; payment-mode confidence; exact decimal round-trips with no floating
point anywhere; and two purity scans over the committed DDL and the live schema.

One test compares every `@Column` on the three entities against `information_schema` — the
defect class behind FIN-X-001, for which this project otherwise has no working check.

### Architectural review

| Question | Answer |
|---|---|
| Is the revenue fact generic across temples? | **YES** |
| Is source schema copied into it? | **NO** |
| Is source transport represented? | **NO** |
| Are credentials represented? | **NO** |
| Is business date separate from sync time? | **YES** |
| Is cancellation distinguishable? | **YES** — and from zero as well as from unknown |
| Is provenance retained? | **YES** — and mandatory |
| Is the canonical grain explicit? | **YES** |
| Is the grain database-enforced? | **YES** — mutation-verified |
| Can two temples coexist? | **YES** |
| Can future connectors write without schema changes? | **YES** |
| Can the dashboard eventually operate without source DB access? | **YES** |

---

## FIN-031 — Connector Registry (previous session)

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/templeregistry/connector/finance/ConnectorRegistry.java` | new — 100 lines, plain Java, no Spring import |
| `backend/src/main/java/com/templeregistry/connector/finance/ConnectorConfigurationException.java` | new |
| `backend/src/main/java/com/templeregistry/service/finance/sync/SyncWorkerConfig.java` | `connectorRegistry` bean added |
| `backend/src/test/java/com/templeregistry/connector/finance/ConnectorRegistryTest.java` | new — 13 tests |
| `backend/src/test/java/com/templeregistry/service/finance/sync/RegistryRuntimeContextTest.java` | asserts the registry runtime holds no registry and no connector |

No migration, no schema change, no entity, no repository, no API, no frontend, no
dependency added to `pom.xml`.

### What it does, and what it deliberately does not

Resolution is one map lookup on the configured identifier — no branch, no switch, no
knowledge of any temple — so onboarding the tenth source system adds a connector bean and a
configuration row and changes nothing here. The registry creates no connector, resolves no
credential, opens nothing, selects no transport and runs no synchronization.

It is built in `SyncWorkerConfig` from `getBeansOfType(TempleFinanceConnector.class)`, so a
connector reaches the registry by being declared as a worker `@Bean` and by no other route.
Nothing is scanned: a discovery mechanism would have removed the registration FIN-D-008
depends on and let a connector class drift into the registry runtime by nothing more than
being on the classpath.

### The missing-connector behaviour

`resolve` returns a `TempleFinanceConnector` or throws `ConnectorConfigurationException`.
There is no `Optional`, no nullable return, no default connector and no "skip this source"
branch (FIN-D-017). Four distinguishable failures, each naming the connector identifier and
the source system identity and none naming a credential reference or value:

| Condition | Result |
|---|---|
| Configured connector not registered | throws, listing what *is* registered |
| Source system names no connector | throws |
| Connector implements a different `ConnectorType` than the source declares | throws |
| Connector registered under a name it does not declare as its `connectorId` | throws at worker startup |

**Kollur is the live case.** It is completely configured — source system, 19 capabilities,
source of truth, 9 mapping rules — and names `kollurFinanceConnector`, which does not exist.
Resolving it throws today, and the test asserts exactly that, reading the connector name out
of `V111` rather than hardcoding it. No placeholder connector was created to make anything
pass; the absence is the verification.

### Tests

`ConnectorRegistryTest` — **13**, no database and no Docker required. Successful resolution
by identity; missing connector; empty registry; unnamed connector; no API that can express
absence; three connectors across three integration mechanisms resolved generically; type
contradiction; name/metadata contradiction; the Kollur name read from the seed; the assembled
sync worker registering a registry and zero connectors; the registry runtime registering
neither; and a source scan proving the registry holds no source-specific branch, transport,
persistence or credential.

**Mutation-verified.** Replacing the missing-connector throw with `return null` failed 4
tests, including the Kollur one. Restored afterwards.

| Check | Result |
|---|---|
| Missing connector → explicit failure | YES |
| Null / no-op / `Optional` fallback | NO |
| Source-specific code in the registry | NO |
| Credential resolution | NO |
| Transport, filesystem or database access | NO |
| Reachable from the registry runtime | NO |
| Worker-only infrastructure | YES |

---

## Kollur configuration (FIN-021…024, previous session)

### Migration file

`backend/src/main/resources/db/migration/V111__kollur_finance_configuration.sql`

Configuration data only. Creates no table, alters no schema, enables no synchronization,
stores no credential, no host, no port, no URL and no connection string.

**Every statement is conditional on temple 300001 existing** (FIN-D-014). No migration in
this repository creates that temple — `V100` seeds temples with ids `100`… — so an
unconditional seed would have left a source system, 19 capability rows, a source-of-truth
declaration and 9 mapping rules orphaned in every fresh developer and CI database.

> **Operational consequence that must not be forgotten.** A versioned migration runs once.
> In an environment where temple 300001 is created *after* V111 has run, the seed never
> applies and Kollur configuration must be applied through the onboarding path (FIN-140) or
> by re-running the statements manually. V111 is idempotent, so re-running is safe.

---

## Seeded Rows

| Table | Rows |
|---|---:|
| `fin_source_system` | 1 |
| `fin_temple_capability` | 19 |
| `fin_source_of_truth_decl` | 1 |
| `fin_mapping_rule` | 9 |

### Source system (FIN-021)

`temple_id=300001` · `system_code=KOLSOHAM` · `source_temple_code=43` ·
`source_database_name=KOLSOHAM_LOCAL` (documentation only) · `credential_ref=kollur-readonly`
(an **alias**) · `sync_enabled=0` · `sync_schedule_cron=NULL`.

`connector_type` is **`PULL_JDBC` and provisional**, recorded as such in the row's own
`notes` (FIN-D-016). Q4 is unresolved and the column is `NOT NULL`, so a value had to be
written; it reflects how the source was analysed, not a finding that the platform can reach
it. If Q4 resolves to `PUSH_AGENT`, that is one `UPDATE` and nothing else changes — which is
what the FIN-030 contract exists to guarantee.

### Capabilities (FIN-022) — 19, every value of the canonical vocabulary

| Availability | Capabilities |
|---|---|
| `AVAILABLE` (10) | `REVENUE`, `SEVA`, `DONATION`, `PRASADAM_SALE`, `CANCELLATION`, `PRECIOUS_METAL_COUNT`, `PRECIOUS_METAL_WEIGHT`, `IN_KIND_DONATION`, `NIRANTARA_SUBSCRIPTION`, `NIRANTARA_SCHEDULE` |
| `PARTIALLY_AVAILABLE` (2) | `NIRANTARA_PAYMENT`, `PAYMENT_MODE` |
| `NOT_AVAILABLE` (7) | `PRECIOUS_METAL_VALUE`, `NIRANTARA_EXECUTION`, `EXPENSE`, `EXPENSE_CATEGORY`, `GRANT`, `GRANT_UTILISATION`, `WORKS` |

**The brief listed 18; the canonical vocabulary has 19.** The missing one was
`IN_KIND_DONATION`, and Kollur genuinely has it — donated sarees recorded with a donor-stated
value, 182,675 records from FY2016-17. Seeded `AVAILABLE`, with the reason recording that
the value is **donor-declared, not appraised**, and that it **must not be summed with auction
proceeds for the same articles** — the auction realises the value of the identical sarees, so
adding both counts them twice.

Coverage windows: revenue `2019-04-01 → 2026-07-26`; precious metals
`2015-04-01 → 2026-07-25` with FY2021-22 and FY2022-23 recorded as gaps; Nirantara payments
`2017-04-01 → 2024-03-31`; Nirantara schedule `2019-05-01 → **2027**-07-26`. Capabilities
with no reliable coverage — `NIRANTARA_SUBSCRIPTION` and all seven unavailable ones — carry
no dates rather than invented ones.

The reasons are user-facing copy, and the distinctions they preserve are the point:

- **Expenditure** — "the source system does not record expenditure … it is not zero."
- **Payment mode** — "no digital payment was recorded … **not** that the payment was made in
  cash." A receipt where the operator left the card field blank cannot be distinguished from
  a genuine cash sale.
- **Metal value** — weight is measured and reported; a monetary value "cannot be derived
  without an assumed rate, which would be an invention rather than a measurement."
- **Nirantara execution** — the preparation flag "is also set on schedules dated into 2027 —
  so it means that a schedule was generated, **not that a seva took place**."

### Source of truth (FIN-023)

`REVENUE_AMOUNT` v1 → `DailySevaNew.Amount`, effective from `2019-04-01`, `effective_to`
NULL (in force).

Filter: `Deleteflag = 0 AND TempleCode = 43 AND ReceiptDate >= '2015-01-01' AND
BillCancled = 0` for recognised revenue. The declaration also records that extraction
*retains* cancelled rows so cancelled counts and amounts can be reported separately — net is
gross minus cancelled. That resolves an apparent contradiction between ADR-008 and the
extraction sketch in `FINANCE_DATA_INTEGRATION.md`: they operate at different levels, and
the declaration now says so explicitly rather than leaving it latent.

All three measured alternatives are recorded with their evidence:

| Rejected | Measured | Why |
|---|---:|---|
| `DailySevaNewOld` | 16,982,270 rows | Byte-exact duplicate of all six archives; including it doubles every historical year |
| `DailySevaNewDetails.TotalAmount` | ₹53,77,53,226 | 41 % below the header total, with 1:1 row correspondence and zero orphans — internal inconsistency, not missing records |
| `DailySevaNewDetails.Amount × Qty` | ₹56,31,07,228 | Disagrees with the header *and* with the detail table's own column |

The rationale also carries the two correctness rules that are easiest to get wrong later:
the **rate-card price is a list price and is never revenue**, and **receipt counts are
receipts, not devotees**.

### Mapping rules (FIN-024) — 9

`SANNIDHI:DS → SEVA` · `SANNIDHI:SS → SPECIAL_SEVA` · `SANNIDHI:KN → DONATION` ·
`SANNIDHI:PS → PRASADAM_SALE` · `SEVA_CODE:430 → HUNDI_DONATION` ·
`STREAM:SAREE_DONATION → IN_KIND_DONATION` · `STREAM:SAREE_AUCTION → ASSET_REALISATION` ·
`METAL_TYPE 2 → GOLD` · `METAL_TYPE 1 → SILVER`

Source values are namespaced and **the more specific rule wins** (FIN-D-015). Without the
`SEVA_CODE:430` override, donation-box collections — 13 records averaging over a crore each —
would be reported as ordinary donations and would dominate any ranking of services devotees
purchased.

No `PAYMENT_MODE` rules are seeded: for this source, payment mode is inferred from the
*absence* of card details, which is connector logic rather than a value mapping.

No canonical value was invented; all nine come from the taxonomy already in
`FINANCE_DATA_MODEL.md`, and a test enforces that.

---

## Tests

**Added:** `backend/src/test/java/com/templeregistry/migration/KollurFinanceConfigurationMigrationTest.java`
— 27 tests.

It drives Flyway directly against a MySQL 8.0 Testcontainer and reads the seeded rows back
over JDBC, deliberately **not** loading the Spring context: every full-context test in this
project is red for two pre-existing reasons unrelated to finance (FIN-X-001, FIN-X-002), and
configuration that could only be verified after someone else fixed those would not be
verified at all. The side benefit is that it exercises the migration exactly as it will run
in production.

| Command | Result |
|---|---|
| `mvn -o test -Dtest=FinanceRevenueStagingMigrationTest` | **20/20 pass** (FIN-050) |
| `mvn -o test -Dtest=FinanceCanonicalRevenueMigrationTest` | **19/19 pass** (FIN-051/052) |
| `mvn -o test -Dtest=ConnectorRegistryTest` | **13/13 pass** (FIN-031, no Docker needed) |
| `mvn -o test -Dtest=KollurFinanceConfigurationMigrationTest` | **27/27 pass** |
| `mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*,*Connector*,*Kollur*'` | **155/155 pass** |
| `mvn -o test` (full suite) | **1010 run · 0 failures · 18 errors** |

What is asserted, beyond row counts: the conditional guard (zero rows *before* the temple is
created), `sync_enabled = 0`, that **every persisted value** in `fin_source_system` contains
no endpoint or credential pattern, that the table has no credential column at all, that each
of the 19 reasons is substantive rather than filler, that unavailable capabilities are not
expressed as zero, the coverage windows and gaps, all three rejected alternatives with their
measured figures, that no mapping value contains SQL or a source table name, and idempotency
by re-applying the whole seed.

---

## Failures

**New: none.** Pre-existing: **18**, unchanged in count, cause and location across the entire
branch (866 → 888 → 898 → 931 → 958 → 971 → 990 → 1010 tests, always the same 18 errors in
the same 4 report files).

- **FIN-X-001** — `Schema-validation: missing column [field_names_json] in table
  [declaration_clarifications]`. Mapped by the entity, created by no migration, present in
  running environments only because `ddl-auto: update` adds it. Architecture risk **R7**,
  observed. Fix is one additive `ALTER`, owned by the declaration module.
- **FIN-X-002** — the `test` profile cannot boot a full context: the test JWT public key is a
  placeholder that fails to parse, and `application.yml` carries a TiDB-only
  `connection-init-sql` that H2 rejects.

---

## Architectural Decisions

- **FIN-D-014** — the seed is conditional on temple 300001 existing. Rejected seeding
  unconditionally (orphan configuration in every fresh database) and creating the temple in
  the migration (the finance platform does not own temple records).
- **FIN-D-015** — mapping source values are namespaced and the more specific rule wins.
  Rejected a rule per service code (164 rows the source already classifies for us) and a new
  `MappingType` value (vocabulary change to solve a naming problem).
- **FIN-D-016** — `connector_type` seeded provisionally rather than made nullable. Rejected
  weakening the column for every temple to express uncertainty about one, and rejected an
  `UNDECIDED` enum value that would add permanent vocabulary for a temporary state.
- **FIN-D-017** — a configured connector that is not registered is a failure, not an absence.
  Rejected `Optional<TempleFinanceConnector> find(...)` (it reads as the safer API and is the
  opposite: it moves the decision to every caller and the failure it invites is silent), a
  no-op connector for unregistered names, and classpath discovery of connector
  implementations, which would have removed the `@Bean` registration FIN-D-008 depends on.
- **FIN-D-018** — the grain is enforced through generated key columns, because NULL is not
  equal to NULL. Rejected making the three columns `NOT NULL` with sentinels (it destroys the
  distinction between "no service was involved" and "service 0") and enforcing uniqueness in
  the loader (an invariant that holds only while the code is correct is not an invariant).
- **FIN-D-019** — `operator_ref` belongs in the grain, extending ADR-003's six-column key.
  Rejected serving R27 from a separate aggregate, which defers the same decision and leaves an
  operator column in the fact that somebody will eventually populate without knowing it is
  outside the key.
- **FIN-D-020** — `net_amount` is computed by the database and is NULL when cancellations are
  unknown. Rejected computing it in the loader (every future stage would have to reproduce the
  same arithmetic) and defaulting net to gross (which asserts that nothing was cancelled).
- **FIN-D-021** — staging is keyed on one source record per batch, and replay is a new batch.
  Rejected uniqueness on `(source_system_id, source_record_ref)` without the batch, which
  sounds stronger and would make re-extraction — and therefore every restatement
  investigation — impossible; and rejected no constraint at all, where the first pipeline bug
  that skipped the check would double a temple's revenue with nothing in the way.

---

## Known Limitations

1. **The seed is a no-op where temple 300001 does not yet exist** (FIN-D-014). This is
   correct behaviour, but it means the live database is the only place the configuration
   actually lands today, and only if the temple predates the migration. Onboarding
   (FIN-140) is the durable answer.
2. **`connector_type` is provisional** pending Q4.
3. **`connector_bean = kollurFinanceConnector` names a bean that does not exist.** Since
   FIN-031 this is a *detected* condition rather than a latent one: resolving it throws,
   naming the connector and the source system. Nothing resolves it yet in production because
   nothing synchronizes, so the failure will first be seen by whoever wires the sync
   orchestration — which is the intended moment.
4. **No source-of-truth declaration for `PRECIOUS_METAL_WEIGHT`.** Deliberately out of FIN-023
   scope, but it is the natural defence against the discarded "assume 15 g and ₹12,000 per
   item" approach returning. One row whenever wanted.
5. **Capability rows carry `last_reviewed_at` set at migration time and no reviewer.** The
   reasons are user-facing copy about a government temple's finances and would benefit from a
   named business sign-off before the dashboard renders them.
6. **Nothing calls the registry yet** (FIN-031). It resolves correctly and fails correctly,
   but until a sync orchestrator exists the failure path runs only under test. Whoever builds
   that orchestrator must let the exception fail the batch — recording it against
   `fin_sync_batch` / `fin_sync_error` — and must not catch it into a "skipped" outcome, which
   would restore precisely the silence FIN-D-017 exists to prevent.
7. **~~`fin_revenue_fact` has no writer.~~ Closed by FIN-056.** `RevenueLoadStage` is the only
   writer, through `FinRevenueFactRepository.upsert` — `INSERT … ON DUPLICATE KEY UPDATE` with
   assignment, never accumulation (FIN-D-041). The table is still empty outside tests because
   no real source row has been read (limitation 37).
8. **`financial_year` is stored, not derived by the database.** It is functionally dependent
   on `transaction_date`, so a loader that computes it wrongly — or with the wrong financial
   year start — will produce facts that disagree with their own dates. FIN-055 owns that
   computation and should have a test that a date in early April lands in the new year.
9. **The generated grain columns are verified on MySQL 8.0, not on TiDB.** TiDB supports
   stored generated columns and indexes over them, and no other syntax in V112 is unusual,
   but the deployment target has not run this migration. Worth confirming on the first
   deployment rather than assuming.
   **Still true at FIN-060, and now wider:** no migration in this project has ever run against TiDB,
   V116 included. FIN-060 adds two more MySQL-specific constructs to confirm there — an
   `ON DUPLICATE KEY UPDATE` upsert, and a nullable column inside a unique index whose NULL-is-
   distinct behaviour FIN-D-051 relies on deliberately. Both are documented as TiDB-compatible.
   Neither has been executed on TiDB, so no TiDB compatibility is claimed anywhere.
10. **Staging retention is unresolved** (Q7, 30–90 days proposed). `fin_stg_revenue` has no
   TTL, purge job or partitioning, so it grows without bound — at the first temple's volumes,
   fast. Deleting financial provenance on a schedule nobody has agreed is not a default worth
   choosing quietly, so it waits for an answer.
11. **~~`fin_stg_revenue` has a reader but still no writer.~~ Closed by FIN-057** —
   `RevenueExtractionStage`, which is generic and was **not** FIN-043's to write: FIN-043 is the
   *Kollur* connector's `extract()`, and taking a `Stream<RawRow>` into staging belongs to no
   temple (FIN-D-045). A duplicate `source_record_ref` fails the row with a `fin_sync_error` at
   stage `EXTRACT` and the rest of the batch still lands; it is never caught and skipped. The
   rows in the table are still all synthetic, because the only connector is a test fixture
   (limitation 37).
12. **`source_business_date` is advisory and currently never populated.** Nothing writes it
   yet, and normalization must derive the authoritative date from `raw_json` regardless. If a
   future reader ever treats this column as authoritative, the advisory comment in V113 is the
   only thing standing in the way.
13. **No `fin_cancellation` detail table yet.** The fact carries `cancelled_count` and
   `cancelled_amount`, which satisfies every catalogued cancellation report;
   `FINANCE_DATA_MODEL.md` §6.2 also specifies a full-detail table (465 rows across the first
   temple's entire history) that no task in the plan currently owns.
14. **~~Nothing calls the validator yet.~~ Closed by FIN-057.** `FinancePipelineOrchestrator`
   calls it second, after extraction. What is still missing is anything that calls *the
   orchestrator* — see limitation 39.
15. **Two validation rules cannot fire** (FIN-D-022). `MISSING_PAYLOAD` and
   `MALFORMED_PAYLOAD` are unreachable while `raw_json` is a `JSON` column, because MySQL and
   TiDB reject a malformed document at insert. They remain because parsing throws a checked
   exception that must be handled anyway, but they are untestable through the database.
16. **Validation has no view of configuration.** It does not check that the batch's capability
   is `REVENUE`, nor that the temple has that capability declared, nor that the payload
   contains the field the source-of-truth declaration names as authoritative. The last would be
   a genuinely generic and valuable rule — it would catch a connector that stopped emitting the
   revenue field — but it needs a decision about whether declaration field references and
   connector field names share a vocabulary. Recorded for FIN-055.
17. **~~`rows_extracted` and `rows_loaded` have no owner.~~ Closed.** `rows_extracted` is
   FIN-057's, derived from `countBySyncBatchId`; `rows_loaded` is FIN-056's, derived from a
   count of `LOADED` rows. All three counters follow the derived-not-incremented rule, and the
   orchestrator writes none of them (FIN-D-047).
18. **Validation performance is untested at scale.** Rows are processed one transaction each,
   in chunks of 500 read through an id cursor. The quadratic re-scan that the earlier offset
   form caused is gone (FIN-D-026), and the test class dropped from 221.4 s to 74.8 s as a
   side effect — but that is a few dozen rows, not a first historical load of millions. One
   transaction per row remains the right shape for isolation and an open question for bulk,
   and batching the status updates is the obvious lever if it turns out to matter.
19. **The registry is built once at worker startup.** A connector bean added at runtime would
   not appear, which is correct for an artifact whose connectors are compiled in, and worth
   knowing before anyone attempts dynamic connector loading.
20. **`SyncWorkerProfileBoundaryTest` now supplies mock persistence beans.** The worker runner
   builds a context from `SyncWorkerConfig` alone, so every pipeline stage that gains a
   repository dependency must be added to that mock list or the boundary test goes red for a
   reason that has nothing to do with the boundary. FIN-054 will hit this. The alternative —
   making the test load a real persistence context — would couple a profile-wiring check to a
   database and to FIN-X-001.
21. **Nothing verifies that the two runtimes agree with the real bean graph.** The boundary
   tests use `ApplicationContextRunner` with a hand-listed set of configurations, and
   `RegistryRuntimeContextTest` / `SyncWorkerRuntimeContextTest` use `@SpringBootTest` with
   local property overrides. Neither is the deployed startup path. Until FIN-X-002 is fixed and
   the `test` profile can boot a full context, "the worker starts" is inferred rather than
   observed.

22. **Mapping has no writer upstream and no reader downstream.** `fin_stg_revenue_mapping` is
   filled only by a test or a manual invocation, and nothing reads a decision yet: FIN-056 is
   what turns `canonical_value` into `fin_revenue_fact.category_id`. Whoever writes it must
   refuse every outcome except `MAPPED` and `UNMAPPED`, because the other three carry no
   canonical value by design (FIN-D-031).
23. **`fin_sync_error` now holds two grains.** Validation writes one error per rejected row;
   mapping writes one per distinct unresolved source value, with a NULL `source_record_ref`.
   Anything counting that table must scope by `error_stage` — `rows_rejected` now does
   (FIN-D-032), and a future reconciliation or monitoring query must too.
24. **The two `METAL_TYPE` rules seeded for the first source cannot fire.** Their `source_value`
   is a bare `1` and `2` with no namespace, so under FIN-D-028 they name no staged field. They
   are reported as `UNUSABLE_MAPPING_RULE` rather than ignored, and FIN-110 will need to
   namespace them. Nothing reads `METAL_TYPE` today, so this costs nothing yet.
25. **`SERVICE` and `PAYMENT_MODE` mapping do not exist.** `SERVICE` needs 164 rules and
   `fin_service_dim` rows that have never been created; `PAYMENT_MODE` for the first source is
   inferred from whether a card field is populated, which ADR-004 places in connector code
   rather than in a rule. `FINANCE_DATA_INTEGRATION.md` §6 also lists a `code 75 → ENTRY_FEE`
   rule that **is not in the seed** — nine rules are seeded, not ten. That gap is real and was
   left rather than invented.
26. **The namespace convention is a contract no connector has yet had to keep.** FIN-D-028 says
   a rule's namespace is the name of the staged field it reads, which obliges a connector to
   emit its payload under those logical names. No connector exists, so the agreement has only
   been exercised against synthetic payloads. The first connector is where it will be tested for
   real, and a mismatch shows up as every record `NOT_APPLICABLE` — loud, but only if somebody
   is looking at the outcome counts.
27. **The business-date declaration is unapproved and inferred.** `V115` declares `ReceiptDate`
   as the business date for the first source on the strength of FIN-023's extraction filter
   already cutting its window on that column. That is good evidence, not confirmation: nobody
   has ruled out a separate business-date column, and nobody has confirmed that `ReceiptDate` is
   not itself rewritten when a receipt is edited — which would restate history silently
   (FIN-D-012). `approved_by` is NULL, and the version in force is stamped on every fact so a
   later correction can be explained rather than discovered.
28. **A batch is normalized entirely in memory.** Groups and the staged row ids that made them
   are held for the whole batch (FIN-D-037). Bounded by the batch and not by history — but a
   first historical load is one batch, and at the first temple's volumes it will not fit.
   Windowing by business date is the lever; it is not built because no batch that size can exist
   until a connector does.
29. **Only ISO dates are accepted.** `yyyy-MM-dd`, optionally with a time part. Any other form is
   rejected rather than guessed (FIN-D-039), so a source emitting `15/06/2025` cannot be
   normalized until its connector emits ISO or somebody declares its format. No mechanism for
   declaring a format exists; if a real source needs one, that is a new metric on
   `fin_source_of_truth_decl`, not a heuristic in the parser.
30. **Payment mode is always `UNRECORDED` and service is always NULL.** Neither is a bug: no
   `PAYMENT_MODE` mapping rules are seeded for the first source (its mode is inferred from field
   presence, which ADR-004 puts in connector code), and `fin_service_dim` has no rows. Both mean
   the first facts this platform produces will carry less detail than `fin_revenue_fact` has room
   for, and several catalogued reports need that detail.
31. **~~Normalization has no caller.~~ Closed by FIN-057.** The orchestrator runs it, via the
   load, which normalizes as its first step (FIN-D-037).
32. **A source deleting records leaves this platform overstating.** The load replaces the grains a
   batch produces and touches nothing else, because an incremental window is a modification
   window and not a business-date range (FIN-D-044). If receipts are deleted at source, the
   earlier fact stands and nothing notices. Only a full reload of a date range or reconciliation
   against source totals (FIN-060) closes it, and FIN-060 is now unblocked.
33. **Nothing links a published fact back to the rows that made it.** `NormalizedFact` carries the
   contributing staged row ids in memory and discards them at the load;
   `fin_revenue_fact.source_record_ref` is NULL for any grouped fact by design (FIN-D-040). So a
   figure can be traced to a batch and a day, but not to its evidence. V113 anticipates a
   `loaded_fact_id` on staging; neither that nor a back-link table exists.
34. **Staged rows are never purged, and now they accumulate as `LOADED`.** Q7 is still unanswered
   and the load has made it sharper: every row a batch processes stays forever, and at the first
   source's volumes `fin_stg_revenue` grows without bound.
35. **The upsert uses `VALUES()`, which MySQL 8.0.20 deprecates.** It works on MySQL 8.0 and on
   TiDB, and is verified on the former by these tests. The alias form (`AS new ... = new.col`) is
   the modern spelling and was not used because TiDB compatibility for it has not been checked —
   the deployment target has still never run any of these migrations (limitation 9).
36. **~~The load has no caller either.~~ Closed by FIN-057.** Every stage now has a caller:
   `FinancePipelineOrchestrator.run(batchId)` composes extract → validate → map → load, and the
   load normalizes internally.

37. **The only connector is a test fixture.** `RevenueExtractionStage` drains whatever connector
   a source system names, but the only implementation that exists is `SyntheticConnector`, which
   lives in the test source tree. **No row of real temple financial data has ever been read.**
   `kollurFinanceConnector` is named by the seed and does not exist (limitation 3), and building
   it is FIN-043, blocked on Q4. Everything downstream is proven against synthetic rows.

38. **The watermark is never advanced** (FIN-D-049). A successful run leaves `watermark_after`
   null, so nothing can resume incrementally from where a previous batch stopped — every batch
   must be given its window explicitly. Deriving it requires knowing which source column the
   watermark is read from, which is per-connector knowledge belonging to FIN-043.

39. **Nothing creates or schedules a batch.** `run()` takes the id of a `PENDING` batch that
   something else inserted. Today that something is a test. There is no scheduler, no API and no
   trigger, deliberately: the current architecture does not need one, and adding a scheduler
   before there is a connector would schedule nothing.

40. **`FAILED` batches are never retried automatically.** `retry_count`, `max_retries` and
   `next_retry_at` exist and `findRetryable` reads them, but nothing calls it. Re-processing
   means creating a new batch — a finished batch refuses a re-run rather than resetting its own
   status, which is what keeps the audit spine honest.

41. **A batch that dies with the JVM stays `RUNNING` forever.** The orchestrator guarantees no
   batch is left `RUNNING` after an *exception*; it cannot guarantee it after a kill -9 or a pod
   eviction, because the claim has no lease and no heartbeat. Recovering such a batch is a manual
   operation today. A lease would be the fix and was not added: it is unjustified complexity
   until something other than a test runs the pipeline.

42. **`RevenueExtractionStage` is untested at source scale.** Chunks of 500 in their own
   transactions terminate structurally, and the duplicate path is covered, but the largest run
   any test performs is a handful of rows. The first source's archives are six financial years,
   and nothing has measured what that costs. Shares limitation 18's shape.

43. **Pipeline stage dependencies must be added to two database-free test contexts by hand.**
   `SyncWorkerProfileBoundaryTest` and `ConnectorRegistryTest` build contexts with mocked
   repositories to answer "which beans does each profile create" without a database. Every new
   stage dependency means a new `.withBean(...)` line in both, and forgetting one turns both
   files red for a reason unrelated to the boundary they guard. This happened three times across
   FIN-055, FIN-056 and FIN-057. It is the cost of testing the boundary without a database, and
   it is paid manually with no guard against forgetting.


44. **No connector implements `sourceTotals()`, so the only check that could catch a wrong
   extraction cannot run.** `SOURCE_VS_CENTRAL` and `SUSPECTED_SOURCE_DELETION` both record
   `NOT_AVAILABLE` against every real source today. The two local checks that do run are
   authoritative about *processing* and say nothing about whether the right rows were extracted:
   a batch that read half the source and processed that half perfectly passes both. Closing this
   is FIN-043 plus Q4, not a reconciliation change.

45. **Source-side deletion cannot be confirmed, only suspected** (FIN-D-044, FIN-D-052). The
   connector contract offers no deletion signal and no record-level period snapshot. A closed-year
   shortfall is recorded with its alternatives named; nothing is deleted, and acting on it would
   need a restatement policy that does not exist. Confirming a deletion needs a new connector
   capability — an authoritative snapshot of record identities for a bounded business-date range.

46. **Record-count reconciliation needs a declared `REVENUE_TRANSACTION_COUNT`, and the first
   source has not declared one.** Without it every fact's transaction count is null, the canonical
   count is a floor, and both the count comparison and deletion detection report `NOT_AVAILABLE`
   (FIN-D-053) — whatever the connector reports. Only the amount comparison would work.

47. **Two source systems writing the same grain overwrite each other.** `uk_frf_grain` is
   `(temple_id, transaction_date, service, category, payment_mode, counter, operator)` — it does
   **not** include `source_system_id`. That is deliberate in ADR-003, where a temple's figure for a
   day is one figure; but it means a second source reporting the same day and category replaces the
   first source's row rather than adding to it, and reconciliation then attributes the whole grain
   to whichever source wrote last. Discovered while writing the FIN-060 scope test, which had to be
   rebuilt around distinct dates. No temple has two sources today. Before one does, this needs a
   decision: either the grain gains `source_system_id`, or multi-source temples are refused.

48. **Reconciliation never runs on its own.** `reconcileBatch` is called by the orchestrator and by
   tests. The scheduled period re-verification the append-only history was designed for — the null
   `sync_batch_id` path (FIN-D-051) — has no caller, so the only rows written are batch-scoped.

49. **~~A `RECONCILE_FAILED` batch blocks nothing yet.~~ Partly closed by FIN-061.** The decision
   now exists and is deterministic: `ReconciliationGate` refuses to publish a period whose checks
   failed or whose figures nothing verified. What still does not exist is a *consumer* — see
   limitation 52. A disagreement is still invisible outside the database: no alert, no dashboard
   indicator, no API.

50. **Reconciliation performance is untested at scale.** One `sourceTotals()` call per financial
   year per batch, and the first source has eight. Each is an aggregate query against a temple's
   production database, and nothing has measured what that costs the temple. Shares the shape of
   limitations 18 and 42.

51. **The mutation harness does not kill orphaned Surefire forks.** `timeout` terminates the Maven
   process it spawned; a forked test JVM can outlive it and keep holding a Testcontainers database.
   Nothing timed out in the FIN-060 run — all eight mutations returned exit 1 with a fresh report —
   so no recorded result depends on this. It cost this project a whole discarded mutation round
   once before, so it is written down rather than remembered.

52. **The publication gate has no caller.** `ReconciliationGate` decides correctly and nothing asks
   it. Its consumers are FIN-070/071 (aggregate writers, which must call `requirePublishable`
   before replacing a period) and the Phase 8 APIs (which must report its status per period).
   Until one exists, the gate is a rule that is tested but not yet enforced anywhere, exactly as
   every pipeline stage was between FIN-053 and FIN-057.

53. **No override exists, and the gate is therefore absolute** (FIN-D-060). A period blocked by a
   variance stays blocked until the variance is corrected and a batch re-reconciles it. There is
   no authenticated caller that could be authorized to force publication, because the finance
   pipeline has no API. If an operational need appears before Phase 8, it needs an endpoint, a
   `CAN_ACT_DC` guard, and an append-only record of who overrode what and why — not a flag.

54. **The gate keeps no history of its own decisions.** It records what the evidence was, not what
   the platform concluded at a past moment. "What did we believe on 3 March" is unanswerable
   without a decision log, which was deliberately not built (FIN-D-057).

55. **There is no Source Mapper screen.** FIN-054A-BE built the API; FIN-054A-FE was not started
   and no frontend file was created or modified. The unmapped list, which is the whole operational
   point, is reachable only by an HTTP client until it is.

56. **The finance admin tests do not validate the schema against the entities.** They run with
   `ddl-auto=none`, like every finance pipeline suite, because `validate` fails on FIN-X-001 —
   `declaration_clarifications.field_names_json`, which is missing from the schema and unrelated to
   finance. A finance entity that drifts from its migration would therefore not be caught by these
   tests. FIN-X-001 is still the reason 18 errors remain in the full suite.

57. **The controller layer has no test.** Authorization, scope resolution and every refusal are
   proven by invoking `MappingAdminService` as each role, which is where the guards are. What is
   *not* covered is the HTTP mapping: that a `DuplicateResourceException` really surfaces as 409,
   that `@Valid` really rejects a missing field as 400, that the JSON shape is what
   [API_CONTRACT.md §7](API_CONTRACT.md) says. Those follow from `GlobalExceptionHandler`, which is
   existing and unchanged, but they are inferred rather than observed.

58. **A typo in a namespace is still possible to save** (FIN-D-062). The format is enforced, but a
   field name no source emits is a warning in the response, not a refusal — there is no registry of
   a source's field names to check against, only staged payloads, which may not exist yet. A client
   that ignores `warnings` will let a user create a rule that never matches anything.

59. **A mapping change still does not correct published figures, and there is no way to make it.**
   `historicalEffect` says so on every write (FIN-D-066), but saying so is all the platform does:
   no re-run trigger exists (plan decision D4), so correcting a misclassification requires somebody
   to re-run the batch by other means.

60. **No change history.** `fin_mapping_rule` keeps only the current row; what a rule said before an
   edit survives only in the `audit_data_events` detail string, which is prose, not queryable state.
   "What was this mapped to in March" remains unanswerable (plan decision D2).

61. **V117 is unverified on TiDB**, as is every migration in this project. `ALTER TABLE ... ADD
   COLUMN ... DEFAULT 0` is within the MySQL subset TiDB documents as supported, but it has not
   been run there.

---

## Q4 Status

**UNRESOLVED, and not resolved by assumption.** The seed stores `PULL_JDBC` only because the
column is `NOT NULL`, and says so in its own notes. Nothing in the configuration presumes the
platform can reach Kollur: there is no endpoint, no host, no URL, and synchronization is
disabled. `ConnectorType` still treats all four mechanisms as equals, and `SourceCredentials`
carries an *optional* principal so a push agent's shared key fits the same shape as a
database user.

If the answer is `PUSH_AGENT`: one `UPDATE` to `connector_type`, and a different connector
implementation. The canonical model, aggregation, APIs and dashboard are untouched.

## Q5 Status

**UNRESOLVED, and no credential was introduced.** `credential_ref = 'kollur-readonly'` is an
alias. No credential exists in Git, YAML, SQL, the migration, the tests, or
`fin_source_system` — and a test scans **every persisted value** in that table for endpoint
and credential patterns, so a future edit that smuggles one into a notes field fails the
build.

Choosing the permanent store still changes one `@Bean` method in `SyncWorkerConfig`.

---

## How To Continue

1. Read this file, then `IMPLEMENTATION_STATUS.md` and `IMPLEMENTATION_TASKS.md`.
2. `git status` and `git log --oneline -10` on `feature/db-integration`.
3. Confirm the baseline:
   `cd backend && mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*,*Connector*,*Kollur*,*RevenueStaging*'`
   — expect **370 passing, 0 failures, 0 errors** as of FIN-060 (requires Docker). The filter needs
   `*Normaliz*`, `*RevenueLoad*`, `*Orchestrator*` and `*Reconcil*` too; the pattern in step 3
   above is the current one. A stage whose test class matches none of these is not in the
   baseline, which is how a failing termination test once survived being recorded as green.

   **The last pattern was added because it was missing.** Without `*RevenueStaging*` the filter
   matches none of FIN-053's 25 tests, so the documented "155 passing" was a real number for a
   suite that silently excluded the code it was meant to cover — which is how a failing
   termination test survived being recorded as green. Any new pipeline stage needs its class to
   match this filter, or it is not in the baseline.
4. Implement one task. Do not implement a connector, and do not implement Kollur-specific
   anything outside a connector.
5. Anything that can reach a source system is registered in `SyncWorkerConfig` as a `@Bean`,
   never as a `@Component` — `FinanceIntegrationBoundaryTest` fails the build otherwise.
6. Run the suite, update the four tracking documents, commit as `FIN-0xx ...`.

Do not repeat the architectural analysis. It is complete and in `docs/finance/`.

---

## NEXT ACTION

The pipeline runs end to end, checks itself, and now knows what its own checks mean. **Nothing
fills it with real data, and nothing calls the gate.**

**FIN-070/071, the aggregates, are the recommended next task.** They are the gate's first consumer:
an aggregate writer must call `ReconciliationGate.requirePublishable` before replacing a period, so
that a blocked period keeps its previous figures rather than gaining corrupted ones. Building them
turns FIN-061 from a tested rule into an enforced one (limitation 52), and FIN-072 then has both
dependencies it needs.

Build them against the gate, not around it. The guard throws for exactly that reason: an aggregate
writer that ignores a returned boolean is easy to write by accident, and one that swallows a
`PublicationBlockedException` is not.

**FIN-043, the Kollur connector, remains the single thing between this platform and a real figure**
— and, since FIN-060, between it and a meaningful reconciliation. It is blocked on **Q4**. Whoever
writes it should implement `sourceTotals()` at the same time and from a separate query, or the
platform gains data it cannot verify and every gate verdict stays `NOT_AVAILABLE`.

Recommended order: FIN-070/071, then FIN-072, then the Phase 8 APIs. FIN-043 slots in whenever Q4
is answered, and watermark advancement goes with it.

Four things to decide rather than inherit:

- **Whether an override is ever needed** (FIN-D-060, limitation 53). None exists, and none can be
  authorized until Phase 8 gives the pipeline an authenticated caller. If an operational need
  appears first, it needs an endpoint, a `CAN_ACT_DC` guard and an append-only record of who
  overrode what and why — not a flag.
- **Whether a fact records which staged rows produced it.** `fin_revenue_fact.source_record_ref` is
  deliberately NULL for grouped facts (FIN-D-040), so reconciliation compares counts and amounts
  per period and **cannot trace a figure to its source records**. The gate inherits that limit.
- **Whether the canonical grain should include `source_system_id`** (limitation 47). It does not, so
  two sources reporting the same day and category overwrite each other. No temple has two sources
  yet. One will.
- **Staging retention (Q7).** Still unanswered: rows stay `LOADED` forever, and at the first
  source's volumes `fin_stg_revenue` grows without bound.

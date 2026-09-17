# Finance Platform — Handoff

**Updated:** 2026-09-17 (FIN-054)
**Branch:** `feature/db-integration`
**Read first**, then [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md),
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Task Status

| Task | Status |
|---|---|
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
FIN-052), the staging table that feeds it (FIN-050), and the first two stages between them:
validation (FIN-053) and mapping (FIN-054).

Both ends of the pipeline exist and half the middle now runs. A staged record is judged, and
the judgement is recorded in a way that cannot be lost — rejected rows keep their payload and
gain one coded `fin_sync_error` each, so `rows_rejected` is explainable row by row. A validated
record then has its source values translated into canonical ones from configuration alone, with
every decision recorded against the record it is about, including the several distinct reasons
for not reaching one.

**What does not work yet.** No connector implementation, no extraction, no normalization, no
financial-year derivation, no loader, no aggregation, no API, and no orchestrator to run the
stages in order. The dashboard is still the static HTML file. Nothing writes to staging yet, so
validation and mapping run only against rows a test or an operator puts there; no row of temple
financial data has been read, `fin_revenue_fact` is empty, and no credential exists anywhere.

---

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
7. **`fin_revenue_fact` has no writer.** The constraint that makes loading idempotent is
   proven, but the loader that relies on it is FIN-056. Until then the canonical tables are
   empty by design, and the upsert shape the test demonstrates
   (`INSERT … ON DUPLICATE KEY UPDATE`) is the intended write path, not a guess.
8. **`financial_year` is stored, not derived by the database.** It is functionally dependent
   on `transaction_date`, so a loader that computes it wrongly — or with the wrong financial
   year start — will produce facts that disagree with their own dates. FIN-055 owns that
   computation and should have a test that a date in early April lands in the new year.
9. **The generated grain columns are verified on MySQL 8.0, not on TiDB.** TiDB supports
   stored generated columns and indexes over them, and no other syntax in V112 is unusual,
   but the deployment target has not run this migration. Worth confirming on the first
   deployment rather than assuming.
10. **Staging retention is unresolved** (Q7, 30–90 days proposed). `fin_stg_revenue` has no
   TTL, purge job or partitioning, so it grows without bound — at the first temple's volumes,
   fast. Deleting financial provenance on a schedule nobody has agreed is not a default worth
   choosing quietly, so it waits for an answer.
11. **`fin_stg_revenue` has a reader but still no writer.** FIN-053 drains it; the extraction
   path that fills it is FIN-043 and does not exist, so every row in the table today was put
   there by a test. A constraint violation while staging must fail the batch with a
   `fin_sync_error` at stage `EXTRACT`, not be caught and skipped.
12. **`source_business_date` is advisory and currently never populated.** Nothing writes it
   yet, and normalization must derive the authoritative date from `raw_json` regardless. If a
   future reader ever treats this column as authoritative, the advisory comment in V113 is the
   only thing standing in the way.
13. **No `fin_cancellation` detail table yet.** The fact carries `cancelled_count` and
   `cancelled_amount`, which satisfies every catalogued cancellation report;
   `FINANCE_DATA_MODEL.md` §6.2 also specifies a full-detail table (465 rows across the first
   temple's entire history) that no task in the plan currently owns.
14. **Nothing calls the validator yet.** `revenueStagingValidator` is a worker bean with no
   caller: the orchestrator that would run extraction, then validation, then the rest is not
   built. Until it exists, validation runs only from tests or a manual invocation.
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
17. **`rows_extracted` and `rows_loaded` have no owner.** FIN-053 sets only `rows_rejected`,
   derived. Whoever writes staging (FIN-043) and the loader (FIN-056) must decide theirs, and
   should follow the same derived-not-incremented rule.
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
   — expect **180 passing, 0 failures, 0 errors** (8 m 29 s; requires Docker).

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

Implement **FIN-055**: normalization — turn each mapped staged record into the canonical daily
shape, which means the two things every stage so far has deliberately deferred: **amounts and
dates**.

It is next because its inputs now all exist. Validation guarantees the payload is readable,
mapping supplies the canonical category, and the source-of-truth declaration (FIN-023) names
which source field is authoritative for `REVENUE_AMOUNT` and under what filter. FIN-055 is the
first stage entitled to read that declaration, and the first that may look at a money value at
all.

What it owns, and what must not slip:

- **The financial year is computed, not carried.** `fin_revenue_fact.financial_year` is stored
  and functionally dependent on `transaction_date`, so a wrong computation produces facts that
  disagree with their own dates. It needs a test that a date in early April lands in the new
  year, and it must use the canonical `2025-26` string form, never `20252026`.
- **Amounts are exact decimals, parsed from strings.** No floating point, no rounding, and an
  unparseable or absent amount is a recorded failure — never zero. `cancelled_amount` NULL,
  `0` and a positive value are three different states (FIN-D-020).
- **The business date is not the extraction date.** `source_business_date` in staging is
  advisory and currently never populated; the authoritative date comes from `raw_json` against
  the declaration. A source editing a two-year-old receipt must not restate history.
- **Only decided records may proceed.** `MAPPED` and `UNMAPPED` have a canonical category;
  `AMBIGUOUS`, `NOT_APPLICABLE` and `INVALID_CONFIGURATION` carry none by design (FIN-D-031),
  and normalization must refuse them rather than substituting one.
- **Many staged rows become one fact.** The collapse to daily grain happens here, where it is
  visible and testable, and every grain column a connector groups by must be a subset of
  `uk_frf_grain` (FIN-D-019).

One open question it should settle rather than inherit: FIN-053 noted that a genuinely generic
and valuable rule — checking that the payload contains the field the source-of-truth
declaration names as authoritative — needs a decision about whether declaration field
references and connector field names share a vocabulary. FIN-054 has now answered the
equivalent question for mapping rules (FIN-D-028: the namespace is the field name), so there is
a precedent to follow or to reject deliberately.

After that, **FIN-056** (the idempotent load through `uk_frf_grain`), and then the orchestrator
that no task currently owns — see limitation 14.

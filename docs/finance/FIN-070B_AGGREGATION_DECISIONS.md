# FIN-070B — Aggregation Architecture Decisions

**Task:** FIN-070B (decision document only)
**Status:** DECIDED — FIN-070, FIN-071 and FIN-072 remain `NOT_STARTED`
**HEAD at analysis:** `f984720f7df0ec15f571ec392ae9262f539d1b57`
**Date:** 2026-09-18
**Supersedes:** the open decisions D1, D3, D5 and D8 in
[FIN-070A_AGGREGATION_PLAN.md](FIN-070A_AGGREGATION_PLAN.md) §18
**Changes to production code:** none. **Migrations created:** none. **Fact model changed:** no.

---

## 1. Executive summary

Four decisions blocked FIN-070. All four are resolved below. Three of them changed shape once the
source was read again, and in two cases the new evidence contradicts reasoning in FIN-070A. Those
corrections are stated plainly rather than quietly folded in.

**D1 — ACCEPT, but the deadline FIN-070A gave was wrong.** `source_system_id` should be added to
`uk_frf_grain`. FIN-070A argued this had to happen "while the table is empty, because it will never
be this cheap again". That reasoning does not survive inspection: **adding a column to a UNIQUE
key widens it, so no existing row can violate the new constraint.** The DDL is a safe index swap
whether the table holds zero rows or fifty million, and `source_system_id` is already `NOT NULL`
and populated, so no backfill is required at any point. What actually expires is different and
narrower: the ability to recover facts *already destroyed* by a second source overwriting the
first. Nothing has been destroyed, because no temple has a second source. The real deadline is
therefore **before a second source system is registered for any temple**, not before FIN-070.

D1 is consequently **not a hard blocker for FIN-070**. It is accepted anyway, as its own small
task, because it costs one index swap and permanently removes a class of silent revenue loss.

**D3 — Option A is the policy. Option C is the approved successor and is not implemented.** Facts
are immutable; a mapping change affects only future extraction; no aggregation path corrects
history. Here too the source changed the analysis, in a direction that helps: FIN-070A assumed a
correction would require re-contacting the source. It would not.
`uk_fsrm_row_type UNIQUE (stg_revenue_id, mapping_type)` exists, per V114's own comment, "so a
re-run after a rule correction updates the decision instead of adding a second one" — the mapping
stage is already idempotent over **retained staging rows**, so a remap needs no source access and
does not violate ADR-001. The missing piece was never re-extraction. It is a **fact retirement
path**, which does not exist and which this task does not invent.

**D5 — `category_id` is `NOT NULL` and part of the aggregate unique key. Totals are computed, never
stored.** The decisive argument is in this codebase already: V112 hit the fact that MySQL and TiDB
treat NULLs in a unique index as distinct, and solved it with three generated stand-in columns
(FIN-D-018). The documented "`category_id` nullable, null = all" design would reproduce that exact
defect one layer up — the unique key would not constrain the total row, so every aggregation run
would **insert another total** instead of replacing it, and a table that is supposed to be
idempotent would grow a duplicate on every pass.

**D8 — ACCEPT ADR-011, with three specific amendments, and raise a governance point that is not
about ADR-011.** All **eleven** finance ADRs are marked `Proposed`; none has ever been accepted. So
ADR-011's status is not an anomaly to be fixed in isolation, and accepting one of eleven would
leave a record nobody can read. ADR-011's *decision* is sound and now has implementation evidence
behind it that it did not have when written — FIN-061 built the gate its argument depends on. Its
*text* overstates performance and misdescribes one column. Both are fixed by amendment, not by
rejection.

One further finding, carried forward from FIN-070A §10.4 and confirmed again here: **re-extraction
after a mapping change orphans a fact and double-counts the money.** D3 is the decision that
contains it. Under Option A it cannot occur, because nothing re-extracts.

---

## 2. Source inspection evidence

Everything below was read at HEAD `f984720`. Documentation was used to find things, not to
establish them.

| Claim | Evidence |
|---|---|
| `uk_frf_grain` has seven columns and omits `source_system_id` | `V112__finance_canonical_revenue.sql:186` |
| Three grain columns are generated NULL stand-ins | `V112:175-183` — `grain_service_key`, `grain_counter_key`, `grain_operator_key`, with the MySQL/TiDB NULL-uniqueness rationale in the comment |
| The upsert assigns, never accumulates | `FinRevenueFactRepository.upsert` — `ON DUPLICATE KEY UPDATE … gross_amount = VALUES(gross_amount)` |
| `source_system_id` is in the upsert's UPDATE list | same — so the last writer's source id overwrites the previous one |
| Nothing deletes a fact | `grep delete` across `FinRevenueFactRepository` and `RevenueLoadStage` returns only `errors.deleteBySyncBatchIdAndErrorStage` |
| No migration seeds facts | `grep "INSERT INTO fin_revenue_fact" db/migration/*.sql` → no matches |
| No production connector exists | `grep -rln "implements TempleFinanceConnector"` matches **only four test classes**; `RevenueExtractionStage:108` resolves a connector through `ConnectorRegistry`, which throws `notRegistered` otherwise |
| Therefore `fin_revenue_fact` is empty in every real environment | Follows from the two rows above: the only writer is the load stage, which is only reachable through extraction, which requires a registered connector |
| Mapping decisions are already re-runnable and idempotent | `V114:123` `uk_fsrm_row_type UNIQUE (stg_revenue_id, mapping_type)`, with the stated purpose of surviving "a re-run after a rule correction" |
| Source record identity exists at staging, per source, across batches | `V113:115` `uk_fsr_batch_record UNIQUE (sync_batch_id, source_record_ref)`, `source_record_ref NOT NULL`, plus `idx_fsr_source_record ON (source_system_id, source_record_ref)` commented "Following one source record across extractions" |
| Source record identity does **not** exist at fact level | `RevenueNormalizationStage.NormalizedFact` javadoc: `sourceRecordRef` "is carried only where one record produced the fact… Null there means 'several'" (FIN-D-040) |
| The fact↔staged-row link is never persisted | `stagedRowIds` lives on the in-memory `NormalizedFact`; `RevenueLoadStage.write` uses it only to call `staging.markLoaded(...)`. No column anywhere records it |
| The gate's unit of decision | `ReconciliationGate.evaluate(long templeId, long sourceSystemId, String financialYear)` |
| Reconciliation writes only two period scopes | `RevenueReconciliationStage` — every `PeriodType` reference is `FULL_HISTORY`/`"ALL"` or `FINANCIAL_YEAR`/`year` |
| `uk_ftc_temple_capability` is `(temple_id, capability)` | `V110:100`, although `source_system_id` on that table is `NOT NULL` (`V110:83`) |
| `uk_fsd_temple_service` is `(temple_id, service_code)` | `V112:98` — the same single-source assumption |
| All eleven finance ADRs are `Proposed` | `grep -H "^\*\*Status:\*\*" docs/finance/adr/*.md` |
| Finance test suites | 22 test classes under `*finance*`; the gate has `ReconciliationGateTest` |

---

## 3. Current system constraints

These bind every decision below. They are properties of the code, not preferences.

1. **One writer to facts.** `RevenueLoadStage` → `upsert`. No other path writes, updates or
   deletes a `fin_revenue_fact` row.
2. **No fact deletion exists at all.** Not a policy choice yet — simply absent.
3. **Measures are assigned, not accumulated**, so a fact's values are wholly attributable to the
   single batch named in its `sync_batch_id`. This matters for D3 and is not obvious.
4. **A fact cannot be traced to its source records** when several records produced it (FIN-D-040),
   and the staged-row association is not persisted at all.
5. **Staging is retained indefinitely** (Q7 unresolved), which means the raw payload for a past
   batch is still available for a remap without contacting the source.
6. **Reconciliation evidence exists only at `FULL_HISTORY` and `FINANCIAL_YEAR` scope.**
7. **`fin_revenue_fact` is empty in every real environment**, because no connector exists.
8. **TiDB is unverified** for every finance table. Nothing here changes that.
9. **No finance query has ever been benchmarked**, at any scale.
10. **The pipeline has no authenticated principal.** Worker services carry no `@PreAuthorize`.

---

## 4. D1 — Fact grain correction

**Decision ID:** D1
**Question:** Must `source_system_id` be added to `uk_frf_grain`, and if so, when?
**Recommendation:** **ACCEPT** — as a standalone task before a second source system is registered
for any temple. **Not a blocker for FIN-070.**

> **EXECUTED by FIN-052A, migration `V118__finance_fact_grain_source_system.sql`** (FIN-D-067).
> The fact grain is now eight columns. `fin_temple_capability` and `fin_service_dim` remain
> deferred under D9, as this decision specified.

### 4.1 Evidence

```sql
CONSTRAINT uk_frf_grain UNIQUE (
    temple_id, transaction_date, grain_service_key, category_id,
    payment_mode, grain_counter_key, grain_operator_key)          -- V112:186
```

```sql
ON DUPLICATE KEY UPDATE
    sync_batch_id    = VALUES(sync_batch_id),
    source_system_id = VALUES(source_system_id),   -- the second source takes ownership
    …
    gross_amount     = VALUES(gross_amount),       -- and replaces the first source's money
```

Two source systems reporting the same temple, date, service, category, payment mode, counter and
operator collide on this key. The second write does not add to the first; it **replaces** it, and
takes the row's `source_system_id` and `sync_batch_id` with it. The first source's revenue is gone
and nothing records that it ever existed.

`fin_source_system` permits this today: `uk_fss_temple_system UNIQUE (temple_id, system_code)`
constrains the code, not the count. Nothing anywhere refuses a second source.

### 4.2 Is the table empty in real environments?

**Yes, and this is derived rather than assumed.** No migration inserts into `fin_revenue_fact`.
The only writer is `RevenueLoadStage`, reachable only through `RevenueExtractionStage`, which at
line 108 resolves a connector through `ConnectorRegistry` and throws `notRegistered` when none
matches. `grep -rln "implements TempleFinanceConnector"` matches four **test** classes and no
production class. FIN-043, the first connector, is `NOT_STARTED` and blocked on Q4.

### 4.3 Correction of FIN-070A's reasoning

FIN-070A §6.4 argued the change must happen now because the empty table "makes it a
drop-and-recreate of one unique index with no data to migrate", and that "it will never again be
this cheap".

That is wrong in a way that changes the schedule. **Widening a unique key cannot be violated by
existing rows** — every row that was unique on seven columns is still unique on eight. And
`source_system_id` is already `NOT NULL` and populated on every row, so there is no backfill
whether the table holds zero rows or fifty million. The migration below is the same migration
either way:

```sql
-- Illustrative only. NOT created by this task.
ALTER TABLE fin_revenue_fact DROP INDEX uk_frf_grain;
ALTER TABLE fin_revenue_fact ADD CONSTRAINT uk_frf_grain UNIQUE (
    temple_id, source_system_id, transaction_date, grain_service_key,
    category_id, payment_mode, grain_counter_key, grain_operator_key);
```

The only cost that grows with data is the index rebuild, which is an operational window, not a
correctness problem.

**What genuinely expires** is narrower and worth stating precisely: once a second source has
overwritten a first source's facts, the overwritten figures cannot be recovered, because the
`ON DUPLICATE KEY UPDATE` destroyed them and no history of the prior values exists. A later
migration would fix the schema and could not fix the data. The deadline is therefore the **second
source system**, not the first fact.

### 4.4 Other tables with the same single-source assumption

| Table | Key | Single-source? | Assessment |
|---|---|---|---|
| `fin_temple_capability` | `uk_ftc_temple_capability (temple_id, capability)` | **Yes**, although `source_system_id` is `NOT NULL` on the row | **Defer with conditions** — see below |
| `fin_service_dim` | `uk_fsd_temple_service (temple_id, service_code)` | **Yes** | Two sources using the same service code for one temple would collide. Not previously recorded. Same deadline as D1 |
| `fin_source_of_truth_decl` | `uk_fsotd_source_metric_version (source_system_id, metric, version)` | No — correctly source-scoped | No action |
| `fin_mapping_rule` | `uk_fmr_source_type_value (source_system_id, mapping_type, source_value)` | No | No action |
| `fin_stg_revenue` | `uk_fsr_batch_record (sync_batch_id, source_record_ref)` | No — the batch carries the source | No action |
| `fin_stg_revenue_mapping` | `uk_fsrm_row_type (stg_revenue_id, mapping_type)` | No | No action |
| `fin_reconciliation_result` | `uk_frr_batch_check (sync_batch_id, capability, check_type, metric, period_type, period_key)` | No — the batch carries the source | No action |

**`fin_temple_capability` is deferred, not accepted**, because unlike the fact grain it is not a
mechanical widening. Adding `source_system_id` to that key forces a design question this task
cannot answer alone: *when two sources for one temple disagree about whether the temple records
cancellations, what does the temple-level availability answer become?* `REVENUE` available from
one source and not the other is a real and awkward state, and the API's metric envelope
(`API_CONTRACT.md` §2) has one `availability` field per metric, not one per source.

**Conditions on the deferral:** resolved before a second source system is registered for any
temple; tracked as an open decision (§21, D9); does not block FIN-070, because with one source per
temple the existing per-temple lookup
(`FinTempleCapabilityRepository.findByTempleIdAndCapabilityAndDeletedFalse`) returns the right
answer.

### 4.5 Options considered

| Option | Assessment |
|---|---|
| **Accept now, as a prerequisite of FIN-070** | Safe, but couples an unrelated schema change to aggregation delivery and delays FIN-070 for a risk that cannot materialise yet |
| **Accept now, as a standalone task** ✅ | One index swap, independently testable and reviewable. Removes the failure class permanently |
| **Reject — keep the grain as ADR-003 defines it, and refuse multi-source temples in code** | Requires a runtime guard in the aggregator *and* in onboarding, both of which can be forgotten. Trades a one-line DDL for two enforcement points |
| **Defer until a second source appears** | The change is safe then too — but the pressure of onboarding is the worst moment to be amending a canonical grain, and a missed guard means silent loss before anyone notices |

### 4.6 Consequences

| Area | Consequence |
|---|---|
| **Existing facts** | None. No row is modified, deleted or rewritten. Widening a unique key cannot invalidate an existing row |
| **Upsert behaviour** | Unchanged in shape. `source_system_id` moves from the UPDATE list to the key, so a second source's write becomes an INSERT rather than an overwrite. `RevenueLoadStage` needs no change — it already passes the value |
| **Aggregation** | Per-source aggregation becomes *truthful* rather than merely well-formed. Temple-level totals become `SUM` across the temple's sources, which is what they should always have been |
| **Reconciliation** | Improves. `sumGrossForSourceAndFinancialYear` already filters by source and would stop being distorted by another source's overwrite. No code change |
| **Multi-source isolation** | Achieved at the fact layer. Not yet at capability (deferred) or service dimension (same deadline) |
| **Historical data** | None exists. This is the whole reason the decision is cheap |
| **ADR-003** | Amended, not overturned. "A temple's figure for a day is one figure" becomes true after summing across sources rather than at the row. That is arguably more honest: two systems genuinely did report separately |

### 4.7 What this decision does not solve

It does not make the platform multi-source-capable. `fin_temple_capability` and `fin_service_dim`
still assume one source; the availability envelope still has one slot per metric; onboarding still
has no guard; and no connector exists to be the second source. D1 removes the *silent* failure and
leaves the *visible* work.

---

## 5. D3 — Orphan-fact restatement policy

**Decision ID:** D3
**Question:** What happens to previously loaded facts when a mapping changes, and how is
double-counting prevented?
**Recommendation:** **Option A** is the enforced policy. **Option C** is the approved successor
design and is **not implemented**.

### 5.1 Evidence — the defect, restated from code

`category_id` is a grain column (`V112:186`). The upsert has no delete path. Nothing else deletes
facts. Therefore a re-extraction that changes any grain column inserts a new row and leaves the old
one:

```
before remap:  (300001, 2025-06-14, svc NULL, UNMAPPED, CASH, …)  gross 4,20,000
after remap:   (300001, 2025-06-14, svc NULL, UNMAPPED, CASH, …)  gross 4,20,000   ← survives
               (300001, 2025-06-14, svc NULL, SEVA,     CASH, …)  gross 4,20,000   ← inserted
```

`sumGrossForFinancialYear` now returns ₹8,40,000 for ₹4,20,000 of revenue.
`RevenueLoadStage`'s javadoc describes precisely this mechanism for *source-side* deletions and
states that detecting it "needs either a full reload of a date range or a reconciliation against
source totals (FIN-060)". No connector implements `sourceTotals()`, so today it would publish
silently.

### 5.2 Evidence that improves on FIN-070A — a remap needs no source access

FIN-070A treated correction as necessarily requiring re-extraction. Two constraints say otherwise:

- `uk_fsrm_row_type UNIQUE (stg_revenue_id, mapping_type)`, whose V114 comment is explicit:
  "One current decision per staged row per mapping type, so a re-run after a rule correction
  updates the decision instead of adding a second one."
- Staged rows are retained indefinitely (Q7), with `raw_json NOT NULL` holding the original
  payload.

So `MAP` then `LOAD` can be re-run over an already-extracted batch, from data this platform already
holds, without contacting a temple database — which keeps ADR-001 intact. **The blocker was never
re-extraction. It is that the load has no way to retire the fact its earlier decision produced.**

### 5.3 Evidence that bounds any future retirement path

Because measures are *assigned*, a fact's values are wholly attributable to the one batch named in
`sync_batch_id`. That makes a batch-scoped retirement rule coherent in principle — "retire the
facts of batch B that this re-load did not rewrite".

It is not sufficient, and the gap is precise: if a **later** batch C restated the same grain, the
stale row's `sync_batch_id` is now C, so a re-load of B would not retire it and the double count
survives. Any future Option C implementation must handle that case explicitly or state that it does
not.

### 5.4 Options compared

| | **A — Facts immutable** | **B — Snapshot authoritative** | **C — Controlled restatement** | **D — Block mapping changes after publication** |
|---|---|---|---|---|
| **Data correctness** | Correct but incomplete: a known-wrong classification stays wrong until re-onboarding | Highest, if identity exists | Correct, and correctable | Correct only if nothing was ever mis-mapped |
| **Auditability** | Total. Nothing changes, so nothing needs explaining | Needs a lifecycle log | Strongest: supersession is recorded, old result preserved | Trivial |
| **Government reporting suitability** | Acceptable — published figures never move under a reader | Acceptable | **Best** — a correction is visible, attributable and dated | Poor: a figure known to be mis-classified cannot be fixed |
| **Implementation complexity** | **Zero.** It is the current behaviour | Very high | High | Low to build, high to live with |
| **Corrects history?** | No | Yes | Yes | No |
| **Double-counting risk** | **None** — nothing re-loads | Low, if identity is complete | Low, if retirement is complete (§5.3) | None |
| **Current schema compatible?** | **Yes** | **No** — needs fact-level source record identity, deliberately removed by FIN-D-040, and a persisted fact↔staged-row link that does not exist | **No** — needs a retirement/supersession path that does not exist | Partly — needs a per-rule publication state that does not exist |
| **FIN-061 compatible?** | Yes — nothing changes, nothing republishes | Yes | Yes, if every restatement re-reconciles before republishing | Yes |
| **Operational burden** | None, but an unmapped value stays unmapped in history forever | Very high | Moderate: an explicit, authorized, audited action | High: administrators blocked from fixing a known error |

### 5.5 Recommendation and reasoning

**Option A governs FIN-070, FIN-071 and FIN-072.** Facts are immutable. No aggregation path
corrects, restates or retires anything.

Option B is rejected on a schema fact, not a preference: a fact produced by several source records
carries `source_record_ref = NULL` by deliberate design (FIN-D-040), and the `stagedRowIds`
association exists only in memory during a load and is never persisted. A snapshot-reconciliation
policy that cannot say which facts a source record contributed to is not implementable, and
inventing the identity now would reverse a decision made for good reasons at FIN-056.

Option D is rejected for two reasons. It is **unenforceable today** — there is no publication state
on a mapping rule, and the gate's verdict is derived per `(temple, source, financial year)`, not
per source value, so "has this rule's data been published" is not a question the schema can answer.
And it is **harmful**: it would make `UNMAPPED` revenue permanently unclassifiable, which defeats
the purpose of the `UNMAPPED` category, whose V112 description exists precisely so that such values
"must be resolved by adding a mapping rule rather than absorbed into Other Income".

Option C is the right destination and is **not buildable on the current schema**. It is recorded
as the approved successor with prerequisites in §5.7 and §16.

### 5.6 The nine mandatory answers

| Question | Answer |
|---|---|
| **Can an administrator change a mapping after data has been loaded?** | **Yes.** This is already true and shipped in FIN-054A/B. The change affects future pipeline runs only |
| **Can an administrator change a mapping after data has been published?** | **Yes**, on the same terms. Nothing is blocked, and nothing published moves |
| **What happens to previously loaded facts?** | **Nothing.** They are not updated, superseded or deleted. They retain the classification in force when they were loaded |
| **How is a correction initiated?** | **It cannot be, today.** There is no supported path from a corrected mapping to a corrected historical figure. Under Option C it would be an explicit, authorized restatement action — which does not exist |
| **Who is allowed to initiate it?** | Nobody, because it does not exist. When it does: `CAN_ACT_DC` (SUPER_ADMIN, DISTRICT_COLLECTOR), matching the write authority FIN-054A-BE already enforces for mapping rules. Not `DC_STAFF`, not `AUDITOR` |
| **How is the old result preserved?** | Under Option A the old result *is* the result — nothing overwrites it. Under Option C, supersession must be recorded rather than the row deleted; the mechanism is undesigned |
| **How do we prevent double-counting?** | By not re-loading. FIN-072 rebuilds **aggregates from facts**; it must never re-run `MAP` or `LOAD`. An acceptance test asserts that no fact row's `updated_at` changes across an aggregation run |
| **Is a future rerun required?** | **Yes**, for historical correction to be possible at all. It is deliberately not built, and this document does not design, propose or authorize one |
| **What must be implemented before historical corrections are allowed?** | §5.7 |

### 5.7 Prerequisites for Option C — none of which exist

1. A **fact retirement or supersession mechanism**, with the cross-batch gap in §5.3 resolved or
   explicitly documented as unhandled.
2. A **restatement scope definition** — the bounded set of `(temple, source, business-date range)`
   a restatement covers, since incremental batch windows are modification windows and say nothing
   about business periods (ADR-006).
3. An **authorized trigger** with `CAN_ACT_DC`, an append-only record of who restated what and
   why, and same-transaction audit on the FIN-D-063 pattern.
4. **Re-reconciliation before republication.** A restated period must pass the gate again; a
   restatement that republishes on a stale verdict defeats FIN-061.
5. Ideally, **`sourceTotals()` in a connector**, so a restatement's result can be checked against
   the temple rather than trusted.

Until all five exist, historical correction is not supported and must not be described as though
it were.

### 5.8 What this decision does not solve

It does not fix a single mis-classified historical figure. A temple onboarded with an incomplete
mapping table will carry `UNMAPPED` revenue in its history indefinitely, visible and labelled but
not reclassified. That is an accepted cost, and it is the honest one: the alternative on today's
schema is a mechanism that would double the money.

---

## 6. D5 — Category as a key column

**Decision ID:** D5
**Question:** Is `category_id` nullable with `NULL = all`, or a non-null key column with totals
computed?
**Recommendation:** **`NOT NULL`, part of the unique key. Totals are computed at read time and
never stored.**

### 6.1 Evidence — the platform has already been bitten by this

V112 documents it directly:

> Three of the six grain columns are legitimately nullable, and MySQL and TiDB both treat NULLs in
> a UNIQUE index as distinct from one another — so a unique key over the nullable columns
> themselves would happily accept the same fact twice and double the reported revenue.

That is FIN-D-018, and the fix was three generated stand-in columns. The documented aggregate
design in `FINANCE_DATA_MODEL.md` §7 — `category_id` nullable, "null = all" — walks into the same
trap one layer up, and this time there is no generated column proposed to catch it.

### 6.2 What would actually happen

With `uk_farp_grain (temple_id, source_system_id, period_type, period_key, category_id, payment_mode)`
and a NULL `category_id` total row:

- The unique key **does not constrain the total row**, because NULL ≠ NULL.
- `ON DUPLICATE KEY UPDATE` never fires for it.
- Every aggregation run **inserts another total row**.
- A table whose defining property is idempotency (§15.3 of FIN-070A, acceptance criterion 5) grows
  a duplicate on every pass, and a report reading "the total" gets whichever the query plan
  returned.

This is not a hypothetical: it is the identical mechanism V112 documents, in a table that would be
written far more often than the facts.

### 6.3 Alternatives compared

| Alternative | Assessment |
|---|---|
| **`category_id` NOT NULL, in the key; totals computed** ✅ | One representation of each number. Unique key fully constrains every row. Upsert is correct. Totals are a `SUM` over ~12 rows |
| `category_id` nullable, `NULL = all` | Breaks uniqueness (§6.2). Also creates two representations of one number that must be kept in agreement by hand — and the first partial failure puts the table in contradiction with itself |
| `category_id` nullable with a generated stand-in (`IFNULL(category_id, 0)`) | Fixes the uniqueness defect and keeps the double-representation problem. Copies a workaround that V112 needed because those columns were *legitimately* nullable — `category_id` is not |
| Separate detail and total tables | Removes the key problem, keeps the two-representations problem, and adds a table. Also needs the two kept in step across every rebuild |
| A sentinel category row `ALL` in `fin_revenue_category` | Puts a non-category in the canonical taxonomy, where a report would rank it alongside `SEVA`. Rejected |

### 6.4 The mandatory definitions

| Question | Answer |
|---|---|
| **Is `category_id` nullable?** | **No.** `NOT NULL`, matching `fin_revenue_fact.category_id` |
| **What does an unknown category mean?** | It resolves to the seeded **`UNMAPPED`** row in `fin_revenue_category`, never to NULL and never to `OTHER_INCOME` — whose own V112 description forbids that use. `UNMAPPED` means "real revenue whose kind is not yet known"; it is deliberately visible and deliberately unattractive to report |
| **Are totals stored or calculated?** | **Calculated**, as `SUM` over the category rows for the period. ~12 rows per period per payment mode. No stored total, no `pct_of_total` (FIN-070A §5.6) |
| **How does aggregate uniqueness work?** | `uk_farp_grain (temple_id, source_system_id, period_type, period_key, category_id, payment_mode)` — every column `NOT NULL`, so no generated stand-in is needed and `ON DUPLICATE KEY UPDATE` is fully effective |
| **How does category-level filtering behave?** | A direct equality predicate on a key column, served by the leading index. No NULL-handling anywhere in the read path |
| **How is unmapped revenue handled?** | As its own row, with its own total, always included in the period total and never folded into any other category. A report may present it distinctly; it may not omit it, because omitting it understates revenue |

### 6.5 Consequences

Row count rises by at most the number of categories per period — ~3,700 period rows for the first
source, matching the data model's own ~3k projection. `FINANCE_DATA_MODEL.md` §7 must be amended
(§14). Reports that want a single headline number issue a `SUM`; no report is made harder.

### 6.6 What this decision does not solve

It does not decide whether a category may be *re-categorised* — a `fin_revenue_category` row
changing meaning, as opposed to a mapping rule pointing elsewhere. Nothing in the platform
supports that today, and D3 applies if it is ever attempted.

---

## 7. D8 — ADR-011 status

**Decision ID:** D8
**Question:** Should ADR-011 be Accepted, Rejected, Replaced, or kept Proposed?
**Recommendation:** **Accept, with three amendments** — and resolve the ADR governance question
separately, because it is not about ADR-011.

### 7.1 Evidence

`grep -H "^\*\*Status:\*\*" docs/finance/adr/*.md` returns **eleven ADRs, all `Proposed`**, all
dated 2026-09-16. ADR-001 carries "Deciders: Architecture review"; none records that a review
occurred.

So ADR-011's status is not a defect specific to ADR-011. Accepting one of eleven would produce a
record in which ten foundational decisions — including ADR-001, on which the entire
no-runtime-source-access posture rests — remain formally unadopted while a Stage-1 aggregation
choice is adopted. That reads as an accident, not a decision.

### 7.2 Why Accept rather than Reject, Replace or hold

ADR-011's *decision* — explicit aggregate tables rebuilt from facts, published only after
reconciliation passes — is sound, and it now has implementation evidence it lacked when written.
FIN-061 built `ReconciliationGate`, which makes the ADR's decisive claim concrete: publication is a
distinct, gateable step. Nothing found in this inspection contradicts the decision.

- **Reject** would require an alternative, and there is none: a query-time aggregate cannot be
  withheld, and MySQL 8.0 has no materialized views, so the ADR's option C is not available in this
  stack at all.
- **Replace** would discard a document whose reasoning is still correct in order to fix three
  paragraphs.
- **Keep Proposed pending a prerequisite** is defensible only if a prerequisite exists. None does:
  D1, D3 and D5 are now decided, and none of them changes whether aggregate tables are the right
  shape.

### 7.3 The required amendments — exact sections

| Section | Problem | Required change |
|---|---|---|
| **"Advantages of D"**, paragraph 1 | Leads with "Reads become small, indexed lookups regardless of underlying volume, so a state-wide query never scans fact tables" — a performance claim with **no benchmark anywhere in this project**, on a table that is empty in production | Demote to a *secondary, unmeasured* advantage and state plainly that no finance query has been benchmarked. Promote the publication-gating paragraph (currently third) to first, because it is the argument that actually decides |
| **"Consequences"**, bullet 3 | "Each aggregate row carries `computed_at`, `sync_batch_id` and `availability`" — wrong at aggregate grain. A period is fed by **many** batches; `ReconciliationGate.Decision.contributingBatchIds` returns a list precisely because one id cannot describe a period | Replace `sync_batch_id` with a contributing-batch reference plus the aggregation run id |
| **"Consequences"** (missing bullet) | Says prior figures stay visible on variance, but gives no mechanism, and nothing states what a blocked period looks like in the table | Add: a blocked period causes **nothing to be written**; the previously published row is left untouched, and the withheld outcome is recorded on the aggregation run. No status column, no candidate row |
| **Status line** | `Proposed` | `Accepted`, with the date and the decider — **an act for the architect, not for this task** |

### 7.4 What accepting ADR-011 commits the project to

- **Why aggregate tables are required:** because publication must be a distinct step that can lag
  the facts on purpose. A query-time total always reflects the newest facts, including facts the
  gate refuses.
- **Why aggregation and publication are separate:** aggregation is a pure function of facts and has
  no consequence; publication changes what readers see. Only the second is gated.
- **Why reconciliation gating decides:** it is the only property no alternative provides.
- **Why performance is not proven:** `fin_revenue_fact` is empty, no connector exists, no
  benchmark has been run, and the 121,242-row projection comes from source-database analysis rather
  than loaded data.
- **Alternatives rejected:** query-time `SUM` and database views (cannot withhold); materialized
  views (**MySQL 8.0 does not have them**); incremental delta updates (not replay-safe without an
  exactly-once ledger nothing provides).
- **What would reopen it:** a measured demonstration that gating can be achieved another way — for
  example a published-watermark column on the facts that a query-time aggregate could filter on.
  Performance evidence alone would **not** reopen it, in either direction.

### 7.5 Consequences

The four tracking documents and `FINANCE_DATA_MODEL.md` continue to cite ADR-011 as the basis for
Phase 7, now legitimately. The ADR governance question — who accepts these, and when — becomes an
open item (§21, D10) rather than being answered by precedent set on one file.

### 7.6 What this decision does not solve

It does not accept the other ten ADRs, and it does not establish who may. It also does not, by
itself, amend ADR-011 — **this task deliberately did not edit the ADR**, because changing an
architecture decision record's status is a governance act, and doing it unasked would be exactly
the silent architecture change the brief forbids.

---

## 8. Decision comparison tables

### 8.1 Summary

| ID | Question | Recommendation | Blocks FIN-070? |
|---|---|---|---|
| D1 | `source_system_id` in `uk_frf_grain`? | **ACCEPT** — standalone task, before the second source system | **No** |
| D3 | Orphan-fact restatement policy | **Option A** enforced; **Option C** approved as successor, unimplemented | **No** |
| D5 | `category_id` nullable "null = all"? | **NOT NULL, in the key; totals computed** | **No** |
| D8 | ADR-011 status | **Accept with three amendments** (architect's act) | **No** |

**No decision in this document blocks FIN-070.** That is the substantive outcome: FIN-070A listed
D1, D3 and D5 as blockers, and closer inspection shows none of them is.

### 8.2 Where this document departs from FIN-070A

| FIN-070A said | Evidence found | FIN-070B says |
|---|---|---|
| D1 must be done "before FIN-070 writes any aggregate" because the empty table makes it cheap | Widening a UNIQUE key cannot be violated by existing rows; `source_system_id` is already `NOT NULL` and populated | The DDL is safe at any time. The deadline is the **second source system**, and D1 does not block FIN-070 |
| A mapping correction would need re-extraction from the source | `uk_fsrm_row_type` exists so a remap re-runs idempotently over retained staging; `raw_json` is retained | A remap needs **no source access**. The missing piece is fact retirement, not extraction |
| The orphan problem is "likelihood high, the moment a rerun path exists" | Confirmed, and bounded further: batch-scoped retirement would still miss a grain restated by a later batch | Same conclusion, with the boundary of any future fix now stated |
| ADR-011 being `Proposed` is an anomaly to fix | **All eleven** finance ADRs are `Proposed` | Accept ADR-011 on its merits; raise ADR governance as its own open item |

---

## 9. Recommended target architecture

Unchanged from FIN-070A §11 except where D1/D3/D5 touch it.

- **`fin_agg_revenue_period`** — key `(temple_id, source_system_id, period_type, period_key,
  category_id, payment_mode)`, all `NOT NULL` (D5). `period_type ∈ {FINANCIAL_YEAR, MONTH}`.
- **`fin_agg_revenue_service`** — key `(temple_id, source_system_id, financial_year, service_id)`.
- **`fin_agg_run`** — append-only, one row per run, the only record of a withheld outcome.
- **Compute in memory → `requirePublishable` → write, or write nothing.** A blocked period leaves
  the previously published rows untouched. There is no candidate row and no status column.
- **Totals are `SUM` at read time.** Nothing stores a total or a percentage.
- **Facts are read-only to every aggregation path** (D3).

---

## 10. Impact on FIN-070

- Build against the **current** fact grain. D1 is welcome but not required first.
- `category_id NOT NULL` in the aggregate key; no total rows (D5).
- Call `requirePublishable(temple, source, financialYear)` immediately before the write, in the
  same transaction.
- `MONTH` rows inherit the parent financial year's verdict and record
  `reconciliation_inherited = 1`.
- **Add a guard**: refuse to aggregate a temple with more than one active source system, with a
  named error. This is the interim protection that D1 makes unnecessary once executed, and it costs
  one query. Remove it in the same commit as D1 if D1 lands first.
- No `@PreAuthorize` anywhere in FIN-070 — worker execution, no principal.

## 11. Impact on FIN-071

- Same key discipline, same gate call, same guard.
- `service_id NOT NULL` in the service table: facts with a NULL `service_id` are not services and
  belong only in the period table. Folding them into a "service = none" row would invite a reader
  to rank a hundi collection among sevas.
- `rate_card_amount` carried as context, never summed as revenue (V112's own warning).
- `pct_of_total` not stored (D5).

## 12. Impact on FIN-072

- FIN-072 rebuilds **aggregates from facts**. It must not re-run `MAP` or `LOAD`, and must not
  trigger extraction (D3).
- Affected scopes come from `findFinancialYearsBySyncBatchId`, plus a new
  distinct-months-per-batch query. Never inferred from the batch window (ADR-006).
- **FIN-072 is not a correction mechanism and must not be described as one** in its task
  description, its javadoc, or any API.
- An acceptance test asserts that no `fin_revenue_fact` row's `updated_at` changes across a rebuild.

---

## 13. Required migrations

**None is created by this task.** For the implementation tasks that follow:

| Task | Migration | Content |
|---|---|---|
| **FIN-052A** (D1) | `V118` | Drop and recreate `uk_frf_grain` with `source_system_id` as the second column. No data change, no backfill |
| **FIN-070** | `V119` | `fin_agg_revenue_period`, `fin_agg_run` |
| **FIN-071** | `V120` | `fin_agg_revenue_service` |

Numbers are indicative; `V117` is the last in the tree. Whichever task ships first takes `V118`.
Flyway is forward-only (ADR-002), and no foreign keys are added, consistent with FIN-D-003, V10,
V110 and V112.

---

## 14. Required schema changes

| Change | Decision | Status |
|---|---|---|
| `uk_frf_grain` gains `source_system_id` | D1 | **Approved**, own task, deadline = second source system |
| `fin_agg_revenue_period` with `category_id NOT NULL` in the key | D5 | Approved, FIN-070 |
| `fin_agg_run` | §9 | Approved, FIN-070 |
| `fin_agg_revenue_service` | D5, §11 | Approved, FIN-071 |
| `uk_ftc_temple_capability` gains `source_system_id` | D1 §4.4 | **Deferred** — needs the "two sources disagree" answer first (D9) |
| `uk_fsd_temple_service` gains a source scope | D1 §4.4 | **Deferred**, same deadline as D1 |
| Fact retirement / supersession | D3 Option C | **Not approved.** Prerequisites in §5.7 |

**Documentation that must change to match these decisions:**
`FINANCE_DATA_MODEL.md` §7 (nullable `category_id`, `pct_of_total`, `sync_batch_id` on an aggregate
row, `FY` vs `FINANCIAL_YEAR` spelling) and §8 (reconciliation columns and statuses that do not
exist in code); ADR-011 per §7.3. None was edited by this task.

---

## 15. Required audit changes

- **FIN-070/071 need no new audit framework.** `fin_agg_run` is the audit record: it is
  append-only, names the trigger, the gate verdict and the withheld reasons verbatim from
  `Decision.reasons`.
- **Do not route aggregation through `AuditService.logDataEvent`.** FIN-D-063 established that it
  is `@Async` + `REQUIRES_NEW` and catches its own exceptions, so it cannot roll a caller back. A
  run record that may silently not exist is not an audit record.
- **A future Option C restatement does need audit_data_event**, written in the caller's transaction
  on the FIN-D-063 pattern, because it is a human-initiated change to financial data. That is a
  prerequisite (§5.7), not part of Phase 7.

---

## 16. Required correction workflow

**There is none, and this document does not create one.**

Under Option A the workflow is: an administrator corrects the mapping through the Source Mapper
(shipped, FIN-054A/B), the screen states — as it already does — that the change applies to future
pipeline runs and does not correct published figures, and historical facts keep their original
classification.

The successor workflow (Option C) is sketched only to the extent needed to say what it requires:
an authorized, audited restatement action scoped to `(temple, source, business-date range)`, which
re-maps retained staging, loads, **retires the facts its earlier decision produced**, re-reconciles
and only then republishes. Five prerequisites in §5.7 do not exist. Until they do, describing this
as available would be a false claim.

---

## 17. Security implications

| Concern | Position |
|---|---|
| Worker services | **No `@PreAuthorize`.** FIN-070/071/072 run without an authenticated principal; adding one would refuse the pipeline |
| Read APIs | Authorization at the **service implementation**, the convention FIN-054A-BE follows. Temple set resolved server-side from the caller's claims, never taken from the client |
| District scope | `JurisdictionGuard.assertDistrictScope` for `DISTRICT_COLLECTOR` and `DC_STAFF` only. **`AUDITOR` is statewide and legitimately has a null `districtId`**, which that guard treats as a corrupted token — `MappingAdminServiceImpl` already handles this and FIN-070's readers must copy it rather than rediscover it |
| New roles or permissions | **None.** `CAN_READ_FINANCE_CONFIG` and `CAN_ACT_DC` already exist and already match the audience |
| Source credentials | Never reach aggregates. Nothing in this design reads `credential_ref`, `source_database_name` or any connection detail |
| Database-level isolation | **None exists**, and none is proposed. No row-level security, no constraint expresses jurisdiction. Stated so nobody treats the database as a backstop |
| Future restatement trigger | `CAN_ACT_DC`, append-only record of actor and reason. Not built |

---

## 18. Testing implications

Beyond FIN-070A §15:

| Decision | Test that must exist |
|---|---|
| **D1** (when executed) | Two source systems writing the same `(temple, date, service, category, mode, counter, operator)` produce **two** fact rows with distinct source ids and both sums intact. Today that test would fail, which is the point |
| **D1 interim guard** | A temple with two active source systems causes aggregation to refuse with a named error, not to produce a plausible wrong number |
| **D3** | A mapping-rule change followed by aggregation changes no aggregate; and no aggregation path alters any fact's `updated_at` |
| **D3 (documented defect)** | A test that pins the current double-count after a remap-and-reload, so that whoever implements Option C has a test that flips rather than a paragraph to rediscover |
| **D5** | Running aggregation three times produces exactly one row per `(temple, source, period_type, period_key, category, payment_mode)` — the duplicate-total failure §6.2 describes must be impossible |
| **D5** | `UNMAPPED` appears as its own aggregate row and is included in a computed total |
| **D8** | None — governance, not behaviour |

MySQL 8.0 Testcontainers for anything touching a unique key, an upsert or `SUM` semantics; H2 only
for pure computation. Finance suites must use the `FinanceApiTestBase` pattern (`ddl-auto=none`,
singleton container) to avoid the FIN-X-001 schema-validation failure and the stopped-container
failure both previously hit. **TiDB remains unverified and no test here changes that.**

---

## 19. Risks and limitations

1. **The orphan double-count is real, documented and unfixed** (D3). Option A prevents it by
   preventing re-load; the moment anyone builds a rerun, it returns.
2. **D1 not yet executed** means a second source system would silently destroy the first's revenue.
   Mitigated only by the interim aggregation guard (§10), which does not protect the **load** path
   — a second source would still overwrite facts before aggregation ever ran. This is the strongest
   argument for doing D1 sooner rather than at the deadline.
3. **`fin_temple_capability` and `fin_service_dim` remain single-source** (deferred).
4. **No connector exists**, so FIN-070 will be built and verified against synthetic facts only.
5. **No benchmark exists.** Every performance statement in this document, FIN-070A and ADR-011 is
   an estimate.
6. **TiDB is unverified** across the whole finance platform.
7. **Monthly figures inherit a yearly verdict** — one month's variance blocks eleven good months.
8. **Aggregates can drift from facts**; ADR-011's mitigation is a periodic full recompute, and no
   scheduler exists.
9. **The gate still has no caller** until FIN-070 ships.
10. **No source declares a transaction count**, so receipt-count reports will publish
    `NOT_AVAILABLE` from day one. Correct, and it will look like a bug in a demo.

---

## 20. Explicit non-goals

This document does not, and FIN-070/071/072 must not:

- implement aggregation, create a migration, or change production code;
- change the fact model, the staging model, or any existing constraint;
- claim source-side deletion is detected — it is not;
- claim historical restatement exists — it does not;
- treat an aggregate rebuild as a correction mechanism;
- introduce a rerun button, endpoint, trigger or scheduled job;
- add a publication override (FIN-D-060: none exists, and none can be authorized until Phase 8
  gives the pipeline an authenticated caller);
- weaken FIN-061: `PASSED` publishes, `NOT_AVAILABLE` publishes flagged, `FAILED` blocks,
  `PENDING` blocks;
- add `@PreAuthorize` to worker-only services;
- expose credentials or connection details;
- assume TiDB behaviour, or claim performance benefits without benchmarks.

---

## 21. Open decisions that remain

| ID | Question | Blocks |
|---|---|---|
| **D2** | Are `DAY` aggregates built? *(Recommendation stands: no — no catalogued report reads one)* | Nothing; reversible |
| **D4** | Is there a rerun trigger? *(Open since FIN-054A)* | Historical correction |
| **D6** | Is `pct_of_total` stored? *(Recommendation stands: no)* | Nothing |
| **D7** | Who triggers aggregation — orchestrator or separate worker? *(Recommendation stands: orchestrator)* | FIN-070 wiring |
| **D9** | **NEW.** When two sources for one temple disagree about a capability, what is the temple-level availability answer? | `uk_ftc_temple_capability` change; multi-source onboarding |
| **D10** | **NEW.** Who accepts finance ADRs, and when? All eleven are `Proposed` | The architecture record's legibility |
| **Q4** | Source access for the Kollur connector | FIN-043, and every real figure |
| **Q7** | Staging retention | Storage growth; also the assumption in §5.2 that staging is available for a remap |

D9 and D10 are new and were surfaced by this inspection.

---

## 22. Implementation prerequisites

**For FIN-070 — all satisfied:**

1. `ReconciliationGate` exists and is tested. ✅
2. `FinancialYear` provides year assignment and boundaries. ✅
3. `fin_revenue_fact` exists with reporting indexes. ✅
4. `fin_temple_capability` provides availability per temple. ✅ (single-source, adequate today)
5. D5 decided. ✅
6. D7 has a recommendation. ✅

**Strongly recommended before FIN-070, not blocking:** D1 (§4), because the interim guard protects
aggregation but not the load path (§19.2).

**For historical correction — none satisfied:** the five prerequisites in §5.7.

---

## 23. Proposed task sequencing

| Order | Task | Status | Note |
|---|---|---|---|
| 1 | **FIN-052A** — add `source_system_id` to `uk_frf_grain` | Proposed by D1 | Small, standalone, own commit |
| 2 | **FIN-070** — `fin_agg_revenue_period` + `fin_agg_run` | `NOT_STARTED` | First caller of `ReconciliationGate`; closes limitation 52 |
| 3 | **FIN-071** — `fin_agg_revenue_service` | `NOT_STARTED` | |
| 4 | **FIN-072** — affected-period rebuild | `NOT_STARTED` | Rebuild only. Not a correction |
| 5 | **Phase 8** — FIN-080…084 | `NOT_STARTED` | Where a gate verdict first reaches a human |
| — | ADR-011 amendment and acceptance | Architect | §7.3 |
| — | D9, D10 | Architect | §21 |
| — | Option C prerequisites | Unscheduled | §5.7 |

FIN-043 slots in whenever Q4 is answered. Nothing here depends on it, and everything here is
unverified against a real temple until it exists.

---

## 24. Final decision summary table

| ID | Decision | Outcome | Rationale in one line | Blocks FIN-070 |
|---|---|---|---|---|
| **D1** | `source_system_id` in the fact grain | **ACCEPTED — and executed by FIN-052A (V118)** | Widening a UNIQUE key is safe with or without data; what expires is recovery of already-overwritten facts | No |
| **D1a** | `uk_ftc_temple_capability` source scope | **DEFERRED** with conditions (D9) | Not a mechanical widening — it forces an unanswered design question about disagreeing sources | No |
| **D1b** | `uk_fsd_temple_service` source scope | **DEFERRED**, same deadline as D1 | Same assumption, newly found, lower stakes | No |
| **D3** | Orphan-fact restatement | **Option A ACCEPTED**; Option C approved as successor, unimplemented; **B and D REJECTED** | B needs fact-level record identity that FIN-D-040 removed; D is unenforceable and would strand `UNMAPPED` revenue forever | No |
| **D5** | `category_id` in the aggregate key | **ACCEPTED** — `NOT NULL`, in the key, totals computed | A nullable "null = all" row is not constrained by a UNIQUE key in MySQL or TiDB, so every run would insert another total | No |
| **D8** | ADR-011 status | **ACCEPT with three amendments** — the status change is the architect's act, not this task's | The decision is sound and now has FIN-061 behind it; the text overstates unmeasured performance and misdescribes one column | No |

**Nothing in this document blocks FIN-070.**

---

## Verification

- Production source code changed: **none**.
- Migrations created: **none**.
- Fact model changed: **no**.
- ADR-011 edited: **no** — §7.3 specifies the amendments; applying them is a governance act.
- FIN-070, FIN-071, FIN-072: **`NOT_STARTED`**.
- FIN-061 policy preserved verbatim: `PASSED` publish · `NOT_AVAILABLE` publish flagged ·
  `FAILED` block · `PENDING` block.
- No rerun trigger, endpoint or override designed, proposed or authorized.
- Source-side deletion: **not claimed to be supported**. Historical restatement: **not claimed to
  be implemented**.
- TiDB: **not assumed verified**. Performance: **no benefit claimed without a benchmark**.
- Every recommendation above cites the file and, where useful, the line it rests on (§2).

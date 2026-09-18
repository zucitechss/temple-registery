# FIN-070A — Financial Aggregation Architecture and Implementation Plan

**Task:** FIN-070A (analysis and design only)
**Status:** PLAN — nothing in FIN-070, FIN-071 or FIN-072 is implemented by this document
**HEAD at analysis:** `f984720f7df0ec15f571ec392ae9262f539d1b57` (`FIN-054B`)
**Date:** 2026-09-18
**Scope of change:** this file, plus a pointer line in `IMPLEMENTATION_TASKS.md`. No Java, SQL,
TypeScript or TSX file was modified. No migration was created.

---

## 1. Executive summary

There is **no aggregation code in this repository**. `grep -rn "fin_agg" backend/src/` returns
nothing: no table, no entity, no repository, no service, no test. Phase 7 is genuinely
`NOT_STARTED`, and the aggregate tables that `FINANCE_DATA_MODEL.md` §7 and
`FINANCE_REPORT_CATALOG.md` describe as the source of eight catalogued reports exist only as
prose. Everything below is therefore a greenfield design constrained by what the canonical layer
already is, not a refactoring of something half-built.

Five findings shape the design, and three of them are problems that must be decided before code
is written.

**First, the decisive argument for materialized aggregates is not performance.** ADR-011 lists
speed first, but `fin_revenue_fact` is empty in production, the projected first-source volume is
121,242 rows across eight financial years, and a temple-scoped indexed scan of that is not slow.
The argument that actually carries is that **a query-time aggregate cannot be gated**. A `SUM`
issued at request time always reflects the newest facts, including facts `ReconciliationGate`
says must not be published. Publication control requires a stored answer that can lag the facts
on purpose. That is the reason to build tables, and it should be stated as the reason, because a
team that believes the justification is performance will abandon the tables the first time
someone measures the query and finds it fast.

**Second, the gate's unit of decision is `(temple, source system, financial year)`, and the
aggregate grain must match it or the gate cannot be applied.** `ReconciliationGate.evaluate` takes
exactly those three arguments. An aggregate row that does not carry `source_system_id` cannot be
withheld for one source while another source's figures publish, and an aggregate row keyed at
month or day cannot be gated at all, because **no reconciliation result is ever written at month
or day granularity** — `RevenueReconciliationStage` writes only `FULL_HISTORY/ALL` and
`FINANCIAL_YEAR/yyyy-yy` rows. Monthly and daily aggregates are therefore governed by their
parent financial year's verdict, and there is no honest alternative today.

**Third, `source_system_id` is missing from the canonical fact grain, and this is now more urgent
than limitation 47 records.** `uk_frf_grain` covers seven columns and `source_system_id` is not
among them, so two sources reporting the same temple, date, category and payment mode overwrite
one another. Aggregating *by* source on top of facts that cannot distinguish sources would
produce per-source figures that look authoritative and are not. The fact table is empty outside
tests, which makes this the cheapest moment this defect will ever be fixable. Recommendation and
the alternative are in §6 and §18-D1.

**Fourth — and this is a new finding, not one the handoff records — re-extraction after a mapping
change leaves an orphan fact and double-counts the money.** `category_id` is part of the fact
grain. When an administrator maps `SEVA_CODE:430` from `UNMAPPED` to `SEVA` and the batch is
re-extracted, the load writes a *new* row at the `SEVA` grain and never touches the old row at
the `UNMAPPED` grain, because the upsert has no delete path and nothing else deletes facts. The
financial year's gross then contains the same money twice. `RevenueLoadStage`'s javadoc
acknowledges the mechanism for source-side deletions ("a later batch produces no fact for the
grain those records made, so the earlier fact survives untouched and overstates"); nobody has
written down that a mapping edit triggers the same mechanism from inside the platform, and
FIN-054B has just shipped the screen that invites the edit. Detail in §10.4.

**Fifth, aggregate rebuild is not a correction mechanism, and the phrase must not be used as
though it were.** Rebuilding aggregates re-reads facts. A mapping error lives *in* the facts.
Rebuilding therefore reproduces the wrong figure faithfully. The only route from a corrected
mapping to a corrected figure is re-extraction of the source batch, which is the re-run trigger
that FIN-054A deliberately left unbuilt (open decision D4) and that FIN-072 must not quietly
become.

The recommended shape is two aggregate tables as documented plus one run-record table, computed
in memory and **written only when the gate permits publication**, so that a blocked period leaves
the previous good figures in place untouched — which is what ADR-011 promises and what the
simplest possible implementation also happens to give.

---

## 2. Existing canonical fact model

### 2.1 What was read

| Artefact | Path |
|---|---|
| Fact entity | `entity/finance/FinRevenueFact.java` |
| Fact DDL | `db/migration/V112__finance_canonical_revenue.sql` |
| Fact repository | `repository/finance/FinRevenueFactRepository.java` |
| Load stage | `service/finance/pipeline/RevenueLoadStage.java` |
| Normalization | `service/finance/pipeline/RevenueNormalizationStage.java`, `RevenueNormalizer.java` |
| Mapping | `service/finance/pipeline/RevenueMappingStage.java`, `MappingRuleResolver.java` |
| Financial year | `service/finance/pipeline/FinancialYear.java` |
| Reconciliation | `service/finance/pipeline/RevenueReconciliationStage.java` |
| Gate | `service/finance/publication/ReconciliationGate.java` |
| Orchestrator | `service/finance/pipeline/FinancePipelineOrchestrator.java` |
| Batch | `entity/finance/FinSyncBatch.java`, `V110__finance_foundation.sql` |
| Capability | `entity/finance/FinTempleCapability.java`, `FinTempleCapabilityRepository.java` |
| Source system | `entity/finance/FinSourceSystem.java` |
| Jurisdiction | `security/JurisdictionGuard.java`, `security/RoleConstants.java` |
| ADRs | 001, 002, 003, 004, 006, 007, 008, 011 |

### 2.2 The columns, and what each one is worth to an aggregator

| Column | Type | Null | Meaning for aggregation |
|---|---|---|---|
| `temple_id` | BIGINT | NOT NULL | Isolation key. Every aggregate is temple-scoped first. |
| `source_system_id` | BIGINT | NOT NULL | Present on the row, **absent from the grain** (§6). |
| `sync_batch_id` | BIGINT | NOT NULL | The batch that **last wrote** the row, not every contributor. |
| `source_of_truth_version` | INT | NULL | ADR-008 declaration in force at load. |
| `source_record_ref` | VARCHAR(200) | NULL | Deliberately NULL for grouped facts (FIN-D-040). Not traceable per receipt. |
| `transaction_date` | DATE | NOT NULL | **Business** date (FIN-D-012). The only legitimate period axis. |
| `financial_year` | VARCHAR(10) | NOT NULL | Stored, canonical `yyyy-yy`, derived by `FinancialYear.of`. |
| `service_id` | BIGINT | NULL | NULL means *not a service*, never *unknown service*. |
| `category_id` | BIGINT | NOT NULL | Always known; unmappable values land in `UNMAPPED`. |
| `payment_mode` | VARCHAR(30) | NOT NULL | Includes `UNRECORDED`, which is not `CASH`. |
| `payment_mode_confidence` | VARCHAR(20) | NOT NULL | `RECORDED`/`INFERRED`. **Not in the grain** — see §5.3. |
| `counter_ref` | VARCHAR(50) | NULL | In the grain; not a reporting dimension. |
| `operator_ref` | VARCHAR(50) | NULL | In the grain; deliberately not a reporting dimension. |
| `transaction_count` | BIGINT | NULL | NULL makes a summed count a **floor**, not a total. |
| `gross_amount` | DECIMAL(18,2) | NULL | The measure every catalogued revenue report is built on. |
| `cancelled_count` | BIGINT | NULL | NULL = source records no cancellations at all. 0 = it does, none occurred. |
| `cancelled_amount` | DECIMAL(18,2) | NULL | Same distinction. |
| `quantity` | DECIMAL(18,3) | NULL | Items, where counting is meaningful. |
| `currency` | CHAR(3) | NOT NULL, `INR` | **Not in the grain** — see §5.4. |
| `net_amount` | DECIMAL(18,2) | GENERATED STORED | `gross - cancelled`; NULL propagates. **Never sum it** — §5.6. |
| `created_at` / `updated_at` | DATETIME(6) | NOT NULL | Load axis. Never a business date. |

### 2.3 Write path, verified

Exactly one writer: `RevenueLoadStage.write` → `FinRevenueFactRepository.upsert`, a native
`INSERT … ON DUPLICATE KEY UPDATE` that **assigns** every measure rather than accumulating.
There is no delete, anywhere: neither the repository nor the load stage issues one, and
`JpaRepository`'s inherited deletes have no caller in finance code. This makes a replay
idempotent and a restatement correct, and it is also the mechanism behind the orphan-fact defect
in §10.4 — the same property that makes replay safe makes a change of grain unsafe.

---

## 3. Existing financial grain

```sql
CONSTRAINT uk_frf_grain UNIQUE (
    temple_id, transaction_date, grain_service_key, category_id,
    payment_mode, grain_counter_key, grain_operator_key)
```

Seven columns, three of them generated stand-ins (`IFNULL(service_id,0)`,
`IFNULL(counter_ref,'~NONE~')`, `IFNULL(operator_ref,'~NONE~')`) because MySQL and TiDB treat
NULLs in a unique index as distinct and would otherwise accept the same fact twice (FIN-D-018).

Three columns that exist on the row are **not** in the grain, and each omission has a consequence
an aggregator must know about:

| Omitted from grain | Consequence |
|---|---|
| `source_system_id` | Two sources overwrite each other (limitation 47; §6). |
| `payment_mode_confidence` | An upsert can flip a row from `RECORDED` to `INFERRED` without changing identity. Confidence is a property of the last write, not of the fact. |
| `currency` | Two currencies for one grain collide. The platform is single-currency in practice; the schema does not enforce it. |

The grain is one row per temple per **business day** per service per category per payment mode
per counter per operator. It is not one row per receipt (ADR-003), which is why aggregation is a
roll-up of already-grouped rows and why `COUNT(*)` over facts is meaningless as a receipt count —
the repository javadoc on `sumTransactionCountForSourceAndFinancialYear` already says so.

---

## 4. Aggregation requirements

### 4.1 Derived from the report catalogue, not invented

`FINANCE_REPORT_CATALOG.md` names the consumers. Eight of the catalogued reports read
`fin_agg_revenue_period` or `fin_agg_revenue_service`; nothing else in the catalogue needs a
table that does not exist.

| Report | Needs | Grain implied |
|---|---|---|
| R1 Total money collected | FY gross for a temple | temple × FY |
| R2 Revenue trend | gross per FY | temple × FY |
| R3 Monthly revenue | gross per calendar month in a FY | temple × month |
| R4 Revenue by category | gross per category in a FY | temple × FY × category |
| R5 Revenue by seva | gross and count per service | temple × FY × service |
| R6 Receipt count | summed `transaction_count` | temple × FY |
| R9 High-value sevas | per-service revenue filtered by rate card | temple × FY × service |
| District rollup (`§4.3` of API contract) | `SUM` over the temples in scope, with coverage metadata | temple × FY, summed at query time |

Nothing in the catalogue requires a daily aggregate. `PeriodType.DAY` exists in the enum and
`FINANCE_DATA_MODEL.md` lists `DAY` as a period type, but no catalogued report reads one. Daily
rows would multiply the table by roughly 365× per temple-year for a consumer that does not exist.
**Recommendation: build `FINANCIAL_YEAR` and `MONTH` only** and leave `DAY` unbuilt until a report
asks for it. Drill-down to a specific day remains available against the facts, which is what
ADR-011 already says drill-down is for.

### 4.2 Non-functional requirements, in the order they bind

1. **Correctness before speed.** A wrong figure on a Deputy Commissioner's dashboard is worse
   than a slow one (ADR-011, FINANCE_RECONCILIATION §1).
2. **Publication gating.** An aggregate must not become visible while `ReconciliationGate`
   refuses the period.
3. **Idempotency.** Running aggregation twice over unchanged facts must produce byte-identical
   rows apart from `computed_at`.
4. **Availability propagation.** A period with no data must stay distinguishable from a period
   with zero revenue (ADR-007, ADR-011).
5. **Isolation.** No aggregate read ever crosses a temple boundary without the caller's
   jurisdiction being checked server-side.
6. **Traceability.** Every aggregate row must name the run, the gate verdict and the facts'
   contributing batches, or a figure cannot be defended six months later.

---

## 5. Supported dimensions

Only dimensions backed by an actual column are listed. Anything the schema cannot support is in
§21.

### 5.1 Safe grouping dimensions

| Dimension | Source field | Column | Null behaviour | Index today | Safe to group? |
|---|---|---|---|---|---|
| Temple | registry id | `temple_id` | NOT NULL | leading column of 4 indexes | **Yes** — mandatory first key |
| Source system | `fin_source_system.id` | `source_system_id` | NOT NULL | **none** | Yes as a *column*; unreliable as *attribution* until §6 is decided |
| Financial year | derived from date | `financial_year` | NOT NULL | `idx_frf_temple_fy` | **Yes** |
| Calendar month | derived from date | `transaction_date` | NOT NULL | `idx_frf_temple_date` | **Yes**, as `YEAR()`/`MONTH()` at compute time |
| Day | business date | `transaction_date` | NOT NULL | `idx_frf_temple_date` | Yes, but no consumer (§4.1) |
| Revenue category | mapping outcome | `category_id` | NOT NULL | `idx_frf_temple_cat_fy` | **Yes** — and `UNMAPPED` must stay its own bucket |
| Service | `fin_service_dim.id` | `service_id` | **NULL = not a service** | `idx_frf_temple_service` | Yes, if NULL is carried as a labelled bucket, never dropped |
| Payment mode | source or inferred | `payment_mode` | NOT NULL | none | Yes, with the `UNRECORDED` caveat below |

### 5.2 Payment mode, and the digital-share trap

`PaymentMode.UNRECORDED` means the source does not record how the money arrived. It is not
`CASH`, and the enum's javadoc says so explicitly. A "digital payment share" computed as
`digital / (digital + everything else)` silently treats `UNRECORDED` as non-digital and reports a
cash ratio the source never measured.

**Rule for FIN-070:** a payment-mode split may be stored, but a *ratio* may only be published
when `fin_temple_capability` declares `PAYMENT_MODE` as `AVAILABLE` for that temple. Otherwise the
ratio is `NULL` with the declared reason. The documented columns `digital_payment_count` and
`cash_inferred_count` in `FINANCE_DATA_MODEL.md` §7 are acceptable only with that guard; on their
own they invite exactly this error.

### 5.3 Payment-mode confidence is not a dimension

It is not part of `uk_frf_grain`, so an upsert can change it on an existing row without creating a
new one. Grouping by it would produce buckets whose membership changes under a restatement that
changed nothing financially. Carry it as a **flag on the aggregate row** — e.g.
`payment_mode_inferred_facts` counting contributing facts with `INFERRED` — not as a key.

### 5.4 Currency is an assertion, not a dimension

`currency` is `NOT NULL DEFAULT 'INR'` and absent from the grain. Every fact the platform can
currently produce is INR. **Rule:** the aggregator asserts a single currency across the facts it
is summing and refuses to write a row if it finds more than one, rather than grouping by currency
and pretending multi-currency is supported. A `SUM` across currencies is a nonsense number that
would look like a large rupee figure.

### 5.5 District is not on the fact, and should not be put there

There is no `district_id` on `fin_revenue_fact`. District is reached through the temple:
`JurisdictionGuard.assertDistrictScope` walks temple → hobli → taluk → district, with a
documented fallback to a flat `temple.districtId` scalar for auto-created temples whose hobli
association is missing.

**Recommendation: do not denormalize district into aggregate rows.** District membership is a
property of a temple that can change when boundaries are redrawn, and a denormalized copy would
need a backfill migration every time — silently restating historical financial reports as a side
effect of an administrative geography change. `TEMPLE_FINANCE_ONBOARDING.md` §219 already
describes the district figure as a `SUM` over the temples in scope, which is the right shape:
resolve the temple set from the jurisdiction at query time, then sum their aggregate rows.

### 5.6 Measures, and the rules each one carries

| Measure | Rule |
|---|---|
| `gross_amount` | `SUM`, skipping NULLs. Count the NULL-gross contributors and carry the count, or the total is a floor presented as a total. |
| `transaction_count` | `SUM` is a **floor** if any contributing fact has NULL. Carry `facts_with_unknown_count`; publish the count as `NOT_AVAILABLE` when it is non-zero, exactly as `RevenueReconciliationStage` already does (FIN-D-053). |
| `cancelled_count` / `cancelled_amount` | NULL = the source records no cancellations. Do not coerce to zero. |
| `net_amount` | **Never `SUM(net_amount)`.** The generated column is NULL wherever cancellations are unrecorded, so summing it silently omits those rows and reports a total far below gross. Compute `SUM(gross) - SUM(cancelled)` and store it **only** when `CANCELLATION` is `AVAILABLE` for that temple; otherwise store NULL. The existing javadoc on `sumGrossForFinancialYear` makes the same point about the same trap. |
| `quantity` | `SUM`, meaningful only for categories where counting items is meaningful. Not a headline metric. |
| `avg_transaction_amount` | Derived; store only where both numerator and denominator are complete, else NULL. |
| `pct_of_total` | Documented on `fin_agg_revenue_service`. **Recommend not storing it.** It is derivable at read time and goes stale the moment any sibling row changes, which creates a second figure that can disagree with the first. |

---

## 6. Proposed aggregation grain

### 6.1 The constraint that decides it

`ReconciliationGate.evaluate(long templeId, long sourceSystemId, String financialYear)`.

Whatever the aggregate grain is, **it must be reducible to that triple**, or the gate cannot be
applied to it. That rules out any grain that spans sources and any grain that spans financial
years.

### 6.2 Recommended grain — `fin_agg_revenue_period`

```
(temple_id, source_system_id, period_type, period_key, category_id, payment_mode)
```

with `period_type ∈ {FINANCIAL_YEAR, MONTH}` and `period_key` in the canonical spelling for each
(`2025-26`, `2025-04`).

Two decisions inside that key need justifying.

**`category_id` is part of the key, not a nullable "null = all" column.** The documented model in
`FINANCE_DATA_MODEL.md` §7 has `category_id` nullable with "null = all", which means the table
holds both detail rows and a total row whose value must equal their sum. Two representations of
one number in one table is a reconciliation problem the platform creates for itself; the first
time a rebuild writes detail rows and fails before the total, the table contradicts itself and
nothing detects it. Totals are `SUM` over detail rows at read time, which is cheap on a table
this small.

**`payment_mode` is part of the key.** R1–R4 do not need it, but the payment-mode split has to
live somewhere and a second table for one column is worse than one extra key column. It multiplies
the row count by at most seven.

Estimated size for the first source: 8 financial years × (1 + 12) period rows × ~12 categories ×
~3 realistic payment modes ≈ 3,700 rows. That matches the ~3k the data model projects.

### 6.3 Recommended grain — `fin_agg_revenue_service`

```
(temple_id, source_system_id, financial_year, service_id)
```

Financial year only, as documented. `category_id` is carried as an attribute, not a key, because
a service belongs to exactly one category by `fin_service_dim`, so adding it to the key would
allow a contradiction the dimension table already forbids.

### 6.4 The `source_system_id` problem, stated plainly

`uk_frf_grain` does not include `source_system_id`. Therefore:

- **Cross-source overwrite:** a second source reporting the same temple, date, service, category,
  payment mode, counter and operator **replaces** the first source's row. The money is not added;
  it is lost.
- **Incorrect grouping:** an aggregate grouped by `source_system_id` reads the surviving row's
  `source_system_id` column — whichever source wrote last — and attributes the whole grain to it.
- **Loss of traceability:** `sync_batch_id` likewise records only the last writer.
- **Unsafe unique constraints:** an aggregate unique key that includes `source_system_id` is
  *structurally* fine but *semantically* unfounded, because the facts underneath it cannot
  actually distinguish sources.
- **No duplicate conflict** arises at the aggregate level, which is precisely what makes this
  dangerous: the aggregate looks consistent and is wrong.

A second manifestation, not previously recorded: `fin_temple_capability` has
`uk_ftc_temple_capability UNIQUE (temple_id, capability)` even though `source_system_id` is
`NOT NULL` on the row, and `FinTempleCapabilityRepository.findByTempleIdAndCapabilityAndDeletedFalse`
returns `Optional`. The availability model is therefore **already single-source-per-temple by
constraint**, and a temple with two sources would break that lookup, not merely the facts. Any
"support two sources" decision has to cover both tables.

**Recommendation (requires sign-off — it amends ADR-003):** add `source_system_id` to
`uk_frf_grain` in a dedicated task before FIN-070 writes any aggregate, i.e.

```
uk_frf_grain (temple_id, source_system_id, transaction_date, grain_service_key,
              category_id, payment_mode, grain_counter_key, grain_operator_key)
```

The reasoning is cost, not elegance. `fin_revenue_fact` is empty in production; the change is a
drop-and-recreate of one unique index with no data to migrate and no backfill, and it will never
again be this cheap. Doing it later means either accepting that historical figures were silently
merged across sources or writing a de-merge that cannot be written, because the information was
destroyed at load.

The cost is that ADR-003's "a temple's figure for a day is one figure" stops being true at the
fact layer and becomes true only after summing across sources — which is arguably more honest,
since two systems genuinely did report separately.

**If that is refused**, the alternative is explicit and must be enforced, not assumed: the
aggregator refuses to run for any temple with more than one active source system, with a named
error, and onboarding refuses to register a second one. Silence is not an option — today a second
source would be accepted and would quietly delete the first source's revenue.

This is open decision **D1** (§18).

---

## 7. Query-time vs materialized aggregation

| | **A. Query-time `SUM` over facts** | **B. Database views** | **C. Materialized views** | **D. Aggregate tables (ADR-011)** | **E. Incremental in-place update** |
|---|---|---|---|---|---|
| **Financial correctness** | Always matches facts — including facts the gate blocks. Cannot withhold. | Same as A. | Refresh semantics decide; not controllable per period. | Correct **and** withholdable: the stored value is the last *published* answer. | Correct only if every delta is applied exactly once; a replayed batch double-counts. |
| **Performance** | Fine at 121k rows; unmeasured at 12M across 100 temples. District scope scans every temple's facts per request. | Identical to A. | Fast reads. | Fast, bounded, indexed. District scope is a `SUM` over ~3k rows per temple. | Fast. |
| **Idempotency** | Trivially idempotent (stateless). | Trivially. | Refresh-dependent. | Idempotent by construction if each run *replaces* a period rather than adding to it. | **Not** idempotent without a per-batch applied-ledger. |
| **Recovery** | Nothing to recover. | Nothing. | Full refresh. | Full rebuild from facts, always available. | Requires replaying deltas in order; a lost delta is undetectable. |
| **Concurrency** | Read-only; no conflict. | Same. | Refresh lock. | One writer per (temple, source, FY); conflicts resolved by a unique key + upsert. | Read-modify-write races lose updates without row locks. |
| **Historical correction** | Automatic — and therefore uncontrolled. | Same. | On refresh. | Explicit: a period is recomputed when something says to. | Needs a reversing delta, which nothing produces. |
| **Auditability** | None. No record that a figure was ever produced. | None. | Minimal. | `computed_at`, run id, gate verdict, contributing batches all storable. | Delta log only. |
| **MySQL 8.0** | Yes. | Yes. | **Not supported** — MySQL has no materialized views. | Yes. | Yes. |
| **TiDB** | Yes. | Yes. | Not dependable; see ADR-011's own assessment. | Yes — plain tables and `ON DUPLICATE KEY UPDATE`, the pattern FIN-056 already uses. | Yes. |

**Recommendation: D**, confirming ADR-011 — but on the gating argument, not the performance
argument. Option C is eliminated outright: MySQL 8.0 does not have materialized views, so it is
not a real option in this stack whatever TiDB does. Option E is rejected because it trades the one
property this platform cannot give up — replay safety — for a speed gain nobody has shown is
needed.

Honest caveat: **the performance case for D is currently unevidenced.** No production row exists,
and no measurement of a fact-table `SUM` has been taken at any scale. If someone later measures
query-time aggregation and finds it comfortably fast, that does not undermine D, because D was
chosen for gating. This should be said out loud in the implementation task so the tables are not
later deleted as premature optimisation.

---

## 8. ReconciliationGate integration

### 8.1 What the gate actually does, verified

- `evaluate(templeId, sourceSystemId, financialYear)` → `Decision(status, publishable, reasons, contributingBatchIds)`.
- `requirePublishable(...)` → same, or throws `PublicationBlockedException`.
- `@Transactional(readOnly = true, propagation = SUPPORTS)` — it takes no locks and writes nothing.
- The verdict is **derived every time**, never stored (FIN-D-057).
- `PENDING` when a contributing batch was never reconciled, or when no facts exist at all.
- `FAILED` when any batch-scoped or newest-per-question period-scoped result is `FAILED`.
- `NOT_AVAILABLE` (publishable, flagged) when nothing failed but something could not be checked.
- `PASSED` otherwise.

### 8.2 Do FIN-070/071/072 need to call it? Yes — at exactly one point

**FIN-070 and FIN-071 must call `requirePublishable` immediately before writing, inside the same
transaction as the write.** Not before computing, and not at read time.

- Not before computing: computation is free of consequence, and computing the blocked figure is
  how the run can *report* what it withheld.
- Not at read time: a read-time check would mean the table contains rows that must not be shown,
  and one caller forgetting the check publishes them. The gate's own javadoc makes this argument —
  "the aggregate writer that skips this does not compile differently, but the one that calls it
  and ignores the result cannot exist."

**The Phase 8 APIs must call `evaluate` as well**, but for a different purpose: to populate the
`reconciliation` field of the metric envelope (`PASSED · FAILED · NOT_AVAILABLE · PENDING`) that
`API_CONTRACT.md` §2 already specifies. That is reporting the verdict, not enforcing it.

### 8.3 The granularity mismatch, and the only honest resolution

`RevenueReconciliationStage` writes results at `FULL_HISTORY/ALL` and `FINANCIAL_YEAR/yyyy-yy`.
It never writes `MONTH` or `DAY`. `ReconciliationGate` correspondingly reads only those two
scopes.

Therefore **a monthly aggregate cannot be gated on its own evidence, because no such evidence
exists.** Three options were considered:

| Option | Assessment |
|---|---|
| Gate each month on its parent financial year's verdict | Conservative and truthful: a year whose total is disputed has no trustworthy months inside it. Coarse — one bad month blocks eleven good ones. |
| Publish months ungated | Rejected. It makes the gate trivially bypassable by asking a different endpoint. |
| Add monthly reconciliation | Out of scope here, and expensive: one extra `sourceTotals()` call per month per batch against a temple's production database (limitation 50 already flags the per-year cost as unmeasured). |

**Recommendation: months inherit the financial year's verdict**, and the aggregate row records
both the verdict and that it was inherited rather than measured, so a reader is never told a month
was verified when it was not. Monthly reconciliation is noted as future work, not promised.

---

## 9. Aggregation vs publication

These are three different operations and conflating any two of them is how a blocked figure
reaches a dashboard.

| Operation | Definition | Owner | Gate involvement |
|---|---|---|---|
| **Aggregation** | Deriving period totals from canonical facts. A pure function of the facts. Produces a candidate. | FIN-070 / FIN-071 compute step | None. Computing a blocked figure is harmless and useful. |
| **Publication** | Making a candidate the answer future readers get. The only step that changes what anyone sees. | FIN-070 / FIN-071 write step; FIN-072 for rebuilds | **`requirePublishable`, mandatory.** |
| **Reporting** | Selecting, scoping, authorizing and shaping published aggregates for a caller. | Phase 8 APIs (FIN-080…084) | `evaluate` for the envelope's `reconciliation` field; authorization is the API's own job. |

The distinction has a practical consequence: an aggregation run over a blocked period is **not a
failure**. It completes, writes nothing to the aggregate tables, and records in its run row that
the period was withheld and why, quoting `Decision.reasons`. An operator can then see that
FY2024-25 for temple 300001 is three days stale *because* a source-vs-central variance of
₹41 lakh is unresolved — which is information that today exists only inside the database
(limitation 49).

### 9.1 State transition model

```
                 facts loaded by a batch
                          |
                          v
                   [ NOT_AGGREGATED ]
                          |
          aggregation run computes candidate
                          |
                          v
                    [ CANDIDATE ]  (in memory only — never stored)
                          |
            ReconciliationGate.requirePublishable
                    /            \
        publishable               blocked (FAILED | PENDING)
              |                          |
              v                          v
      [ PUBLISHED ]                [ WITHHELD ]
   row written/replaced;      nothing written; previous
   availability + verdict     PUBLISHED row untouched and
   recorded on the row        still visible; run row records
              |                the verdict and reasons
              |                          |
              +----------- next run -----+
```

`WITHHELD` is a property of a **run**, not of an aggregate row. There is no `withheld` aggregate
row, because a row that exists but must not be read is a trap for the next developer. The
previously published row stays exactly as it was — which is ADR-011's "slightly old and correct
beats fresh and wrong", implemented by doing nothing rather than by a status column.

A `NOT_AVAILABLE` verdict publishes, with `reconciliation = NOT_AVAILABLE` stored on the row so
the API reports the flag rather than inferring it.

---

## 10. Correction and rerun strategy

### 10.1 The seven scenarios

| # | Event | What happens today | What must happen |
|---|---|---|---|
| 1 | A mapping rule is changed | Nothing. Facts, aggregates and dashboards are untouched (FIN-D-066, and the Source Mapper says so in every write response). | Unchanged. Future pipeline runs classify differently. |
| 2 | A new mapping covers a previously unmapped value | Nothing to existing facts. | Unchanged — plus the orphan warning in §10.4. |
| 3 | A failed reconciliation is corrected | A new batch re-reconciles; the gate's newest-per-question rule lets the later verdict clear the earlier one. | Aggregation must re-run for the affected `(temple, source, FY)` so the now-publishable period is actually published. Nothing does this today. |
| 4 | A source batch is reprocessed | Facts are upserted; measures are replaced, not accumulated. | Affected periods must be recomputed. This is FIN-072's core job. |
| 5 | A fact is corrected or replaced | Only via re-extraction. Nothing edits a fact directly, and nothing should. | Unchanged. |
| 6 | A period has already been aggregated | No aggregation exists. | Recompute and replace the published row, subject to the gate. |
| 7 | A published result needs correction | Impossible — nothing is published. | Correction is re-extraction → re-load → re-reconcile → re-aggregate. There is no "edit the aggregate" path and there must not be. |

### 10.2 Which periods are affected

`FinRevenueFactRepository.findFinancialYearsBySyncBatchId(syncBatchId)` already answers this and
is already used by reconciliation. A batch's change window is a *modification* window and says
nothing about which business periods it touched (ADR-006) — a single incremental batch can carry
corrections to three different years — so the affected set must be read from the facts the batch
wrote, never inferred from the window. FIN-072 should reuse this query rather than write a second
one.

Affected months follow from the affected facts' `transaction_date` values; a second query
returning distinct `yyyy-MM` per batch will be needed.

### 10.3 Options compared

| Option | Correctness | Cost | Recommendation |
|---|---|---|---|
| **Full rebuild from facts** | Highest — the aggregate is a pure function of the facts, so a full rebuild is definitionally correct. | Scans all facts for a temple. At 121k rows, seconds. At 12M across 100 temples, unmeasured. | **Use as the recovery and periodic-verification path**, exactly as ADR-011 says ("deterministic rebuild plus a periodic full recompute"). |
| **Affected-period rebuild** | Same correctness *per period*, because a period's aggregate depends only on that period's facts. | Proportional to the batch. | **Use as the normal path.** This is FIN-072 as already specified. |
| **Incremental delta** | Requires exactly-once delta application, which nothing provides. | Cheapest. | **Reject.** |
| **Versioned aggregates** | Enables "what did we publish on 3 March". | A second row set, a current-version pointer, and a new way to read the wrong version. | **Reject for now.** Nobody has asked for it (limitation 54 records the same gap for gate decisions and it has not bitten). Revisit if an auditor requires it. |
| **Explicit rerun command** | — | — | **Out of scope for FIN-070A by instruction**, and it is open decision D4 from FIN-054A, still unresolved. |
| **Period invalidation** | Marking a period stale without recomputing. | A second status system to keep in agreement with the gate. | **Reject.** The gate is already derived from evidence; a stale flag would be a stored opinion that can contradict it. |
| **Manual correction workflow** | — | — | **Reject.** An editable aggregate is an invented financial figure. |

### 10.4 New finding — re-extraction after a mapping change orphans a fact and double-counts

This is not in `HANDOFF.md` and it is reachable today.

`category_id` is part of `uk_frf_grain`. Suppose FY2025-26 contains a fact:

```
(temple 300001, 2025-06-14, service NULL, category UNMAPPED, CASH, …)  gross = 4,20,000
```

An administrator uses the Source Mapper — shipped in FIN-054B — to map `SEVA_CODE:430` to `SEVA`.
The batch is re-extracted. The load stage now produces:

```
(temple 300001, 2025-06-14, service NULL, category SEVA, CASH, …)      gross = 4,20,000
```

That is a **different grain**, so the upsert inserts a new row. The old `UNMAPPED` row is not
updated, not zeroed and not deleted — the upsert has no delete path and no finance code deletes
facts. `sumGrossForFinancialYear` now returns ₹8,40,000 for money that was ₹4,20,000.

The same mechanism fires for any re-extraction that changes a grain column: a service mapping
change, a payment-mode mapping change, a counter reference correction, or a corrected business
date. `RevenueLoadStage`'s javadoc describes the identical mechanism for *source-side* deletions
and states that detecting it "needs either a full reload of a date range or a reconciliation
against source totals (FIN-060)". Nobody has written down that an administrator's mapping edit
triggers it from inside the platform.

Consequences for this plan:

1. **Aggregation does not cause the defect and cannot detect it.** Summing facts faithfully
   reproduces the double count. An aggregate that disagrees with a source total would surface as a
   `SOURCE_VS_CENTRAL` failure — but only if a connector implements `sourceTotals()`, and none
   does (limitation 45/46). So today this would publish silently.
2. **FIN-072 must not be given a rerun trigger without first resolving this.** A "rebuild after
   remapping" button built on the current load path would double revenue on its first use.
3. **The likely fix is a bounded delete-then-load for a re-extracted business-date range**, which
   is a restatement policy this platform does not have and which is explicitly noted as missing in
   limitation 45. It belongs in its own task.

Recorded as open decision **D3** and risk **R2**.

---

## 11. Database design

No migration is created by this task. The following is the recommended shape for FIN-070's
migration (`V118`, next free number — `V117` is the mapping-rule version column).

### 11.1 `fin_agg_revenue_period`

| Column | Type | Null | Purpose |
|---|---|---|---|
| `id` | BIGINT AI | NOT NULL | PK |
| `temple_id` | BIGINT | NOT NULL | Isolation key |
| `source_system_id` | BIGINT | NOT NULL | Gate scope; see D1 |
| `period_type` | VARCHAR(20) | NOT NULL | `FINANCIAL_YEAR` \| `MONTH` — the existing `PeriodType` enum spelling |
| `period_key` | VARCHAR(20) | NOT NULL | `2025-26` \| `2025-04` |
| `period_start` / `period_end` | DATE | NOT NULL | Inclusive bounds, from `FinancialYear.startOf/endOf` for FY rows |
| `financial_year` | VARCHAR(10) | NOT NULL | The parent FY even for `MONTH` rows — this is the gate key |
| `category_id` | BIGINT | NOT NULL | Detail grain; no "null = all" row (§6.2) |
| `payment_mode` | VARCHAR(30) | NOT NULL | Includes `UNRECORDED` |
| `transaction_count` | BIGINT | NULL | NULL when any contributing fact's count is unknown |
| `facts_with_unknown_count` | INT | NOT NULL | Why the above is NULL, and by how much it is a floor |
| `gross_amount` | DECIMAL(20,2) | NULL | |
| `cancelled_count` / `cancelled_amount` | BIGINT / DECIMAL(20,2) | NULL | NULL = source records no cancellations |
| `net_amount` | DECIMAL(20,2) | NULL | Stored, **not generated**: computed as `SUM(gross) - SUM(cancelled)` only when `CANCELLATION` is `AVAILABLE` (§5.6) |
| `quantity` | DECIMAL(20,3) | NULL | |
| `currency` | CHAR(3) | NOT NULL | Asserted single-valued at compute time |
| `payment_mode_inferred_facts` | INT | NOT NULL | §5.3 |
| `availability` | VARCHAR(30) | NOT NULL | From `fin_temple_capability` — ADR-011's requirement that the API never has to infer it |
| `reconciliation_status` | VARCHAR(20) | NOT NULL | The gate verdict at publication: `PASSED` \| `NOT_AVAILABLE` |
| `reconciliation_inherited` | TINYINT(1) | NOT NULL | 1 on `MONTH` rows — the verdict is the parent FY's, not this month's (§8.3) |
| `contributing_batch_ids` | VARCHAR(500) | NULL | Traceability; the gate already returns them |
| `agg_run_id` | BIGINT | NOT NULL | Which run published this |
| `computed_at` | DATETIME(6) | NOT NULL | |
| `created_at` / `updated_at` | DATETIME(6) | NOT NULL | Load axis |

```sql
CONSTRAINT uk_farp_grain UNIQUE (
    temple_id, source_system_id, period_type, period_key, category_id, payment_mode)
```

Indexes: `(temple_id, financial_year, period_type)`, `(temple_id, period_type, period_key)`,
`(agg_run_id)`. No foreign keys, consistent with FIN-D-003, V10, V110 and V112.

Deliberately **not** a generated `net_amount`: unlike the fact table, net here must be NULL for a
reason the database cannot see (the temple's declared cancellation capability), so the computation
belongs in the aggregator with the capability lookup beside it.

### 11.2 `fin_agg_revenue_service`

Key `(temple_id, source_system_id, financial_year, service_id)`, with `category_id`,
`booking_count`, `gross_amount`, `cancelled_amount`, `net_amount`, `avg_transaction_amount`,
`rate_card_amount`, `availability`, `reconciliation_status`, `agg_run_id`, `computed_at`.

`pct_of_total` is **not** stored (§5.6). `rate_card_amount` is copied from `fin_service_dim` as
context and must never be summed as revenue — V112's own comment makes that point about the
dimension column.

`service_id` is `NOT NULL` here: facts with a NULL `service_id` are not services and belong in
`fin_agg_revenue_period` only. Excluding them is correct; silently folding them into a
"service = none" row in a *service* report would invite a reader to treat hundi collections as a
seva, which is the specific error V112's category vocabulary exists to prevent.

### 11.3 `fin_agg_run`

One row per aggregation run, and the only place a withheld outcome is recorded.

`id`, `temple_id`, `source_system_id`, `financial_year`, `trigger` (`BATCH` \| `MANUAL` \|
`FULL_REBUILD`), `sync_batch_id` (nullable — a full rebuild has none), `outcome`
(`PUBLISHED` \| `WITHHELD` \| `FAILED`), `reconciliation_status`, `blocked_reasons` TEXT,
`period_rows_written`, `service_rows_written`, `started_at`, `finished_at`, `created_at`.

Unique on nothing: runs are append-only history, which is the same choice FIN-060 made for
`fin_reconciliation_result` and for the same reason.

### 11.4 Why three tables and not fewer

`fin_agg_revenue_period` and `fin_agg_revenue_service` have genuinely different grains and
different measures; merging them would need a nullable `service_id` in the key and reintroduce the
"null = all" ambiguity §6.2 rejects. `fin_agg_run` is the only place a *withheld* run leaves a
trace, and without it limitation 49 — "a disagreement is invisible outside the database" — simply
moves up a layer.

### 11.5 MySQL 8.0 and TiDB compatibility

Everything here is plain tables, `VARCHAR`/`DECIMAL`/`DATETIME(6)`, a unique key and
`INSERT … ON DUPLICATE KEY UPDATE` — the exact pattern `FinRevenueFactRepository.upsert` already
uses and that FIN-056 tested on MySQL 8.0. No generated columns are required in the aggregate
tables, no window functions, no CTEs in the write path, no foreign keys. **TiDB remains untested
for the whole finance platform** (a standing limitation), and this design does not add a new
dependency that would make it worse.

---

## 12. State transition model

See §9.1 for the diagram. Stated as rules:

1. A period is `NOT_AGGREGATED` until a run computes it. Absence of an aggregate row is not zero
   revenue and must not be rendered as such.
2. A run computes a candidate **in memory**. Candidates are never stored.
3. `requirePublishable(temple, source, financialYear)` decides. `MONTH` rows inherit their parent
   FY's decision.
4. `PASSED` / `NOT_AVAILABLE` → upsert the rows, stamp the verdict and `agg_run_id`, record
   `outcome = PUBLISHED`.
5. `FAILED` / `PENDING` → write **nothing** to the aggregate tables; record `outcome = WITHHELD`
   with `Decision.reasons` verbatim. The previously published rows remain, unmodified, and remain
   the answer readers get.
6. A published row is only ever replaced by a later published row for the same key. Nothing
   deletes an aggregate row except a full rebuild that replaces the whole scope in one
   transaction.
7. The reporting layer reads published rows and reports `reconciliation` from a live `evaluate`
   call, so a period that has *become* blocked since publication is shown as stale rather than
   silently presented as current. This is what makes the derived-verdict design (FIN-D-057) pay
   off: there is no stored verdict to go out of date.

---

## 13. Security and isolation

### 13.1 Existing controls that must be reused, not replaced

| Control | Where it lives |
|---|---|
| Role expressions | `security/RoleConstants.java` — `CAN_READ_FINANCE_CONFIG`, `CAN_ACT_DC`, `CAN_READ_ALL`, `IS_DC_ROLE` |
| Authorization enforcement | `@PreAuthorize` on **service implementations**, the convention FIN-054A-BE followed |
| District scoping | `JurisdictionGuard.assertDistrictScope(temple, claims)` / `enforceDistrictId` |
| Claims | `ScopeHelper.Claims` |

**No new permission is proposed.** The read roles for finance configuration already exist and the
report catalogue's audience (Deputy Commissioner, DC staff, auditor, super admin) matches
`CAN_READ_FINANCE_CONFIG` exactly. If the Phase 8 APIs need a temple-facing read for
`TEMPLE_AUTHORITY`, that is a Phase 8 decision with its own documented requirement, not something
FIN-070 should pre-empt.

### 13.2 Where each control belongs

| Concern | Enforced at | Why not elsewhere |
|---|---|---|
| Which temples a caller may read | **Service layer**, by resolving the caller's district to a temple id set server-side | A controller-only check is bypassed by any other caller of the service; a query-only check cannot produce a good error |
| Cross-temple grouping | **Service layer** — the temple id set is an input to the query, never a client-supplied list taken on trust | `API_CONTRACT.md` §4.3 takes `districtId`; that must be passed through `enforceDistrictId`, which makes the JWT claim win for DC roles |
| Temple-level access | Service layer, same mechanism | |
| Auditor access | Statewide and legitimately has a null `districtId`. `MappingAdminServiceImpl` already handles this by **not** calling `assertDistrictScope` for `AUDITOR`, because that guard treats a null district as a corrupted token. FIN-070's readers must copy that behaviour, not rediscover it. | |
| Super admin | No district restriction | |
| Cross-source-system data | Service layer: a caller authorized for a temple is authorized for all of that temple's sources. Source systems are an internal concern, not a permission boundary. | |
| Aggregate storage | **Nothing** — there is no row-level security in MySQL/TiDB here, and no database constraint can express jurisdiction | Stating this plainly prevents someone assuming the database is a backstop |
| Cached aggregates | Not applicable. ADR-011 forbids a cache on top of aggregates, because it would delay a reconciliation-triggered correction for no gain. | |

### 13.3 Aggregation is not a user-facing operation

FIN-070/071 run from the pipeline, in the sync worker's runtime, with no authenticated principal.
They must therefore **not** carry `@PreAuthorize` — an unauthenticated internal caller would be
refused. The authorization boundary is the Phase 8 read API. This mirrors how every pipeline stage
already works and is worth stating because "add `@PreAuthorize` everywhere" is the wrong reflex
here.

Worker beans remain explicit `@Bean` registrations, never `@Component` + `@Profile` (FIN-D-008).

---

## 14. API implications

Nothing in this task creates an endpoint. For Phase 8:

1. `fin_agg_revenue_period` and `fin_agg_revenue_service` carry `availability` and
   `reconciliation_status`, so `API_CONTRACT.md` §2's metric envelope can be populated without
   inference — which is what ADR-011 asked for.
2. `PENDING` reaches the envelope only from a live `evaluate` call, because it is never stored
   anywhere (`ReconciliationStatus.PENDING` javadoc says so explicitly).
3. A period with **no aggregate row** must be reported as `availability = NOT_AVAILABLE` with a
   reason, never as `value: 0`.
4. District rollups sum aggregate rows for the temples in scope and must carry the
   `coverage` block (`templesInScope` / `templesReporting` / `templesNotAvailable`) that
   `API_CONTRACT.md` §4.3 already specifies. A district total that silently omits non-reporting
   temples is a wrong number presented as a right one.
5. `reconciliation_inherited` should surface in the reconciliation endpoint (FIN-083), so a reader
   of a monthly figure is told the verdict came from the year.
6. **No aggregation-trigger endpoint** is proposed. That is the rerun question (D4), still open.

---

## 15. Testing strategy

All behavioural, all against real behaviour. MySQL 8.0 Testcontainers for anything touching the
upsert, the unique key or `SUM` semantics; H2 is acceptable only for pure computation that never
reaches those. Note the standing constraint: finance suites must extend a base with
`ddl-auto=none` (the `FinanceApiTestBase` pattern) to avoid the FIN-X-001 schema-validation
failure, and a singleton container must be used so a second test class does not run against a
stopped one.

### 15.1 Financial correctness

| Test | Asserts |
|---|---|
| Sum equals aggregate | `SUM(gross)` over facts for a (temple, source, FY) equals the aggregate row's total, to the paisa |
| No duplicate counting | Facts at three grains that differ only by payment mode produce three rows, not one triple-counted row |
| Financial-year assignment | A fact dated 31 March lands in the prior FY and 1 April in the next; boundary tested in both directions |
| Month boundaries | 30 April and 1 May land in different month rows; February in a leap year is complete |
| Month-to-year consistency | Twelve month rows for a FY sum to the FY row, per category and payment mode |
| Null measures | A fact with NULL `gross` does not zero the aggregate; a fact with NULL `transaction_count` sets `transaction_count` NULL and `facts_with_unknown_count` ≥ 1 |
| Net amount | With `CANCELLATION` `NOT_AVAILABLE`, `net_amount` is NULL, **not** `gross`; with it `AVAILABLE`, net is `SUM(gross) - SUM(cancelled)` |
| Never `SUM(net_amount)` | A fixture where one fact has NULL `cancelled_amount` proves the aggregate does not silently drop it |
| Decimal precision | Amounts at `DECIMAL(18,2)` summed across 10,000 facts stay exact; no float appears anywhere in the path |
| Currency | Two currencies in one scope cause a refusal with a named error, not a summed nonsense figure |
| `UNMAPPED` is visible | An `UNMAPPED` fact produces its own aggregate row and is never folded into `OTHER_INCOME` |
| Service NULL excluded from service table | A hundi fact (service NULL) appears in the period table and not in the service table |

### 15.2 Reconciliation

| Test | Asserts |
|---|---|
| `PASSED` publishes | Rows written, `reconciliation_status = PASSED`, run `PUBLISHED` |
| `NOT_AVAILABLE` publishes flagged | Rows written with `NOT_AVAILABLE` recorded |
| `FAILED` blocks | **No aggregate row written**, previous row byte-identical afterwards, run `WITHHELD` with the gate's reasons |
| `PENDING` blocks | A batch that loaded facts and never reconciled blocks the whole FY |
| One bad contributing batch blocks the period | Two batches feed FY2025-26; one has a batch-scoped `FAILED`; the period is blocked despite the other's clean result |
| Month inherits | A blocked FY blocks all twelve months; `reconciliation_inherited = 1` on published months |
| Gate is actually called | A mutation that removes the `requirePublishable` call must fail a test (FIN-D-027: the mutation counts only against a report the run itself produced) |

### 15.3 Idempotency

| Test | Asserts |
|---|---|
| Repeat run | Two runs over unchanged facts produce identical rows apart from `computed_at` and `agg_run_id` |
| Replay does not accumulate | Running three times does not triple a total — the specific failure a `gross = gross + VALUES(gross)` upsert would produce |
| Rebuild equals incremental | Full rebuild of a temple equals the result of the per-batch runs that built it |
| Concurrency | Two runs for the same (temple, source, FY) started together leave one consistent row set, not duplicates; the unique key is what enforces it |
| Partial failure | A run that throws after writing some rows leaves the scope recoverable — either all-or-nothing per period transaction, or a run row that says what was incomplete |

### 15.4 Isolation

| Test | Asserts |
|---|---|
| Cross-temple | An aggregate query for temple A never returns temple B's rows even when both share a category and a period |
| District scope | A DC for district 29 cannot read a temple in district 20; `JurisdictionGuard` is what refuses |
| Auditor | An `AUDITOR` with a null `districtId` reads statewide and is **not** refused by the district guard |
| Unauthorized role | `VIEWER` / `TEMPLE_AUTHORITY` get the documented refusal, not an empty list that reads as "no revenue" |

### 15.5 Corrections

| Test | Asserts |
|---|---|
| Mapping change does not move a figure | Change a rule, re-run aggregation without re-extraction; every aggregate is unchanged — the promise FIN-054B makes on screen |
| Facts unchanged | No aggregation path writes, updates or deletes a `fin_revenue_fact` row. Assert by row-version/`updated_at` comparison across a run |
| Affected-period scoping | A batch touching FY2023-24 and FY2025-26 recomputes exactly those two and leaves FY2024-25's `computed_at` untouched |
| Orphan detection (§10.4) | A regression test that re-extracts after a category remap and asserts the **current, defective** double-count, documenting it, so that whoever fixes it has a test that flips |

### 15.6 Database-specific

MySQL 8.0 for: the aggregate upsert, unique-key collision behaviour, `DECIMAL` summation,
`SUM` NULL semantics, generated-column interaction on the fact side. H2 for: `FinancialYear`
boundary logic and pure candidate computation from in-memory facts.

---

## 16. Performance considerations

| Aspect | Assessment |
|---|---|
| Aggregate table size | ~3,700 period rows + ~1,000 service rows for the first source. ~470k rows total for all finance tables at one temple; ~47M across 100 temples per the data model's projection. |
| Read cost | Temple-scoped index lookups over thousands of rows. District rollup is a `SUM` over the aggregate rows of the temples in scope. |
| Write cost | One `GROUP BY` scan of a temple-and-FY slice of facts per run, plus an upsert per aggregate row. `idx_frf_temple_fy` and `idx_frf_temple_cat_fy` serve it. |
| **Missing index** | There is **no index with `source_system_id` as a leading or secondary column** on `fin_revenue_fact`. Grouping by source uses `idx_frf_temple_fy` and filters. With one source per temple this is free. It becomes a real cost only under D1's multi-source world, and the index should ship with that change, not before. |
| Measurement status | **Nothing has been measured.** `fin_revenue_fact` is empty in production, no connector exists, and the 121k-row projection is derived from source-database analysis rather than from loaded data. Every performance statement in this section, in ADR-011 and in §7 is an estimate. |

---

## 17. Risks and limitations

**R1 — The `source_system_id` fact-grain gap (limitation 47).** Two sources overwrite each other,
and `fin_temple_capability`'s unique key has the same single-source assumption. Aggregating by
source on top of it produces confident, wrong attribution. Decision D1. *Likelihood today: nil —
no temple has two sources. Impact when it happens: silent revenue loss.*

**R2 — Orphan facts after a mapping-driven re-extraction (§10.4, new).** Double-counts revenue.
Reachable now that FIN-054B has shipped the editing screen, and invisible without a working
`sourceTotals()`. Decision D3. *Likelihood: high, the moment a rerun path exists. Impact: a
materially overstated headline figure.*

**R3 — Monthly figures are gated on evidence about the year, not the month (§8.3).** One month's
variance blocks eleven good months, and a published month carries an inherited verdict. Mitigated
by recording `reconciliation_inherited`.

**R4 — Aggregates can drift from facts.** ADR-011's own mitigation is a periodic full recompute,
which needs a scheduler that does not exist. Until it does, drift is detectable only by running a
rebuild by hand.

**R5 — The gate still has no caller (limitation 52) and this task does not add one.** FIN-070 is
what closes it. Until FIN-070 ships, a `RECONCILE_FAILED` batch blocks nothing in practice.

**R6 — No source has declared `REVENUE_TRANSACTION_COUNT` (limitation 46).** Every fact's
`transaction_count` will be NULL, so R6 (receipt count) publishes `NOT_AVAILABLE` from day one.
That is correct behaviour and will look like a bug to whoever demos it. Say so in advance.

**R7 — TiDB is untested** for the entire finance platform. This design adds no new risk but
inherits the existing one.

**R8 — Performance is entirely unevidenced** (§16). If the tables are later judged premature, the
gating argument (§7) is the one that must be answered, not the speed argument.

**R9 — No connector exists (FIN-043, blocked on Q4).** `fin_revenue_fact` is empty outside tests,
so FIN-070 will be built and tested against synthetic facts only. Everything here is verifiable by
test and unverified against a real temple.

---

## 18. Open decisions

> **D1, D3, D5 and D8 are resolved in
> [FIN-070B_AGGREGATION_DECISIONS.md](FIN-070B_AGGREGATION_DECISIONS.md), which supersedes this
> section for those four.** Two of them changed on closer inspection: D1's deadline is the second
> source system rather than FIN-070 (widening a UNIQUE key is safe with or without data), and a
> mapping correction turns out to need no source access, because `uk_fsrm_row_type` already makes
> a remap idempotent over retained staging. **None of the four blocks FIN-070.** D2, D4, D6 and D7
> below remain open as written.

**D1 — Must `uk_frf_grain` include `source_system_id` before FIN-070?**
*Recommendation:* **Yes**, as a separate task before FIN-070 writes anything. The table is empty,
which makes it a drop-and-recreate of one index with no backfill. It amends ADR-003 and needs the
architect's sign-off. *If refused:* the aggregator must refuse any temple with more than one
active source system, with a named error, and onboarding must refuse to register a second — and
`fin_temple_capability`'s unique key must be covered by the same decision.

**D2 — Are `DAY` aggregates built now?**
*Recommendation:* **No.** No catalogued report reads one; drill-down goes to facts (ADR-011).
Reversible at any time.

**D3 — What happens to the orphan fact left by a grain-changing re-extraction (§10.4)?**
*Recommendation:* a bounded delete-then-load restatement for a re-extracted business-date range,
as its own task, **before** any rerun trigger exists. Not FIN-070's to solve, but FIN-070 must not
be taken as evidence that the problem is handled.

**D4 — Is there a rerun trigger?** Unresolved since FIN-054A. Explicitly out of scope here.

**D5 — Is `category_id` a key column, or nullable with "null = all" as documented?**
*Recommendation:* **key column** (§6.2). This contradicts `FINANCE_DATA_MODEL.md` §7, which needs
updating if accepted.

**D6 — Is `pct_of_total` stored?** *Recommendation:* **no** (§5.6). Also contradicts the data
model doc.

**D7 — Who triggers aggregation?** The orchestrator after a successful load and reconcile is the
obvious answer, but it makes every sync do more work and couples two concerns. The alternative is
a separate worker reading batches that have reconciled. *Recommendation:* orchestrator for now —
one caller, easy to reason about — with the run table making a separate trigger easy to add later.

**D8 — Does ADR-011 get promoted from Proposed to Accepted?** It has been the basis of the plan
since Stage 1 and is still marked **Proposed**. FIN-070 shipping without that being resolved
leaves the architecture record ambiguous.

---

## 19. Phased implementation plan

**Phase 0 — Prerequisite (only if D1 is accepted).** Migration adding `source_system_id` to
`uk_frf_grain` plus a supporting index; update `FinRevenueFact`'s javadoc, which currently
documents a seven-column grain; extend the existing grain tests. Its own task, its own commit.

**FIN-070 — `fin_agg_revenue_period`.**
Migration `V118`; entity + repository with an upsert mirroring `FinRevenueFactRepository.upsert`;
`RevenuePeriodAggregator` computing FY and MONTH candidates from facts, resolving availability from
`fin_temple_capability`, calling `requirePublishable` immediately before writing; `fin_agg_run`
recording every outcome. Tests: §15.1, §15.2, §15.3.
*Does not:* touch facts, add an endpoint, add a scheduler.

**FIN-071 — `fin_agg_revenue_service`.**
Same migration or a follow-on; service-grain aggregator reusing FIN-070's gate call and run
record. Tests: service-grain correctness, NULL-service exclusion, `rate_card_amount` carried and
never summed.

**FIN-072 — Affected-period rebuild.**
Reuse `findFinancialYearsBySyncBatchId`; add the distinct-months-per-batch query; rebuild exactly
the affected `(temple, source, period)` scopes; wire into the orchestrator after reconciliation
per D7. Tests: §15.3, §15.5.
*Does not:* trigger re-extraction, correct historical facts, or offer an override.

**Then Phase 8** (FIN-080…084), which is where the gate verdict finally reaches a human.

Each phase keeps the four tracking documents current, and nothing is marked COMPLETE that is not
implemented and verified.

---

## 20. Acceptance criteria

FIN-070 is complete when all of the following are demonstrated by a test that actually ran:

1. For a temple with facts in a financial year and a `PASSED` verdict, `SUM(gross_amount)` over
   the aggregate rows equals `SUM(gross_amount)` over the facts, exactly.
2. Twelve `MONTH` rows sum to their `FINANCIAL_YEAR` row, per category and payment mode.
3. A `FAILED` or `PENDING` verdict writes **zero** aggregate rows and leaves any previously
   published row bit-for-bit unchanged, with the reasons recorded in `fin_agg_run`.
4. A `NOT_AVAILABLE` verdict publishes with `reconciliation_status = NOT_AVAILABLE` stored.
5. Running aggregation three times over unchanged facts produces identical rows apart from
   `computed_at` and `agg_run_id`.
6. A fact with NULL `gross_amount` or NULL `transaction_count` never becomes a zero, and
   `facts_with_unknown_count` explains the gap.
7. `net_amount` is NULL wherever the temple's `CANCELLATION` capability is not `AVAILABLE`.
8. `UNMAPPED` revenue appears as its own aggregate row.
9. No aggregation code path writes, updates or deletes a `fin_revenue_fact` row — asserted, not
   assumed.
10. A mapping-rule change followed by aggregation (without re-extraction) changes no aggregate.
11. Removing the `requirePublishable` call fails at least one test.
12. `IMPLEMENTATION_STATUS.md`, `IMPLEMENTATION_TASKS.md`, `IMPLEMENTATION_DECISIONS.md` and
    `HANDOFF.md` record what was built, what was verified and what was not.

---

## 21. Unsupported assumptions

Listed so nobody inherits them as facts.

1. **Performance figures are estimates.** No finance query has been benchmarked at any scale.
   Volume projections come from source-database analysis, not from loaded rows.
2. **TiDB behaviour is assumed, not tested**, for every finance table including the ones proposed
   here.
3. **Multi-source temples are assumed not to exist.** True today; the schema does not enforce it,
   and two places (the fact grain and the capability unique key) would break quietly.
4. **`sourceTotals()` is assumed to arrive eventually.** Until it does, every `SOURCE_VS_CENTRAL`
   check is `NOT_AVAILABLE`, so aggregates will publish flagged rather than verified, and the
   defect in §10.4 stays invisible.
5. **The report catalogue is assumed to be the complete consumer list.** If a report outside it
   needs a daily grain or a district-denormalized row, D2 and §5.5 change.
6. **Expenditure, grants, in-kind donations and Nirantara are assumed out of scope.** Their fact
   tables are designed in outline and not built; no aggregate for them is proposed, and none
   should be built before the facts exist.
7. **A single currency is assumed.** Asserted at compute time rather than trusted.
8. **The financial year is assumed statutory** (1 April–31 March) and not per-temple
   configuration, per `FinancialYear`'s javadoc.
9. **`availability` is assumed resolvable per temple** from `fin_temple_capability`. Under D1's
   multi-source world it would need to be per source, which that table cannot currently express.

---

## 22. Documentation/code discrepancies

Found by reading both. None is fixed by this task; each is reported.

1. **`fin_reconciliation_result` is documented with columns and statuses that do not exist.**
   `FINANCE_DATA_MODEL.md` §8 lists `canonical_total`, `variance`, `variance_pct`, `tolerance`,
   `diagnosis`, `investigated_by`, `resolved_at` and statuses
   `MATCHED`/`WITHIN_TOLERANCE`/`VARIANCE_DETECTED`/`FAILED`/`NOT_RECONCILABLE`. The code has
   `central_total`, `difference`, `difference_pct`, `tolerance_pct`, `status_reason` and
   `PASSED`/`FAILED`/`NOT_AVAILABLE`/`PENDING`. The document predates FIN-060 and was not updated.

2. **`period_type` values disagree.** `FINANCE_DATA_MODEL.md` §7 writes `FY`/`MONTH`/`DAY`; the
   `PeriodType` enum is `DAY`/`MONTH`/`FINANCIAL_YEAR`/`FULL_HISTORY`. The enum spelling is
   authoritative — it is already persisted in `fin_reconciliation_result`.

3. **Neither documented aggregate table carries `source_system_id`.** The same omission as
   limitation 47, one layer up, and it would make the gate inapplicable (§6.1).

4. **`FINANCE_REPORT_CATALOG.md` claims R6 works for the first source** ("Kollur ✅, 3,950,072 in
   FY2025-26"), but limitation 46 records that no source has declared
   `REVENUE_TRANSACTION_COUNT`, so `transaction_count` will be NULL on every fact and R6 will
   report `NOT_AVAILABLE`. The catalogue describes what the source database contains; the platform
   cannot currently carry it.

5. **`fin_temple_capability` is documented as per-temple-per-source** (the column is `NOT NULL`)
   but constrained as per-temple (`uk_ftc_temple_capability UNIQUE (temple_id, capability)`), and
   the repository lookup ignores the source. Not previously recorded anywhere.

6. **ADR-011 is still marked `Proposed`** though it is the stated basis for FIN-070/071/072 and for
   the report catalogue's data sources. See D8.

7. **`FINANCE_DATA_MODEL.md` §7 documents `pct_of_total` as stored**, which creates a derived
   figure that goes stale whenever a sibling row changes. See D6.

8. **The data model's aggregate section says nothing about how a blocked period is represented.**
   ADR-011 says prior figures stay visible; the table sketch has no column or mechanism for it.
   §9.1 resolves this by writing nothing rather than by adding a status.

---

## Verification

- Production source code changed: **none**. No `.java`, `.sql`, `.ts` or `.tsx` file was modified.
- Migrations created: **none**.
- Basis: direct inspection of the files listed in §2.1 at HEAD `f984720`, not documentation.
- FIN-061 policy represented as implemented (`PASSED` publish · `NOT_AVAILABLE` publish flagged ·
  `FAILED` block · `PENDING` block), with the granularity gap in §8.3 stated rather than glossed.
- The `source_system_id` fact-grain limitation is addressed in §6.4 with a recommendation, a
  named alternative and an open decision.
- No historical rerun capability is designed, proposed as buildable now, or implied.
- Unsupported assumptions listed in §21; discrepancies in §22.
- FIN-070, FIN-071 and FIN-072 remain `NOT_STARTED`.

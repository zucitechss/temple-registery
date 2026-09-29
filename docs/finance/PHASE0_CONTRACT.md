# Phase 0 — The Contract Commit

**Status:** COMPLETE
**Date:** 2026-09-29
**Purpose:** everything two people would otherwise edit in the same week, settled once so the
Financial Dashboard build can run as two independent streams.

Read with the target architecture and the work split. This document is the authoritative record
of what was frozen and why; where it and a diagram disagree, this wins.

---

## What this commit is for

The Financial Dashboard specification is split between two developers at the **database table**:

| Stream | Owns |
|---|---|
| **A — schema and reads** | every migration, entity and repository; service resolution; aggregation; report services and controllers; export; the DC dashboard; the nightly scheduler and connector |
| **B — writes and input** | staging and pipeline stages for the four new subjects; the manual input API; Excel import; the freshness job and alerts; the temple-facing UI |

A table definition is a contract both sides build against without either one existing yet. Stream A
inserts fixture rows and asserts what a report returns; stream B stages rows and asserts what lands
in the fact table. Neither runs the other's code.

**This is the only serialized work in the plan.** Migrations are a numbered sequence and five of the
enums are three lines each; there is no split in which nobody ever waits. Making that part small,
explicit and early is the whole point.

---

## 1. Migrations — V130 to V139

Stream A owns the sequence. **Reserved ranges: A takes `V140`–`V169`, B takes `V170`–`V189`.**
Flyway does not care about gaps, and two people never pick the same number. If B uses a number,
say so in the pull request title.

> **The whole finance block moved up by 8 when `main` was merged** (`V110`–`V131` → `V118`–`V139`).
> `main` had already shipped its own `V110`, `V111`, `V112`, `V114`, `V116` and `V117`, and Flyway
> refuses to start with a duplicate version. Main is deployed, so ours moved; the block had to stay
> contiguous and above `V117` because the finance migrations alter each other in order. Any database
> that already ran the old numbers must be dropped and re-migrated — its history rows name files that
> no longer exist.

| Migration | What it does |
|---|---|
| `V130__finance_ingestion_source_generalisation` | `connector_bean` nullable; `fin_sync_batch.actor_user_id`; `uk_ftc_temple_capability` widened to include `source_system_id` |
| `V131__finance_service_dim_special` | `fin_service_dim.is_special` (FR8) |
| `V132__finance_canonical_expenditure` | `fin_expense_category` (13 seeded rows) + `fin_expense_fact`. **Carries the record-grain contract in its header — read it before writing any load.** |
| `V133__finance_dc_approved_funds` | `fin_dc_fund` + `fin_fund_utilisation` (FR5, FR6) |
| `V134__finance_precious_items` | `fin_precious_item_fact` (FR7), with per-field provenance across two channels |
| `V135__finance_nirantara_seva` | `fin_nirantara_subscription` + `fin_nirantara_payment` (FR11) |
| `V136__finance_subject_staging` | `fin_stg_expense`, `fin_stg_fund`, `fin_stg_precious_item`, `fin_stg_nirantara` |
| `V137__finance_daily_freshness_alerts` | `fin_data_expectation`, `fin_daily_data_status`, `fin_data_alert` (FR17–FR20) |
| `V138__finance_upload_file` | `fin_upload_file` (FR19) |
| `V139__finance_service_and_expense_aggregates` | `fin_agg_revenue_service`, `fin_agg_expense_period` |

Nobody edits a merged migration. A correction is a new migration. Run `flyway:clean` and a full
migrate locally after every rebase — it is the cheapest way to catch a bad merge.

---

## 2. The grain of every new fact — one row per source record

**The single most important decision in Phase 0.** It is deliberately not the revenue grain, and
both streams depend on it.

```
fin_revenue_fact   one row per (temple, source, day, service, category,
                   payment mode, counter, operator)                    -- ADR-003
these facts        one row per (source_system_id, source_record_ref)
```

Revenue collapses to a daily grain because one source holds 22,348,125 receipts. Expenditure does
not: a temple records a handful of vouchers a day, each with its own payee and receipt, and
collapsing three vouchers in one category on one day would destroy exactly the detail FR4's grid
exists to show.

Consequences, all intended:

- **A re-run restates, it does not duplicate.** Same guarantee `uk_frf_grain` gives revenue.
- **`source_record_ref` is never null** on these facts, unlike on the revenue fact where a grouped
  row has no single source record. Provenance is row-exact: the trail reaches an individual form
  submission or spreadsheet cell.
- **A correction re-submits the same ref.** The input API returns the ref on create and an edit
  posts it back, so an edit restates one line instead of appending a second.

### Replacing a whole day is an importer operation, not a grain

A second workbook covering an already-loaded day carries different row numbers, so the upsert alone
would leave the first file's facts in place and double the day. **On commit, the importer deletes
what that source previously wrote for each date the file covers, then inserts.**

`deleteBySourceAndDateRange` exists on each fact repository for this, and is scoped by
`source_system_id` so one channel can never delete another channel's figures. `idx_*_supersede`
makes it an index range scan.

This is a protocol between the importer and the grain. The unique key cannot enforce it and is not
meant to.

### No native upsert

`FinRevenueFactRepository.upsert` is native `ON DUPLICATE KEY UPDATE` because a connector load
writes ~121k rows a batch. These do not: a submission is one row, an import is a few hundred.
`findBySourceSystemIdAndSourceRecordRef` then `save` is the same restatement with one extra read,
and keeps the mapping in JPA where a new column cannot be silently dropped from a hand-written
column list.

> `ponytail:` read-then-write upsert. Switch to native if bulk import volume ever makes the extra
> read measurable, or if concurrent imports of one file become possible.

---

## 3. Enums — frozen

| Enum | Change |
|---|---|
| `ConnectorType` | `+ MANUAL_ENTRY`, `+ FILE_UPLOAD`, `+ isAutomated()` |
| `SourceTechnology` | `+ MANUAL` |
| `SyncTrigger` | `+ TEMPLE_INPUT`, `+ EXCEL_UPLOAD` |
| `MappingType` | `+ EXPENSE_CATEGORY`, `+ FUND_CATEGORY`, `+ NIRANTARA_TYPE` |
| new | `MetalType`, `WorkStatus`, `DataSubmissionStatus`, `AlertSeverity`, `AlertStatus`, `UploadStatus`, `ValuationSource`, `NirantaraStatus`, `FundStagingRecordKind`, `NirantaraStagingRecordKind` |

**`ConnectorType.isAutomated()` is the only definition of "automated".** FR17 alerts any temple
without an automated connector; the nightly scheduler runs only those that have one; and
`connector_bean` is required for exactly the types it returns `true` for. There is deliberately no
separate is-manual flag — a flag and a connector type can disagree, and then nothing decides which
one governs whether a temple gets alerted.

**`AlertSeverity.forConsecutiveMissedDays(int)` is the only place FR18's day-8 boundary is
written.** Derived on every evaluation, never incremented, which is what lets a partial backfill
de-escalate an alert rather than leaving it stuck.

**`DataSubmissionStatus.NIL_RETURN`** makes "we spent nothing on Tuesday" recordable. Without it a
temple that complied gets alerted — the absent-is-not-zero confusion of ADR-007 arriving through
the freshness model. `WAIVED` covers a day nothing was expected on; without it every closure day
produces an alert nobody can clear.

---

## 4. Entities and repositories

17 new entities and 18 new repositories, all under stream A's ownership. **Stream B uses them and
never edits them.** A query A did not anticipate goes in a `*WriteRepository` in B's ingestion
package — two interfaces over one table is normal; two people editing one interface is not.

Two existing entities changed: `FinSyncBatch` gains `actorUserId`, `FinServiceDim` gains `special`.
`FinSourceSystem.connectorBean` became nullable — **the entity, not only the column.** The test
caught this: the migration made the column nullable while the entity still declared
`nullable = false`, and H2 generated a NOT NULL column from the entity.

`FinServiceDimRepository` is new, and its absence was the problem it fixes: `fin_service_dim` has
been a table since V120 with no repository, no seed and no writer, which is why `service_id` is
always null and FR8/FR10 are unimplementable (FIN-D-069). Creating the interface does not fix that
— service resolution is stream A's first task — but it means nobody invents a second one.

---

## 5. Cross-stream interfaces — exactly two

**1. `AggregateRefresh`** (`service/finance/aggregation/AggregateRefresh.java`)

One method. A owns the implementation; B calls it after a load and tests against a recording fake.
It exists so that a load stage cannot reach past `ReconciliationGate`: behind the interface, the
gate is not skippable.

```java
Outcome refresh(Long templeId, Long sourceSystemId, FinanceCapability capability, String financialYear);
```

Contract: idempotent, gated per period, **never throws for a blocked period** (the load succeeded;
only publication was withheld), called after the facts are committed.

**2. `<FinanceAlertPanel />`** (`features/finance-alerts/FinanceAlertPanel.tsx`)

Props frozen in `financeCoreTypes.ts` as `FinanceAlertPanelProps`. B implements; A imports and
places it on the DC dashboard. A Phase 0 placeholder is committed that **renders `null`** — an
alert panel saying "no alerts" before the feature exists would tell a DC user their temples are up
to date, which is the one wrong thing it could say. B replaces the file wholesale, so no caller
changes.

**If a third cross-stream interface appears, stop.** That is a sign the seam moved, and the moment
to re-cut the split rather than add another port.

---

## 6. Other frozen items

| Item | Detail |
|---|---|
| `RoleConstants.CAN_ENTER_TEMPLE_FINANCE` | `SUPER_ADMIN`, `TEMPLE_AUTHORITY`. DC roles deliberately absent — the specification gives the DC office no data-entry role. **Grants nothing on its own:** every endpoint it guards must also resolve the path temple against `ScopeHelper.Claims`. |
| `ReportTableResponse` + `ReportColumn` + `ReconciliationSummary` | The one response shape all eleven reports return. Chart, grid, PDF and spreadsheet render from one call, so a figure cannot differ between them. **Check `availability` before `rows`** — an unanswerable report returns a reason and *no rows*, so an empty list means only that the period genuinely had nothing. |
| `pom.xml` | Apache POI 5.3.0. One dependency, both directions: the FR19 template needs a version marker and constrained cells that CSV cannot express, and once POI is in the build the FR15 download uses it rather than shipping a CSV labelled as Excel. |
| `financeCoreTypes.ts` | `features/finance/`. The shared types moved out of `finance-reporting` and are re-exported from their old home so existing imports keep working. A owns it; other folders import and do not extend. |
| `routePaths.ts` | `DC_FINANCE_DASHBOARD`, `TA_FINANCE_INPUT`, `TA_FINANCE_UPLOAD`, `TA_FINANCE_STATUS`. Both streams' entries added in one commit. |
| Test filter | Extended in HANDOFF step 3 with `*Mapping*`, `*Onboarding*`, `*Aggregat*`, `*Phase0*`. The first three were already missing — about a hundred tests were outside the documented baseline. |

---

## 7. What Phase 0 does not settle

Four questions still change what gets built, and each belongs to whoever reaches it first:

- **Expenditure source.** FR1.3 says "available (inferred) in SOHAM"; FR4 says "requires Temple
  Management Input UI". The table is the same either way — only which `source_system_id` writes it
  differs — so this is a backlog question, not a schema one. Treated as manual until told otherwise.
- **Nil-return authority.** Whether a temple may declare its own, or whether it needs DC assent.
  The schema permits both by recording `waived_by`.
- **Escalation counting.** Recorded here as consecutive missed days ending today, which is what
  `AlertSeverity` implements. Confirm before B builds the job.
- **Restatement history.** An upsert overwrites the previous value; the batch trail survives, the
  superseded *figure* does not. If a DC-facing audit needs the prior value, that is an append-only
  revision row, and it is far cheaper to decide now than to add retrospectively.

Staging retention (Q7) also remains open, and now matters more: purging connector staging is cheap,
but purging manual and Excel staging destroys the only row-exact provenance those lanes have.

---

## 8. Verification

| Check | Result |
|---|---|
| `mvn -DskipTests clean compile` | pass |
| `mvn -DskipTests test-compile` | pass |
| `mvn -o test -Dtest='FinancePhase0RulesTest'` | **9 passing** — the three behavioural rules, no Spring, no Docker |
| `mvn -o test -Dtest='FinancePhase0SchemaTest'` | **29 passing** — real MySQL 8.0 container, real Flyway, following `RevenueAggregatePersistenceTest`. Green on 2026-09-29. |
| Full finance filter | **578 run, 0 failures, 0 errors, 238 skipped** (all skips are Testcontainers classes) |
| `npx tsc --noEmit` | pass |
| Frontend finance tests | **190 passing, 16 files** |

**The schema test found one thing, and it was worth the run.**
`deleteBySourceAndDateRange` threw `TransactionRequiredException`: a `@Modifying` query gets no
transaction of its own, unlike the CRUD methods, so it requires an ambient one. That is the
behaviour to keep — the delete is only safe as the first half of a delete-then-insert, and a
`@Transactional` on the repository would let it commit alone and empty the day when the insert
that was meant to follow it fails. So the method is now documented on all three supersede
repositories rather than "fixed", and the test calls it through a `TransactionTemplate`, the way
the importer will. **Stream B: wrap the supersede and the insert in one transaction.**

The `test` profile cannot substitute: it runs H2 with `ddl-auto: create-drop` and Flyway disabled,
so it generates the schema from the entities and therefore cannot disagree with them. A migration
that forgot a unique key would pass. It also carries no seed rows, and the expenditure taxonomy is
part of the contract.

---

## 9. Starting work

1. **`FinancePhase0SchemaTest` is green** (29/29, 2026-09-29). Re-run it after every rebase and after the main merge — it is the only check that reads the migrations rather than the entities.
2. Settle the four questions in §7 that affect your stream.
3. Branch: `feature/db-integration-clean` is the integration branch, synced with `main`;
   `feature/fin-read` (A) and `feature/fin-input` (B) come off it. Rebase weekly. Merge into the
   integration branch, never into each other.
4. **Stream A** starts with service resolution — two reports are currently unimplementable and
   nothing in the code says so.
   **Stream B** starts with registering a manual source, then expenditure ingestion.
5. **M1, around week 3: the expenditure vertical slice.** B's write path and A's read path merged,
   one figure traced from form to dashboard, the provenance query returning the uploader and the
   staged payload. If the grain, the provenance columns or the refresh port are wrong, that is
   where it surfaces — in one subject, not in five. Do not defer it to the end.

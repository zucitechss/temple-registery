# Finance Platform — Handoff

**Updated:** 2026-09-16
**Branch:** `feature/db-integration`
**Read first**, then [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md),
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Current State

**What works.** The Phase 1 foundation exists and is tested: seven tables, seven entities,
seven repositories, eleven enums. Migration `V110` applies cleanly to MySQL 8.0. The
finance schema can record which external system feeds which temple, what each temple can
and cannot answer, which source field is authoritative for each metric, how source values
map to canonical ones, and the full audit trail of every sync attempt and reconciliation.

**What does not work yet.** Nothing is connected to anything. There is no connector, no
extraction, no canonical fact table, no API, and the dashboard is still the static HTML
file it was. No row of temple financial data has been read or stored. This is expected:
Phase 1 is the spine, not the pipeline.

---

## Last Completed Task

**FIN-015** — `FinanceFoundationRepositoryTest`, 11 tests, all passing. This concluded
FIN-010 through FIN-015.

---

## Currently In Progress

Nothing is half-built. Every file committed compiles and is covered by a passing test.

---

## Next Task

**FIN-016 — sync-worker profile split.**

Add `@Profile("sync-worker")` to connector and sync-scheduler wiring, `@Profile("!sync-worker")`
to finance controllers, and verify by test that the context loads correctly under each
profile with the right beans absent.

Do this **before** writing any connector. It is the mechanism that makes "the registry
runtime never reaches a temple database" a structural property rather than a convention —
if connectors are written first, they will be wired into the monolith and the split will
never happen (this is risk **R12**, the single most likely way this architecture quietly
degrades).

FIN-021 through FIN-024 (seeding Kollur configuration) are also unblocked and can follow
immediately; seeding is safe because `sync_enabled` defaults to `0` and contacts nothing.

---

## Files Recently Changed

All additions. No existing file was modified.

**Migration**
- `backend/src/main/resources/db/migration/V110__finance_foundation.sql`

**Enums** — `backend/src/main/java/com/templeregistry/entity/finance/enums/`
`ConnectorType` · `DataAvailability` · `FinanceCapability` · `MappingType` · `PeriodType` ·
`ReconciliationStatus` · `SourceTechnology` · `SyncStage` · `SyncStatus` · `SyncTrigger` ·
`SyncType`

**Entities** — `backend/src/main/java/com/templeregistry/entity/finance/`
`FinSourceSystem` · `FinTempleCapability` · `FinSourceOfTruthDecl` · `FinMappingRule` ·
`FinSyncBatch` · `FinSyncError` · `FinReconciliationResult`

**Repositories** — `backend/src/main/java/com/templeregistry/repository/finance/`
one per entity, same names with `Repository` suffix.

**Test**
- `backend/src/test/java/com/templeregistry/repository/finance/FinanceFoundationRepositoryTest.java`

**Documentation** — `docs/finance/`
`API_CONTRACT.md` · `IMPLEMENTATION_OWNERSHIP.md` · `IMPLEMENTATION_STATUS.md` ·
`IMPLEMENTATION_TASKS.md` · `IMPLEMENTATION_DECISIONS.md` · `HANDOFF.md`

---

## Database Changes

`V110__finance_foundation.sql` creates seven tables, all additive, no changes to existing
tables:

`fin_source_system` · `fin_temple_capability` · `fin_source_of_truth_decl` ·
`fin_mapping_rule` · `fin_sync_batch` · `fin_sync_error` · `fin_reconciliation_result`

No foreign keys (FIN-D-003). Fifteen indexes. Verified applied by Flyway against MySQL 8.0
in a Testcontainers run — Flyway reported `now at version v110`.

---

## APIs Added or Changed

None. No controller, no endpoint, no DTO. The contract is written
([API_CONTRACT.md](API_CONTRACT.md)) but unimplemented.

---

## Tests

| Command | Result |
|---|---|
| `mvn -o compile -DskipTests` | BUILD SUCCESS |
| `mvn -o test -Dtest=FinanceFoundationRepositoryTest` | **11/11 pass**, 24.8 s |
| `mvn -o test` (full suite) | **866 run · 0 failures · 18 errors** — all 18 pre-existing, see below |

The 18 errors fall in two classes only — `ApplicationContextIntegrationTest` (1) and
`TrustIntegrationTest` (17) — and share a single root cause unrelated to finance.

**Verified by experiment, not by assumption:** all finance code and `V110` were stashed
and `TrustIntegrationTest` re-run on the clean tree. It produced the identical 17 errors
with the identical root cause. The finance work neither caused nor worsened them.

The repository test covers, in addition to persistence: the kill switch default, the
absence of credential fields (by reflection), availability reasons surviving round-trip,
supersession of source-of-truth declarations, exclusion of inactive mapping rules, the
distinction between latest attempt and latest success, retry exhaustion, and null (not
zero) reconciliation totals.

---

## Known Problems

**1. Two integration test classes fail (18 errors) — not caused by this work.**

```
Schema-validation: missing column [field_names_json] in table [declaration_clarifications]
```

Affects `ApplicationContextIntegrationTest` and all 17 `TrustIntegrationTest` cases; both
load the full application context with `ddl-auto: validate` after Flyway, so both trip on
the same missing column and every nested test cascades from one context-load failure.

`DeclarationClarification` maps `field_names_json`; the table as created in
`V1__initial_schema.sql:657` has no such column and no migration adds it. The column exists
in running environments only because `spring.jpa.hibernate.ddl-auto: update` creates it
silently.

Confirmed pre-existing twice over: by inspection (the column is in a table no finance
migration touches, and no migration anywhere adds it) and by experiment (stashing all
finance code reproduces the identical failures).

Why it matters here: that test is the **only** automated check that migrations agree with
the entity model, and it is exactly the check every future finance migration wants. While
it fails, `V110` and its successors are verified only by the weaker evidence that Flyway
applied them without error.

Recommended fix, owned by the declaration module, not applied here:
`ALTER TABLE declaration_clarifications ADD COLUMN field_names_json JSON NULL;`
Whether deployed databases already carry the column (via `ddl-auto`) determines whether
this is a no-op or a real change, which is why it needs that module's owner rather than a
blind edit from here. This is risk **R7** from the architecture document, now observed.

**2. Namespace collision to be aware of.** `com.templeregistry.event.finance` already
exists and belongs to the **trust financial declaration workflow** — a trust submitting
annual figures for DC approval. It has nothing to do with this platform. Do not extend it,
and do not let the two meet. New work goes in `entity.finance`, `repository.finance`,
`service.finance`, `connector.finance`.

---

## Known Data Limitations

Binding, established by measurement during the analysis phase. Full list in
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

The five that most often get implemented wrongly:

1. **`DailySevaNewOld` must never be read.** It duplicates all six FY archive tables.
   Including it doubles every historical figure.
2. **`DailySevaNewDetails.TotalAmount` is not revenue.** It is 41 % below the header total
   with exact 1:1 row correspondence and zero orphans — the detail table is internally
   inconsistent. It is the *more granular* table, which is precisely why someone will be
   tempted by it.
3. **Expenditure, grants and metal valuation do not exist in the source.** They are
   `NOT_AVAILABLE`. Never `0`.
4. **Nirantara execution is not recorded.** `seva_Prepared = 1` appears on 17,808
   future-dated rows into 2027, so it means "schedule generated", not "performed". Never
   compute a fulfilment percentage from it.
5. **Receipt counts are not devotee counts.** Do not label them as such anywhere.

---

## Important Decisions

Implementation-time decisions FIN-D-001 … FIN-D-006 are in
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md). The two with the widest reach:

- **FIN-D-002** — `fin_source_system` has no password, host, port or connection-string
  column, only a `credential_ref` alias. An encrypted connection string was considered and
  rejected: it would place a decryptable route to every temple database inside the registry
  process, which is what ADR-001 forbids. A reflection test fails the build if such a field
  is ever added.
- **FIN-D-005** — "latest batch" and "latest *successful* batch" are separate repository
  methods. Conflating them would let a failed run advance the watermark past a window it
  never loaded, losing revenue silently.

---

## Open Questions Still Blocking Later Phases

| # | Question | Blocks |
|---|---|---|
| Q4 | What network path exists from the platform to the Kollur database? | FIN-041 |
| Q5 | Where are temple source credentials stored, given no secrets manager? | FIN-020 |
| Q1 | Expenditure approach — no source data exists | 5 dashboard widgets |
| Q2 | Should Nirantara execution capture be requested from the temple? | FIN-120 |
| Q10 | Sync worker deployment target | FIN-016 rollout (not the code) |

Q4 and Q5 do not block FIN-016, FIN-021 … FIN-024, or FIN-030.

---

## How To Continue

1. Read this file, then `IMPLEMENTATION_STATUS.md` and `IMPLEMENTATION_TASKS.md`.
2. `git status` and `git log --oneline -5` on `feature/db-integration`.
3. Confirm the baseline: `cd backend && mvn -o test -Dtest=FinanceFoundationRepositoryTest`
   should be 11/11.
4. Implement FIN-016 only. Do not start a connector in the same change.
5. Run the test suite, update the four tracking documents, commit as `FIN-016 ...`.

Do not repeat the architectural analysis. It is complete and in `docs/finance/`.

---

## NEXT ACTION

Implement **FIN-016**: add the `sync-worker` Spring profile split — `@Profile("sync-worker")`
on connector and sync-scheduler wiring, `@Profile("!sync-worker")` on finance controllers —
and add a context test asserting that finance controller beans are absent under the
`sync-worker` profile.

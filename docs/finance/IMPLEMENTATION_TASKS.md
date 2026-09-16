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
| FIN-016 | Sync-worker profile split (`@Profile("sync-worker")` / `@Profile("!sync-worker")`) | NOT_STARTED | FIN-014 | `config/`, `TempleRegistryApplication` | context test per profile |

**FIN-016 is not optional and must not slip.** It is the mechanism that makes ADR-001
structural rather than a convention (risk R12). It belongs in Phase 1, before any
connector exists to be wired into the wrong process.

---

## Phase 2 — Source Configuration

| ID | Description | Status | Depends on | Notes |
|---|---|---|---|---|
| FIN-020 | Credential resolution from environment/secret config, sync-worker only | BLOCKED | FIN-016 | Blocked on open question **Q5** — no secrets manager exists, and `application.yml` currently carries committed fallback DB credentials. Temple credentials must not join that arrangement. |
| FIN-021 | Register Kollur source system (seed migration) | NOT_STARTED | FIN-011 | `temple_id=300001`, `system_code=KOLSOHAM`, `source_temple_code=43`, `sync_enabled=0` |
| FIN-022 | Declare Kollur capabilities with reasons | NOT_STARTED | FIN-021 | 19 rows; the `NOT_AVAILABLE` reasons are user-facing copy |
| FIN-023 | Declare Kollur source of truth for `REVENUE_AMOUNT` | NOT_STARTED | FIN-021 | Includes the three measured rejected alternatives |
| FIN-024 | Seed Kollur mapping rules (category, service, metal type) | NOT_STARTED | FIN-021 | |

---

## Phase 3 — Connector Framework

| ID | Description | Status | Depends on |
|---|---|---|---|
| FIN-030 | `TempleFinanceConnector` interface and supporting types | NOT_STARTED | FIN-016 |
| FIN-031 | Connector registry resolving `connector_bean` | NOT_STARTED | FIN-030 |
| FIN-032 | `testConnection` and `describeCapabilities` | NOT_STARTED | FIN-030 |

Capabilities are declared per connector. No connector implements a capability its temple
does not have.

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
| FIN-020 | Q5 — credential storage with no secrets manager | Business / infra decision |
| FIN-041 | Q4 — network path to the Kollur database | Infra / temple IT |

Neither blocks Phase 1 completion. FIN-016 can and should proceed.

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

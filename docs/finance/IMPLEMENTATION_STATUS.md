# Finance Implementation Status

**Updated:** 2026-09-16
**Branch:** `feature/db-integration`
**Primary handoff document:** [HANDOFF.md](HANDOFF.md)

---

## Current Phase

**Phase 1 — Finance Foundation.** Configuration and operational spine implemented and
tested. One Phase 1 task remains: the sync-worker profile split (FIN-016).

---

## Overall Progress

| Phase | Status | % | Notes |
|---|---|---:|---|
| Architecture | COMPLETE | 100 | 6 design docs + 11 ADRs, delivered previously |
| Finance Foundation | IN_PROGRESS | 85 | FIN-010…FIN-015 complete; FIN-016 (profile split) outstanding |
| Source Configuration | NOT_STARTED | 0 | FIN-020 blocked on Q5 |
| Connector Framework | NOT_STARTED | 0 | |
| Kollur Connector | NOT_STARTED | 0 | FIN-041 blocked on Q4 |
| Staging | NOT_STARTED | 0 | |
| Canonical Finance Data | NOT_STARTED | 0 | |
| Revenue Pipeline | NOT_STARTED | 0 | |
| Reconciliation | NOT_STARTED | 0 | Tables exist; service does not |
| Aggregation | NOT_STARTED | 0 | |
| Finance APIs | NOT_STARTED | 0 | Contract written, no code |
| Dashboard | NOT_STARTED | 0 | Still the static iframe |
| Seva | NOT_STARTED | 0 | |
| Precious Metals | NOT_STARTED | 0 | |
| Nirantara | NOT_STARTED | 0 | |
| Multi-Temple Onboarding | NOT_STARTED | 0 | |
| Incremental Sync | NOT_STARTED | 0 | |
| Monitoring | NOT_STARTED | 0 | |
| Production Hardening | NOT_STARTED | 0 | |

---

## Phase 0 — Architecture & Contracts · COMPLETE · 100%

**Completed.** Existing backend inspected (Spring Boot 3.4.4, Java 21, MySQL-protocol
TiDB, Flyway, Lombok, MapStruct, Caffeine, 94 test classes). Conventions identified and
followed rather than invented: `BaseEntity` for config-style domain entities, the
`EmailOutbox` standalone pattern for operational logs, `V1xx__snake_case.sql` migrations
with separate `CREATE INDEX` statements, `@Enumerated(EnumType.STRING)` throughout.

Architecture contracts checked for contradictions before writing code. None material.

**Remaining.** None.
**Blockers.** None.
**Files.** `docs/finance/API_CONTRACT.md`, `docs/finance/IMPLEMENTATION_OWNERSHIP.md`.

---

## Phase 1 — Finance Foundation · IN_PROGRESS · 85%

**Completed.**

Seven tables, seven entities, seven repositories, eleven enums, eleven tests.

*Configuration* — `fin_source_system`, `fin_temple_capability`,
`fin_source_of_truth_decl`, `fin_mapping_rule`. These extend `BaseEntity`: they are
reviewed, authored, soft-deletable configuration with real human ownership.

*Operational* — `fin_sync_batch`, `fin_sync_error`, `fin_reconciliation_result`. These
follow the `EmailOutbox` precedent instead: append-mostly logs where status transitions
make soft-delete meaningless.

Three design points are load-bearing and were verified by test rather than asserted:

1. **`fin_source_system` cannot hold a credential.** There is no password, host, port or
   connection-string column — only `credential_ref`, an alias the sync worker resolves.
   A reflection test fails if such a field is ever added, so ADR-001 is enforced by the
   build rather than by reviewer vigilance.
2. **`sync_enabled` defaults to `0`.** Registering a source system never, by itself,
   causes the platform to contact a temple.
3. **Latest attempt and latest success are separate queries.** A failed batch following a
   successful one must not advance the watermark, or the pipeline would skip a window it
   never loaded.

**Remaining.** FIN-016 — sync-worker profile split.

**Blockers.** None for FIN-016.

**Tests.** `FinanceFoundationRepositoryTest` — 11 tests, all passing.

**Migrations.** `V110__finance_foundation.sql` — verified to apply cleanly to MySQL 8.0
via Testcontainers (Flyway reports "now at version v110").

**Files changed.** See [HANDOFF.md](HANDOFF.md).

**Decisions.** FIN-D-001 … FIN-D-006, recorded in
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Phase 2 — Source Configuration · NOT_STARTED · 0%

**Remaining.** FIN-020 … FIN-024.

**Blockers.** FIN-020 is blocked on **Q5**. There is no secrets manager in this
deployment, and `application.yml` currently carries committed fallback database
credentials. Temple source credentials must not be stored the same way, so the storage
mechanism needs a decision before the code that reads it is written.

FIN-021 … FIN-024 (seeding Kollur configuration) are not blocked and can proceed
independently, because registering a source system with `sync_enabled = 0` contacts
nothing.

---

## Phases 3–17 · NOT_STARTED · 0%

See [IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md) for the task-level breakdown.

FIN-041 is blocked on **Q4** — there is no agreed network path from the platform to the
Kollur database, and the connector cannot be tested without one. Note that this is exactly
the situation where `PUSH_AGENT` rather than `PULL_JDBC` may be the answer; the connector
model already supports both, so the decision does not require rework.

---

## Known Defect Outside Finance Scope

The full suite reports **866 tests, 0 failures, 18 errors**. All 18 are in
`ApplicationContextIntegrationTest` (1) and `TrustIntegrationTest` (17), and all share one
root cause: `ddl-auto: validate` rejecting
`missing column [field_names_json] in table [declaration_clarifications]`.

Pre-existing and unrelated to finance work — the column is mapped by
`DeclarationClarification` but no migration creates it, so it exists in deployed
environments only because `ddl-auto: update` adds it. Confirmed by stashing all finance
code and reproducing the identical failures on a clean tree.

This matters to the finance platform for one reason: that test is the only automated check
that migrations agree with the entity model, and every future finance migration would
benefit from it. Detail in [IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md) under
FIN-X-001.

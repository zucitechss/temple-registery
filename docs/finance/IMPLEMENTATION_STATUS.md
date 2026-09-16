# Finance Implementation Status

**Updated:** 2026-09-16
**Branch:** `feature/db-integration`
**Primary handoff document:** [HANDOFF.md](HANDOFF.md)

---

## Current Phase

**Phase 1 — Finance Foundation · COMPLETE.** Configuration and operational spine
implemented and tested, and the registry / sync-worker runtime boundary is in place and
proven by test. Next work is Phase 2 or Phase 3; both are unblocked.

---

## Overall Progress

| Phase | Status | % | Notes |
|---|---|---:|---|
| Architecture | COMPLETE | 100 | 6 design docs + 11 ADRs, delivered previously |
| Finance Foundation | **COMPLETE** | 100 | FIN-010…FIN-016, including the runtime boundary |
| Source Configuration | IN_PROGRESS | 20 | FIN-020 abstraction delivered by FIN-016; Q5 decides the permanent store |
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

## Phase 1 — Finance Foundation · COMPLETE · 100%

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

**FIN-016 — the runtime boundary.** The registry and the finance sync worker are now two
runtimes built from one artifact, separated where a real constraint sits: credentials and
network reach.

The registry runtime contains **no** bean that can resolve a temple credential or reach a
temple source system, and that is asserted against the fully assembled application, not
just against the configuration classes. The worker runs non-web and refuses to start if it
ever comes up serving HTTP.

Two things were done differently from the obvious approach, both because the obvious
approach fails open:

1. **Worker beans are explicit `@Bean` registrations, not `@Component` + `@Profile`**
   (FIN-D-008). A forgotten profile annotation on a component would place a connector in
   the registry runtime silently. A classpath guard test enforces this and was verified by
   mutation — adding a `@Component` to the worker package makes it fail.
2. **`@EnableScheduling` moved to a registry-only configuration** (FIN-D-007) rather than
   annotating individual schedulers, because several scheduler beans expose methods other
   services call.

**A hazard closed in passing.** Had the worker kept `@EnableScheduling`, it would have run a
second copy of every background job. `EmailDeliveryService.processQueue()` claims outbox
rows every ten seconds with no row locking, so a second process would have **delivered
duplicate emails to real recipients**.

**Remaining.** None.

**Blockers.** None.

**Tests.** 43 finance tests across 6 classes, all passing:
`FinanceFoundationRepositoryTest` (11), `SyncWorkerProfileBoundaryTest` (7),
`FinanceIntegrationBoundaryTest` (6), `EnvironmentSourceCredentialProviderTest` (9),
`SyncWorkerRuntimeContextTest` (5), `RegistryRuntimeContextTest` (5).

**Migrations.** `V110__finance_foundation.sql` — verified to apply cleanly to MySQL 8.0
via Testcontainers (Flyway reports "now at version v110").

**Files changed.** See [HANDOFF.md](HANDOFF.md).

**Decisions.** FIN-D-001 … FIN-D-010, recorded in
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Phase 2 — Source Configuration · IN_PROGRESS · 20%

**Completed.** The credential seam (FIN-020, abstraction half). `SourceCredentialProvider`
resolves a `credential_ref` alias to real credentials; the interim implementation reads
`trm.finance.source.<ref>.secret` from the worker process environment. It exists only in
the worker runtime, never falls back to a default, never logs a secret, and validates the
reference so a database row cannot read an unrelated property such as the registry
database password.

**Remaining.** FIN-021 … FIN-024 — seeding Kollur configuration. Not blocked: registering a
source system with `sync_enabled = 0` and a credential *alias* contacts nothing and stores
no secret.

**Blockers.** **Q5** decides the permanent credential store, not whether work can continue.
There is no secrets manager in this deployment, and `application.yml` carries committed
fallback database credentials — which is exactly the arrangement temple credentials must
not join, and why the provider declares its properties in no YAML at all. Replacing the
environment-backed implementation with a vault-backed one is a change to one `@Bean`
method.

---

## Phases 3–17 · NOT_STARTED · 0%

See [IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md) for the task-level breakdown.
FIN-030 (connector contract) is unblocked and is the recommended next task.

FIN-041 is blocked on **Q4** — there is no agreed network path from the platform to the
Kollur database, and the connector cannot be tested without one. This is exactly the
situation where `PUSH_AGENT` rather than `PULL_JDBC` may be the answer, and the design
already treats the two as equals: `ConnectorType` models all four mechanisms, and
`SourceCredentials` carries an *optional* principal so a token or shared key fits the same
shape as a database user. Whichever way Q4 resolves, the canonical model, aggregation,
APIs and dashboard are untouched — the difference is a `connector_type` value and a
connector implementation.

---

## Known Defects Outside Finance Scope

The full suite reports **898 tests, 0 failures, 18 errors**. All 18 are in
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

**FIN-X-002 — the `test` profile cannot boot a full application context.** Discovered while
building the FIN-016 boundary tests. Two independent pre-existing causes: the test JWT
public key (`jwt-test.pub`) is a placeholder that fails to parse, and `application.yml`
carries a TiDB-only `connection-init-sql` that H2 rejects. Neither is caused by finance
work; both were invisible previously because every full-context test fails earlier on
FIN-X-001.

The finance boundary tests work around both with local `@TestPropertySource` overrides,
introducing no key material and changing no production configuration. Worth fixing
centrally: the project currently has no working full-context test on the `test` profile.

# Finance Implementation Status

**Updated:** 2026-09-16 (FIN-031)
**Branch:** `feature/db-integration`
**Primary handoff document:** [HANDOFF.md](HANDOFF.md)

---

## Current Phase

**Phase 3 — Connector Framework · IN_PROGRESS.** Phases 0–2 are complete: the foundation,
the registry / sync-worker runtime boundary, the credential seam and the full Kollur
configuration. The connector contract (FIN-030) and the registry that resolves
`connector_bean` to an implementation (FIN-031) are done; no connector implementation
exists, and resolving one now fails explicitly rather than silently. Next work is the
canonical model (Phase 5), which needs neither Q4 nor a connector.

---

## Overall Progress

| Phase | Status | % | Notes |
|---|---|---:|---|
| Architecture | COMPLETE | 100 | 6 design docs + 11 ADRs, delivered previously |
| Finance Foundation | **COMPLETE** | 100 | FIN-010…FIN-016, including the runtime boundary |
| Source Configuration | **COMPLETE** | 100 | FIN-021..024 seeded; FIN-020 abstraction delivered, Q5 decides only the permanent store |
| Connector Framework | IN_PROGRESS | 70 | FIN-030 contract and FIN-031 registry COMPLETE; FIN-032 outstanding |
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

## Phase 2 — Source Configuration · COMPLETE · 100%

**Completed.** The credential seam (FIN-020) and the full Kollur configuration
(FIN-021 … FIN-024), seeded by `V111__kollur_finance_configuration.sql`.

`SourceCredentialProvider` resolves a `credential_ref` alias to real credentials; the
interim implementation reads `trm.finance.source.<ref>.secret` from the worker process
environment, exists only in the worker runtime, never falls back to a default, never logs a
secret, and validates the reference so a database row cannot read an unrelated property such
as the registry database password.

**Kollur configuration seeded:** 1 source system, 19 capability declarations, 1
source-of-truth declaration, 9 mapping rules. No credential, no endpoint, no schema change,
and `sync_enabled = 0`.

Three things about this seed are worth knowing before touching it:

1. **It declares every capability, including the seven that are unavailable.** Availability
   is data, and "not declared" must never have to be guessed at. Each carries a *measured*
   reason written for a Deputy Commissioner, because that text is rendered where a figure
   would otherwise be — expenditure says the source records none and that the figure is
   unknown *rather than zero*; payment mode says no digital payment was recorded rather than
   claiming cash; metal value says a monetary figure would require an assumed rate and would
   be an invention.
2. **A capability count discrepancy was found and resolved.** The task brief listed 18
   capabilities; the canonical vocabulary has 19. The missing one, `IN_KIND_DONATION`, is
   genuinely available at Kollur (donated sarees with a donor-stated value), and is seeded
   with the warning that it must not be summed with auction proceeds for the same articles.
3. **The seed is conditional on temple 300001 existing** (FIN-D-014). No migration creates
   that temple, so in a fresh database V111 correctly seeds nothing rather than leaving
   orphan configuration.

**Remaining.** None.

**Blockers.** **Q5** decides the permanent credential store, not whether work can continue.
There is no secrets manager in this deployment, and `application.yml` carries committed
fallback database credentials — which is exactly the arrangement temple credentials must
not join, and why the provider declares its properties in no YAML at all. Replacing the
environment-backed implementation with a vault-backed one is a change to one `@Bean` method.

**Tests.** `KollurFinanceConfigurationMigrationTest` — 27 tests against a real MySQL 8.0
container with real Flyway, asserting database state rather than file contents, including
the conditional guard and idempotency.

**Decisions.** FIN-D-014 … FIN-D-016.

---

## Phase 3 — Connector Framework · IN_PROGRESS · 40%

**Completed.** FIN-030 — the `TempleFinanceConnector` contract and ten supporting types in
`com.templeregistry.connector.finance`. Contract only: no implementation, no transport, no
credential, no schema change, no dependency on any JPA entity.

The contract describes a **synchronization capability, not a transport**, which is what
keeps `PULL_JDBC`, `PUSH_AGENT`, `SOURCE_API` and `FILE_DROP` equally implementable. A
`getConnection()` or `getClient()` method would have decided the deployment model for every
future temple on behalf of the first one — and since permission for an inbound connection to
a government temple database is frequently refused as policy rather than capability, that
decision would have been wrong for a large share of them.

Three things the contract deliberately does **not** do, each protecting a decision made
earlier:

1. **It cannot carry a credential.** A connector receives only a credential *alias* and
   resolves it inside the worker (FIN-D-002, FIN-D-009). This is why the contract is safe to
   make visible to both runtimes.
2. **It cannot propose a watermark** (FIN-D-012). The framework fixes the change window
   before calling and stores it only on success, so a failed batch has no mechanism by which
   to skip a window it never loaded.
3. **It cannot reconcile itself.** `sourceTotals()` reports; it never compares, applies a
   tolerance, or decides to publish. A connector judging its own output would be marking its
   own homework.

**FIN-031 — the registry.** `ConnectorRegistry` resolves the identifier held in
`fin_source_system.connector_bean` to the connector registered under that name, and does
nothing else: it creates no connector, resolves no credential, opens nothing and runs no
synchronization. It is registered as a `@Bean` in `SyncWorkerConfig` from the connector beans
declared there, so nothing is component-scanned and FIN-D-008 stays intact, and it is absent
from the registry runtime entirely.

The part worth knowing is what happens when configuration is wrong. **A configured connector
that is not registered is a failure, not an absence** (FIN-D-017): resolution returns a
connector or throws, with no `Optional`, no null and no default implementation anywhere in
the path. Had absence been expressible, the natural handling — skip the source, log,
continue — would produce a batch that succeeded having read nothing, and a temple would
report figures meaning "nobody ran anything" while reading as "nothing happened".

This is the live case, not a hypothetical. Kollur is completely configured and names
`kollurFinanceConnector`, which does not exist, so the missing-connector path is the one the
platform takes today. Two smaller rules fail closed alongside it: a connector must be
registered under the name it declares as its `connectorId` (configuration has one field), and
resolution rejects a connector whose `connectorType` contradicts the source system's declared
integration mechanism, because that column is what firewall approval and the onboarding
record were based on.

**Remaining.** FIN-032 — probe and capability declaration wiring into onboarding.

**Blockers.** None.

**Tests.** 46 across 3 classes: `TempleFinanceConnectorContractTest` (25),
`ConnectorContractPurityTest` (8) and `ConnectorRegistryTest` (13). Both guards were verified
by mutation — a probe importing `java.sql.ResultSet`, naming a password parameter and
mentioning the first temple made 3 purity tests fail; replacing the registry's
missing-connector throw with `return null` failed 4 registry tests, including the Kollur one.

**Decisions.** FIN-D-011 … FIN-D-013, FIN-D-017.

---

## Phases 4–17 · NOT_STARTED · 0%

See [IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md) for the task-level breakdown.
FIN-051 / FIN-052 (canonical dimensions and the daily-grain revenue fact) are unblocked and
recommended next: they depend on neither Q4 nor a connector, and every later stage writes
into them.

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

The full suite reports **958 tests, 0 failures, 18 errors**. All 18 are in
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

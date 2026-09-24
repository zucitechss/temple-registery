# Finance Implementation Status

**Updated:** 2026-09-24 (FIN-058)
**Branch:** `feature/db-integration`
**Primary handoff document:** [HANDOFF.md](HANDOFF.md)

---

## Current Phase

**Phase 5 — Revenue Pipeline · IN_PROGRESS.** Phases 0–2 are complete, Phase 3 is complete
apart from onboarding wiring (FIN-032), and **both ends of the pipeline now exist**: raw
records land in `fin_stg_revenue` (FIN-050) and finished figures live in `fin_revenue_fact`
at daily grain (FIN-052), with dimensions to classify them (FIN-051). Both grains are
enforced by the database.

The middle is now complete: validation (FIN-053) judges a staged record, mapping (FIN-054) says
what its source values mean, normalization (FIN-055) turns it into a dated figure at the
canonical daily grain, and the load (FIN-056) writes it. What the pipeline lacks is not a stage
but a caller: nothing runs it, and nothing fills staging. Both can be built and tested against synthetic staging rows, without
a connector and without an answer to Q4, which is the point of having built the ends first.

---

## Overall Progress

| Phase | Status | % | Notes |
|---|---|---:|---|
| Architecture | COMPLETE | 100 | 6 design docs + 11 ADRs, delivered previously |
| Finance Foundation | **COMPLETE** | 100 | FIN-010…FIN-016, including the runtime boundary |
| Source Configuration | **COMPLETE** | 100 | FIN-021..024 seeded; FIN-020 abstraction delivered, Q5 decides only the permanent store |
| Connector Framework | IN_PROGRESS | 70 | FIN-030 contract and FIN-031 registry COMPLETE; FIN-032 outstanding |
| Kollur Connector | NOT_STARTED | 0 | FIN-041 blocked on Q4 |
| Staging | **COMPLETE** | 100 | FIN-050 — `fin_stg_revenue`; other capabilities get their own staging tables with their phases |
| Canonical Finance Data | **COMPLETE** | 100 | FIN-051, FIN-052 — revenue dimensions and the daily-grain fact; other canonical facts arrive with their phases |
| Revenue Pipeline | COMPLETE | 100 | Staging through canonical load, end to end. No caller yet |
| Reconciliation | **COMPLETE** | 100 | FIN-060 records what was checked; FIN-061 decides what it means for publication. The gate's first consumer is FIN-070/072 |
| Aggregation | **COMPLETE** except service aggregates | 90 | FIN-070/072 build and rebuild `fin_agg_revenue_period`. FIN-071 (`fin_agg_revenue_service`) is **BLOCKED** — no fact carries a `service_id` (FIN-D-069) |
| Finance APIs | **COMPLETE** except `revenue/sevas` | 90 | Administrative (FIN-054A-BE) and reporting (FIN-081/082/083) endpoints both exist. `revenue/sevas` blocked on FIN-071 |
| Dashboard | **COMPLETE** | 100 | FIN-090…093. Reads live from the reporting API for every temple; the static iframe and its hard-coded temple id are deleted |
| Seva | NOT_STARTED | 0 | |
| Precious Metals | NOT_STARTED | 0 | |
| Nirantara | NOT_STARTED | 0 | |
| Multi-Temple Onboarding | **IN_PROGRESS** | 45 | FIN-140 slices 140-A and 140-B COMPLETE — register a source system, declare its capabilities, check its configuration. Source-of-truth writes, activation and the probe are later slices |
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

## Phase 5 — Revenue Pipeline · COMPLETE · 100%

**Completed.** FIN-051 and FIN-052 — `V112__finance_canonical_revenue.sql`, three tables,
three entities, two enums. This is the reporting boundary: from here upwards, every revenue
figure the platform publishes comes from these tables and from nothing else, which is what
ADR-001 means in practice rather than in principle.

*Dimensions.* `fin_revenue_category` is the platform-wide income taxonomy, seeded with twelve
codes and carrying **no `temple_id` column** — a taxonomy each temple invents for itself
cannot produce a district total. `fin_service_dim` is per temple, because service catalogues
genuinely differ; every service still resolves to a platform-wide category, which is what
makes two temples comparable without pretending their seva lists are the same.

*Fact.* `fin_revenue_fact` at daily grain: one row per
`(temple, transaction_date, service, category, payment mode, counter, operator)` rather than
one row per receipt. On the first onboarded source that is 22.3 M receipts becoming ~121 k
rows, and it means no devotee name, address, mobile number or email address is copied into
the central platform at all.

**Three things here are load-bearing, and two of them correct the design documents:**

1. **The documented unique key does not enforce the documented grain.** Three grain columns
   are legitimately nullable — a donation-box collection has no service, counter or operator —
   and MySQL and TiDB treat NULLs in a unique index as distinct. Written literally, ADR-003's
   key accepts the same hundi fact twice and doubles ₹13.40 Cr a year. Generated key columns
   close it (FIN-D-018), **verified by mutation**: with the literal key, the duplicate insert
   succeeds silently.
2. **`operator_ref` was added to the grain** (FIN-D-019). R27 reports revenue by counter *and
   operator* from this table, which the six-column key cannot answer — and worse, a connector
   grouping by operator would emit colliding rows, so the loader would lose revenue or
   overwrite it. Whatever a connector groups by must be a subset of the key.
3. **Cancellation is three states, not two.** `cancelled_amount = 150.00` is measured and
   deducted, `0` is measured and none, `NULL` is not recorded. `net_amount` is computed by the
   database (FIN-D-020) and is NULL in the third case, because reporting gross as net would
   assert that nothing was cancelled.

**FIN-050 — the landing table.** `V113__finance_revenue_staging.sql` creates
`fin_stg_revenue`, where everything a connector extracts arrives before it is trusted.

Its grain is **one row per record a connector delivered within one sync batch** — one
`RawRow`, stored exactly as `extract()` produced it, never merged, split or reinterpreted
(FIN-D-021). Several staged rows may contribute to one canonical daily fact; that collapse
belongs to normalization, where it is visible and testable, rather than to extraction, where
it would destroy the trace from a published figure back to what the source actually said.

The payload stays loose — `raw_json`, connector field names, values as strings — because real
sources contain impossible dates, nulls where the schema promises otherwise and text in
numeric columns. Typed staging columns would turn each of those into an extraction failure and
lose a batch of forty thousand good rows over one bad one. There is deliberately no amount
column here at all; exact decimals are the canonical table's job.

Idempotency is `uk_fsr_batch_record (sync_batch_id, source_record_ref)`: a replay stages the
same records again under a *new* batch, which is how a restatement gets investigated, while
the same record twice inside one batch is refused. This needs no NULL workaround, unlike
FIN-D-018 — `RawRow.sourceRecordRef` is mandatory by contract, so every key column is
`NOT NULL` and the plain constraint means what it says.

**FIN-053 — validation, the first stage that decides something.** `RevenueStagingValidator`
reads a batch's `RECEIVED` rows, judges each, and records the judgement: `VALID`, or `REJECTED`
with a reason and one coded `fin_sync_error` at stage `VALIDATE`.

Six rules, and the test for whether a rule belongs here is whether it can be stated without
knowing any source system (FIN-D-022): a blank locator, provenance disagreeing with the batch,
and four payload-shape rules. **Dates and amounts are deliberately not validated** — they sit
inside `raw_json` under the connector's own field names, so checking them would mean teaching
this stage one temple's vocabulary. FIN-055 reads them against the source-of-truth declaration
instead. A rule that cannot be stated generically is deferred, not approximated.

Three properties are load-bearing and each was verified by removing it:

1. **A rejection cannot be lost.** The status change and the error row commit in one
   transaction per row, so "rejected but not recorded" and "recorded but still `RECEIVED`" are
   both impossible (FIN-D-024).
2. **`rows_rejected` is derived from the error rows**, never incremented (FIN-D-023), so a
   re-run or a second worker cannot inflate it and every unit it counts is a row an operator
   can open.
3. **The status transition is a conditional claim**, which is what makes `REJECTED` and
   `LOADED` terminal and what makes two concurrent validators safe — tested with two threads
   over 40 rows.

**The validator shipped with a defect, and this section previously denied it.** The chunked
read asked repeatedly for the *first page* of `RECEIVED` rows, so it ended only if every row it
read left that state. A row that did not — the ordinary result of losing a claim race to a
second validator — was handed back for ever, and `validateBatch` could not return. It is now
read through an advancing id cursor, so each row is offered once and the query is guaranteed to
run out (FIN-D-026).

The failure is worth naming precisely, because none of the safeguards above catch it: no
exception, no failed batch, no `fin_sync_error` — just a worker holding a database connection
while the batch stays `RUNNING`, `rows_rejected` is never written, and the dashboard shows
figures that are only old. Everything FIN-053 does to make a lost judgement impossible is
bypassed by a run that never reaches its own final statement.

Two earlier claims in this file were wrong and are withdrawn: that the loop "stops when a pass
claims nothing" (no such guard existed in any source file), and that five validator mutations
had been recorded (one had been measured; the harness was reporting a stale report as a result
— FIN-D-027).

The fix also removed a quadratic re-scan: the class runs in **74.8 s** where it previously took
**221.4 s**.

**FIN-054 — mapping, the stage that says what a value means.** `V114` adds
`fin_mapping_rule.priority` and creates `fin_stg_revenue_mapping`; `MappingRuleResolver`
decides, `RevenueMappingStage` runs it over a batch's `VALID` rows.

ADR-004 draws the line this stage sits on: *which rows and which columns* is connector code,
*what a value means* is configuration. Everything in FIN-054 is the second kind. It names no
source table and no source column, and a purity scan fails the build if one appears.

**How a shared stage reads a temple's payload without knowing its schema.** A rule's
`source_value` is namespaced — `SANNIDHI:DS`, `SEVA_CODE:430` — and the namespace **is** the
name of the staged field the rule reads (FIN-D-028). So the fields consulted are derived from
the rules themselves: a rule in a new namespace starts a new field being read, with no
deployment and with nothing in the code learning a temple's vocabulary.

**Two gaps in the existing design had to be closed first.**

1. **Precedence existed only in prose.** FIN-D-015 decided that the more specific rule wins and
   put the consequence on the connector — "the connector must apply the precedence rule" — which
   buries a business classification decision in per-temple code and leaves a generic engine
   unable to tell which of two matching rules is more specific. `priority` makes it
   configuration (FIN-D-029). It is not academic: without `SEVA_CODE:430` outranking
   `SANNIDHI:KN`, the first source's donation-box collections — 13 records averaging over a
   crore each — are classified as ordinary donations and dominate any ranking of purchased
   services.
2. **Nothing said where a rule's value is found.** Closed by the namespace convention above,
   with no new column and no change to the connector contract.

**Five outcomes, and only two of them produce a value** (FIN-D-031). `MAPPED` and `UNMAPPED`
set a canonical value; `AMBIGUOUS`, `NOT_APPLICABLE` and `INVALID_CONFIGURATION` leave it NULL,
so FIN-056 has nothing it could load them as. The distinction that earns its keep is
`UNMAPPED` versus `NOT_APPLICABLE`: an unknown value needs a mapping rule, whereas a record
carrying no category field at all usually means extraction stopped supplying one. Collapsing
them would hide a broken connector behind a configuration gap.

**Ambiguity is never resolved by picking one.** Two rules matching at the same priority is a
contradiction in configuration, and the only available tiebreakers — rule id, or whatever order
the database returns — would make a temple's published revenue depend on an implementation
detail. Nothing is decided and the competing rules are named.

**Staging is not touched.** Decisions go to a separate table keyed `(stg_revenue_id,
mapping_type)`, which is also the idempotency key (FIN-D-030). Correcting a rule and re-running
replaces the decision rather than adding one — the reason staging is retained at all — and no
count taken from that table can be doubled by a retry.

**A counter defect this exposed in FIN-053.** `rows_rejected` was derived from *all* of a
batch's errors. Mapping records against the same batch at a different grain — one error per
distinct unresolved value, not per row — so the unscoped count would have folded mapping's
summaries into a rejected-row figure matching no set of rows. Now scoped to `VALIDATE`
(FIN-D-032).

**Scope: `REVENUE_CATEGORY` only.** It is the only mapping type with both seeded rules and a
seeded canonical target. `SERVICE` needs 164 rules and `fin_service_dim` rows that do not exist;
`PAYMENT_MODE` for the first source is inferred from field presence, which ADR-004 puts in
connector code, and has no seeded rules; `METAL_TYPE` belongs to FIN-110 and its two seeded
rules are not namespaced, so they are reported as unusable rather than silently ignored. The
resolver is generic over `MappingType`, so those arrive as configuration.

**Nothing is loaded.** No amounts, no dates, no financial year, no `fin_revenue_fact` row.
Those are FIN-055 and FIN-056.

**Tests.** `MappingRuleResolverTest` — 16, no database: precedence, order independence,
ambiguity and its stable message, unmapped versus not-applicable, null and blank values,
unknown canonical targets, unusable rules, and a check that nothing is trimmed or case-folded
into a match. `RevenueMappingStageTest` — 22 against a real MySQL 8.0 container with the real
migrations: provenance carried on the decision, staging left byte-for-byte alone, only `VALID`
rows mapped, source and temple isolation, disabled and soft-deleted rules ignored, re-running
replacing rather than duplicating, the unique constraint refusing a second decision, error
summarisation per distinct value, validation's errors untouched, termination, and two stages
running concurrently.

**FIN-055 — normalization, the stage that turns a record into a figure with a date.** The two
things every earlier stage deliberately deferred: amounts and dates. `RevenueNormalizer` decides
one record, `RevenueNormalizationStage` runs it over a batch's `VALID` rows and collapses them
onto the canonical daily grain. Nothing is written to `fin_revenue_fact` — that is FIN-056.

**It reads a payload only where a declaration says to.** This is the first stage entitled to
look at a money value, and ADR-008 is why it does not choose which one: for the first onboarded
source, three columns plausibly represent revenue and disagree by 41%, and the more granular
one — the one that looks like an improvement — is wrong. `fin_source_of_truth_decl` names the
field; the code knows only metric names. `V115` adds the matching declaration for the business
date, which nothing had declared: the amount could be read and never placed in time.

**The open question FIN-053 raised is now answered** (FIN-D-033). A declaration's `source_field`
names the *staged* field, the same vocabulary a mapping rule's namespace uses (FIN-D-028). The
alternative — recording the source column and the emitted key separately — is more informative
and lets the two drift silently, which is worse than the ambiguity it removes.

**The financial year is computed in exactly one place** (FIN-D-034). It is stored beside
`transaction_date` rather than generated from it, so a wrong computation produces facts that
disagree with their own dates while both columns look plausible. April start, canonical
`2025-26` form, tested at both boundaries including the early-April case the handoff singled
out. Not configurable: April is statutory, and a per-temple year start is a setting whose only
use is to produce wrong reports.

**Absence survives the collapse.** A field the source has not declared is NULL on every fact,
never zero (FIN-D-035) — so `cancelled_amount` NULL keeps the generated `net_amount` NULL and no
report can present gross as net. Where several records become one fact and any of them leaves a
measure null, the group's total is null rather than a partial sum (FIN-D-036): a partial sum is
indistinguishable from a complete one and understates the figure with nothing on the row to say
so.

**A dubious value is refused, never repaired** (FIN-D-039). `03/04/2025` is 3 April or 4 March
depending on a convention nobody declared, and guessing moves revenue between months and, in
early April, between financial years. `1500.505` is rejected rather than rounded into a
`DECIMAL(18,2)` column, because rounding is a silent write-down that reappears later as an
unexplainable reconciliation gap. A missing amount is a recorded failure, never zero.

**Nothing is persisted here** (FIN-D-037). The facts are returned for FIN-056 to load. A third
staging table would need its own idempotency key, cleanup and retention answer; computation that
writes nothing is idempotent for free. The ceiling is stated rather than hidden: a batch's
groups are held in memory, which is bounded by the batch and not by history — but a first
historical load is one batch, and windowing by business date is the lever when that arrives.

**Staged rows are not marked `LOADED`** (FIN-D-038). A record has not been loaded until
something loads it; marking it earlier would strand rows against a fact nobody wrote.

**Tests.** `FinancialYearTest` — 12, no database: both sides of 1 April, the calendar-year trap,
the canonical string form and a century boundary. `RevenueNormalizerTest` — 36, no database:
every refusal, exact decimal arithmetic, undeclared measures staying null, `UNRECORDED` payment
mode, and grain-key behaviour. `RevenueNormalizationStageTest` — 21 against a real MySQL 8.0
container with the real migrations: the collapse, exact summing, source isolation, staging left
untouched, stage-scoped error records, re-running, and termination past the chunk boundary.

All 69 pass against MySQL 8.0. The finance suite is 312 green; the full backend suite is 1,142
with 0 failures and the 18 pre-existing FIN-X-001 errors, unchanged.

**FIN-056 — the load, and the first figure this platform will stand behind.** `RevenueLoadStage`
takes what normalization produced and writes it to `fin_revenue_fact` through `uk_frf_grain`.
Everything before it is evidence or interpretation; this is the reporting boundary (ADR-002).

**One line carries most of the risk.** The upsert *assigns* every measure —
`gross_amount = VALUES(gross_amount)`, never `gross_amount + VALUES(gross_amount)` (FIN-D-041).
That single choice makes a retry and a restatement both safe, and an accumulating version would
satisfy `uk_frf_grain` perfectly while doubling a temple's revenue on every replay. Nothing in
the schema can catch it; only a test that checks the number can, which is what mutation L1 is
for.

**`created_at` is excluded from the update list.** A delete-then-insert would have passed every
other test and failed only that one — it makes restating a two-year-old day indistinguishable
from loading it for the first time.

**Generated columns doing real work.** This is the first code path that writes through
`grain_service_key`, `grain_counter_key` and `grain_operator_key`. Because NULL is distinct from
NULL in a unique index, a fact with no counter and no operator — the ordinary case for the first
source — would otherwise insert twice and report the day twice (FIN-D-018). `net_amount` is
likewise computed by the database, so no loader can disagree with it, and stays NULL where
cancellations are unrecorded.

**A partial load keeps what it wrote and still fails the batch** (FIN-D-042). Per-fact
transactions mean a retry does not redo successful work; `LoadFailedException` and errors at
stage `LOAD` mean no batch reports success having written half its facts.

**What it cannot do, and says so.** If a source *deletes* records, the earlier fact stands: an
incremental batch's window is a modification window, not a business-date range, so "this day
should now be empty" cannot be inferred from it (FIN-D-044). Catching that needs a full range
reload or reconciliation against source totals (FIN-060). A test asserts the current behaviour so
the gap is visible rather than discovered.

**Tests.** `RevenueLoadStageTest` — 18 against a real MySQL 8.0 container with the real
migrations. Most of them are about not counting something twice: idempotent reload, restatement
replacing rather than adding, `created_at` preserved, NULL grain columns not duplicating, the
stale-fact case, per-temple isolation, financial-year totals, the failure path and its partial
progress, and termination past the chunk boundary.

**FIN-057 — the pipeline becomes a pipeline, and grows its missing first step.**
`FinancePipelineOrchestrator` runs one batch from claim to terminal status. Five stages existed
and none of them had a caller; this is the caller.

**Composition was small because the stages were already right.** Each takes a batch id and
returns a result; none takes a payload from another. The database carries the data between
stages, so the orchestrator passes an id and reads a result. No stage implementation was
rewritten to fit the abstraction (FIN-D-047).

**What the investigation actually found.** Extraction did not exist. Limitation 11 attributed it
to FIN-043, but FIN-043 is scoped as the *Kollur* connector's `extract()` — its live receipt
table and six financial-year archives. Taking a `Stream<RawRow>` from any connector and landing
it in staging is generic, and a search of production code for a write against `fin_stg_revenue`
found only tests. The pipeline had no first step at all. `RevenueExtractionStage` is that step,
written as production code rather than faked in the harness (FIN-D-045).

**It stores; it does not interpret.** Values go to `raw_json` as delivered. No date parsed, no
amount read, no field name understood — those happen after a declaration says what a field means.
The class names no source table and no source column.

**A doomed chunk costs one row, not five hundred** (FIN-D-046). Chunks of 500, each in its own
transaction, and a chunk refused by `uk_fsr_batch_record` is retried row by row from the
`RawRow`s — *rebuilding* each entity, because the failed `saveAll` already assigned generated ids
that the rollback did not reclaim, and re-saving those instances makes Hibernate merge against
rows that were never inserted. A test found this; without the fix a single duplicate reference
failed the whole batch with a stale-object error naming nothing useful.

**Claiming, not checking.** `claimForRun` is `UPDATE ... WHERE id = ? AND status = PENDING`. Two
runners racing cannot both win, and the loser is refused *by name* — a caller that asked for a
run and got silence cannot tell "already done" from "did nothing". No distributed lock, scheduler
or queue was added; the conditional claim is what the current architecture needs, and a
two-thread test proves exactly one winner.

**No batch is left `RUNNING`.** `run()` is not `@Transactional`; claim, finish and
failure-recording each take a fresh transaction. A stage that throws leaves the batch `FAILED`
with the failing stage in `fin_sync_error`, and the exception is rethrown rather than swallowed.
Writing that status inside the stage's own transaction would roll it back with everything else
and strand the batch (FIN-D-048).

**The watermark is deliberately not advanced** (FIN-D-049). `watermark_after` is a per-connector
fact that belongs to FIN-043's extract; writing anything here to make the column non-null would
mean a later run skipping a window this one only partly processed. A test asserts it stays null,
so the boundary is visible rather than discovered.

**Tests.** `FinancePipelineOrchestratorTest` — 13 against a real MySQL 8.0 container, wiring the
**real** extraction, validation, mapping, normalization and load stages behind a synthetic
in-test connector. Nothing is mocked to make the pipeline appear to run: the assertions are on
staged rows, decisions, canonical facts and batch counters that the stages themselves produced.
Covered: a full run in stage order; counter ownership; the watermark staying null; a duplicate
reference refused while the rest of the batch lands; an unknown batch; a non-`PENDING` batch in
all four states; two threads racing; a finished batch refusing a re-run; an extract failure and
the absence of any downstream effect after it; a load failure; a missing source system; and an
unregistered connector bean.

**Remaining.** No real data still — the synthetic connector is a test fixture. FIN-043's Kollur
connector, blocked on Q4, is what puts a real row into staging. Reconciliation (FIN-060) is
unblocked.

**Decisions.** FIN-D-045 … FIN-D-049.

**FIN-060 — the platform starts checking itself, and says what it cannot check.**
`RevenueReconciliationStage` runs four checks over a finished batch and records every answer in
`fin_reconciliation_result` — a table that had existed since V110 with nothing ever writing to it.

**Two of the four checks are worth much less than the other two, and the table now says which is
which.** `STAGE_COMPLETENESS` and `REJECTION_ACCOUNTING` compare this platform's counts against
each other: authoritative about processing, silent about extraction. A batch that read half a
source and processed that half perfectly passes both. `SOURCE_VS_CENTRAL` is the only check whose
two sides are independent, and it is the only one that could catch a wrong extraction query —
which is exactly why `check_type` had to exist (FIN-D-050). Without it, a screen of green rows
would read as agreement with a temple nobody has asked.

**No production connector implements `sourceTotals()`**, so against every real source today those
two checks record `NOT_AVAILABLE` with a reason. That is not a pass, and the distinction is the
point of recording it.

**Deletion is suspected, never concluded, and never acted on** (FIN-D-052). A closed period that
shrank at the source produces a `FAILED` row naming the five other explanations — partial
response, network failure, source filter error, source correction, over-count here — and changes
nothing. An open period produces `NOT_AVAILABLE`, because while records may still arrive a
shortfall is not evidence in either direction. The most important test in the class asserts the
canonical facts are unchanged field by field after a suspicion fires.

**A matching total is not a matching set of records.** `RECORD_COUNT` is compared alongside
`GROSS_AMOUNT`, and a test proves a count mismatch fails while the money agrees. The canonical
side is `SUM(transaction_count)`, not `COUNT(*)` — a fact is a daily grain that can stand for
thousands of receipts — and it is `NOT_AVAILABLE` if any contributing fact has a null count,
because a floor compared against a source count manufactures a shortfall indistinguishable from a
deletion (FIN-D-053).

**Money is compared with `compareTo`, never `equals`.** `100.00` and `100.0` are the same amount
and different `BigDecimal`s; a reconciler using `equals` would report a variance whose difference
column read zero. Scale 2 for amounts, 4 for percentages, `HALF_UP`, tolerance exactly zero.

**Idempotent by constraint, and append-only where that is what history needs** (FIN-D-051).
`uk_frr_batch_check` makes a batch replace its own answers; a null `sync_batch_id` never matches
in a MySQL unique index, so a scheduled period re-verification appends instead. One constraint,
two behaviours — the same NULL semantics FIN-D-018 had to defeat, used deliberately here.

**`RECONCILE_FAILED` is not a worse `FAILED`** (FIN-D-054). The rows loaded correctly and are
inspectable; what is in doubt is whether they are the source's. Sending such a batch down the
retry path would re-extract and reach the same disagreement forever.

**It reads widely and writes one table.** Nothing here touches `fin_revenue_fact`,
`fin_stg_revenue` or the batch counters. A reconciler able to repair what it found could make its
own checks pass (FIN-D-055).

**Tests.** `RevenueReconciliationStageTest` — 26 against a real MySQL 8.0 container with the real
migrations and the real pipeline stages, behind a synthetic connector whose totals a test
controls. Roughly half are negative: mismatches reported, suspicions recorded, failed batches
refused, and nothing repaired. All 26 pass. The finance regression is 370 green; the full backend
suite is 1,200 with 0 failures and the 18 pre-existing FIN-X-001 errors, unchanged.

**Mutations.** Eight, all KILLED, each with a report the run itself produced (FIN-D-027). They
remove the record-count comparison, the amount comparison, deletion detection, the completeness
check, the shared comparator's verdict, the source scope filter and the idempotency constraint --
and, in the one mutation that adds code rather than removing it, make a suspected deletion
destructive. That last is the only way to test a guarantee whose implementation is the absence of
a call.

**The regression it caused, and fixed.** Making `check_type` non-null broke two pre-existing
`FinanceFoundationRepositoryTest` tests that built a result without one -- on **H2**, which is the
only engine in the finance suite that could have caught it, since every result the MySQL tests
write has a check type. Both were given `SOURCE_VS_CENTRAL`, the value they were always recording,
with no assertion changed. The constraint now has a test of its own; before it, a NOT NULL column
that FIN-D-050 depends on was enforced by the database and asserted by nothing.

**What it found that was not planned.** A row rejected at normalization keeps its `VALID` staging
status, so counting only `LOADED` and `REJECTED` would have reported every unparseable amount as
an unexplained loss. And `uk_frf_grain` did not include `source_system_id`, so two sources
writing the same day and category overwrote each other — deliberate under ADR-003, undocumented
until FIN-060 surfaced it. **Closed by FIN-052A** (V118, FIN-D-067), which was cheaper than the
plan expected: widening a UNIQUE key cannot be violated by existing rows.

**Remaining.** The check that matters most cannot run until a connector answers `sourceTotals()`, and **TiDB has never run any of these migrations** (limitation 9).
Acting on a disagreement is FIN-061's.

**Decisions.** FIN-D-050 … FIN-D-055.

**FIN-061 — the platform decides what its own checks mean.** `ReconciliationGate` answers one
question: may this temple's figures for this financial year be published? Until now a `FAILED`
reconciliation and a clean one led to the same outcome, because nothing read either.

**The scope needed resolving before any code.** The plan said "a FAILED result blocks aggregate
publication" — and there are no aggregates, and no APIs. Since FIN-072 depends on FIN-061 rather
than the reverse, the rule is built first and its consumers are written against it. So FIN-061 is
**the decision, not an enforcement point**, and the missing caller is recorded as a limitation
rather than implied.

**Four outcomes, none of them invented.** `PASSED` and `NOT_AVAILABLE` publish; `FAILED` and
`PENDING` do not. They are the four values the API contract already documents for the
`reconciliation` field, so `PENDING` joined `ReconciliationStatus` rather than starting a parallel
enum (FIN-D-058). `NOT_AVAILABLE` publishes because no connector implements `sourceTotals()` yet —
blocking on it would publish nothing, ever, while withholding figures that loaded correctly. What
is withheld is the claim of verification, which the status carries.

**`PENDING` is the case worth building the gate for.** A batch that loads facts and then dies
before reconciliation leaves figures nothing has verified, and a gate that only asks "did anything
fail?" waves them straight through. Absence of a result is not absence of a problem.

**A period is not self-certifying** (FIN-D-059). It publishes only if its own checks pass *and*
every batch that fed it passed its batch-scoped checks. A batch that lost rows taints every period
it contributed to, however well that period's totals agree — they are summed from the rows that
arrived, so they always agree with themselves.

**Derived, never stored** (FIN-D-057). No decision table, no status column, no migration. The
verdict is computed from FIN-060's evidence each time, which makes it idempotent by construction
and impossible to leave contradicting the evidence. Two callers asking at once cannot conflict
because neither writes anything; no lock was needed.

**No override** (FIN-D-060). Nothing documents one; the pipeline has no authenticated caller,
because Phase 8 does not exist and RBAC is enforced at HTTP boundaries; and nothing is blocked yet
that would need releasing. An override added now would be reachable only from code with no
principal attached — an unauthenticated bypass of a financial control.

**Blocking withholds a replacement; it destroys nothing.** The gate holds no repository that could
delete or restate a fact, and a test asserts the canonical figures are untouched after a suspected
deletion blocks a period. FIN-060's non-destructive guarantee survives intact.

**Tests.** `ReconciliationGateTest` — 16 against MySQL 8.0 with the real migrations. Eleven are
about refusing to publish, or about refusing to refuse for the wrong reason: another source's
failure and another year's failure must both leave this period alone, and a corrected batch must be
able to clear an earlier variance.

**Remaining.** The gate has no caller — FIN-070/071 and the Phase 8 APIs are its consumers — and it
keeps no history of its own decisions, only of the evidence.

**Decisions.** FIN-D-057 … FIN-D-061.

**Remaining.** None in this phase. Nothing writes to staging yet, so the whole pipeline runs
today only against rows a test or an operator puts there, and no row of real temple financial
data exists anywhere. The extraction that would fill staging is FIN-043, blocked on Q4.

**Blockers.** None. With both ends of the pipeline built, the remaining stages can be
developed and tested against synthetic staging rows — no connector, no credential, and no
answer to Q4 required.

**Tests.** `FinanceCanonicalRevenueMigrationTest` — 19 tests against a real MySQL 8.0
container with real Flyway, asserting database behaviour rather than DDL text: grain rejection
including the all-NULL case, six distinct dimensions coexisting on one date, upsert
convergence, multi-temple isolation, business date surviving restatement, the three
cancellation states, mandatory provenance, exact decimal round-trips, and two purity scans.
One further test compares every `@Column` on the three entities against
`information_schema` — the defect class behind FIN-X-001, which this project otherwise has no
working check for.

`FinanceRevenueStagingMigrationTest` — 20 tests, same harness: mandatory provenance, the
duplicate-within-a-batch refusal and the replay-under-a-new-batch allowance, multi-temple and
multi-source isolation, tracing one record across batches, the four documented states with
their default, a rejected row keeping both reason and payload, business date separate from
extraction time, an undeclared date staying NULL, an impossible source value surviving
verbatim, no typed money column, two purity scans, the index set, and a regression check that
V112's canonical grain still holds after V113.

`RevenueStagingValidatorTest` — 25 tests, `@DataJpaTest` against the same real database with
the real migrations, test methods non-transactional so that what is asserted is what committed:
the happy path and each rejection rule, aggregation of several failures into one error, the
error's batch/record/stage and its deliberately null payload copy, the derived batch counter,
terminal `REJECTED` and `LOADED`, idempotent re-runs, temple and source isolation, two threads
over one batch, and a source scan for source vocabulary, transport and credentials.

Two of the 25 cover termination, and they are the reason the defect above is now closed rather
than merely described. `should_terminate_when_noRowCanBeClaimed` hands the validator a
repository whose rows never leave `RECEIVED`; `should_processClaimableRows_when_othersCannotBeClaimed`
is the half-way case a careless fix gets wrong — two claimable rows and two contended ones,
interleaved, where every claimable row must still be judged and the run must still end.

Constraints in this phase were verified by removing them: `uk_frf_grain`'s generated columns
(4 tests fail) and `uk_fsr_batch_record` (2). The five validator mutations are recorded with
their exact outcomes — including which were previously reported without having run — in
[HANDOFF.md](HANDOFF.md), under a harness rebuilt to prove each report is fresh (FIN-D-027).

**Decisions.** FIN-D-018 … FIN-D-027.


---


---

## FIN-052A — Canonical grain correction · COMPLETE

**Migration `V118__finance_fact_grain_source_system.sql`.** `uk_frf_grain` went from seven columns
to eight; `source_system_id` sits second, after `temple_id`.

Before V118, two source systems reporting the same temple, business date, service, category,
payment mode, counter and operator shared one grain, and the loader's `ON DUPLICATE KEY UPDATE`
**replaced** the first source's figures with the second's — taking `source_system_id` and
`sync_batch_id` across with them. Nothing failed, nothing warned, and nothing recorded that the
first figure had existed. It was found while writing the FIN-060 scope test, whose two-source case
had to be built around separate dates to work around it (limitation 47).

**One `ALTER TABLE`, no data touched.** Widening a UNIQUE key cannot be violated by existing rows,
and `source_system_id` was already `NOT NULL` and populated, so there was nothing to backfill and
nothing to restate. No fact was deleted or rewritten.

**Two small production changes.** `source_system_id = VALUES(source_system_id)` left the upsert's
update list — a matched row now necessarily holds that value already, and that assignment was
exactly how one source used to take ownership of another's money.
`RevenueNormalizer.GrainKey` was left alone: normalization runs over one batch, and a batch has one
temple and one source system, so both are constants of every key it builds, which is why a six-field
in-memory key still agrees with an eight-column constraint.

**Verified on MySQL 8.0.** `FinanceCanonicalRevenueMigrationTest` 24 (5 new, one of which reads the
index definition back out of `information_schema` and one of which checks no column was relaxed),
`RevenueLoadStageTest` 20 (2 new, through the real loader), `RevenueReconciliationStageTest` 26 —
its two-source test moved back onto one shared day, which the old seven-column grain could not have kept apart. Finance regression
**437 run, 0 failures, 0 errors, 0 skipped**. **TiDB is not verified**, as for every migration here.

**What it does not do.** It recovers nothing already overwritten. It does not make the platform
multi-source-capable — `uk_ftc_temple_capability` and `uk_fsd_temple_service` still assume one
source per temple, deferred under FIN-070B D9 (limitations 67, 68). And ADR-003 still describes the
old grain: amending an ADR is a governance act, so it was explained rather than performed.

**Decision.** FIN-D-067.


---

## Phase 7 — Aggregations · IN_PROGRESS

**FIN-070 — Aggregation foundation · COMPLETE, verified on MySQL 8.0.** `fin_agg_revenue_period`
(V119) at the grain FIN-070B decided, a pure `RevenueAggregator` that needs no database, and a
`RevenueAggregationWriter` that refuses to write without a publishable `ReconciliationGate.Decision`
— the gate's first consumer, which closes the *shape* of limitation 52 although nothing schedules a
run yet.

**82 tests, 0 failures, 0 errors, 0 skipped**: 66 pure, and 16 in `RevenueAggregatePersistenceTest`
against real MySQL 8.0 containers with the real migrations. V119 applies cleanly. All five required
mutations were applied and killed, including the two needing a database — an accumulating upsert,
and a `uk_farp_grain` missing `source_system_id`.

Verified against the server rather than a mock: the unique key's columns and order, every grain
column `NOT NULL`, the generated `net_amount`, `DECIMAL` precision across 10,000 facts, the
replace-don't-append upsert, source and temple isolation, and that a run leaves canonical facts
untouched.

**Not verified: TiDB**, as for every migration here. **Not measured: any performance figure.**

**FIN-071 is BLOCKED, not merely unstarted.** No canonical fact has ever carried a
`service_id`: `RevenueNormalizer`’s single row-construction site passes a literal `null`,
`fin_service_dim` has no repository and no seed rows, and nothing resolves a source service code to
a dimension id. `fin_agg_revenue_service` would be a table that cannot receive a row, so it is not
built. It needs a service-resolution step first — of roughly FIN-054’s size, and not yet a task.
Decisions FIN-D-068 and FIN-D-069; limitation 73.

**FIN-072 is COMPLETE for period aggregates** (FIN-D-070). `RevenueAggregationRebuilder` runs as
a sixth orchestrator stage after reconciliation and republishes exactly the financial years a
batch’s facts landed in, each gated independently — so a batch can publish two years and withhold
a third. Months need no scope of their own, because the aggregator emits them beside the year from
the same facts. 10 unit tests, 4 new database tests, 3 mutations applied and killed. It is not a
correction mechanism and writes no canonical fact. Its service-aggregate scope waits on FIN-071.

**These aggregates now have a reader.** `fin_agg_revenue_period` is exposed by FIN-081/082/083
(Phase 8), and rendered by FIN-091 (Phase 9) — see below.

## Phase 8 — Finance APIs · COMPLETE except `revenue/sevas` · 90%

**FIN-080/081/082/083/084 — Finance reporting API · COMPLETE, except `revenue/sevas`.**
`DcFinanceController` under `/api/v1/dc/temples/{templeId}/finance` is the first endpoint to read
`fin_agg_revenue_period` — the table FIN-070/072 write to, which nothing had read until now
(closing the second half of limitation 71). Five endpoints: `capabilities`, `summary`,
`revenue/trend`, `revenue/monthly`, `revenue/categories`, plus `reconciliation`. `revenue/sevas`,
`cancellations` and `sync-status` are not built — the first is blocked on FIN-071 (FIN-D-069), the
other two are scoped for a later pass, not silently dropped from the contract.

**What it establishes:**

- **A pure reporting-layer rollup** (`RevenueMetricRollup`) that sums several `fin_agg_revenue_period`
  rows into one figure, re-deriving none of `RevenueAggregator`'s null-propagation rules at each
  call site. A sum is `PARTIALLY_AVAILABLE` the moment any contributing row's own gross was
  unknown, or recorded facts with unknown gross — never silently a total.
- **The capability declaration gates the read**, not the presence of aggregate rows: a temple whose
  `REVENUE` capability is `NOT_AVAILABLE` gets that reason without a query touching
  `fin_agg_revenue_period` at all. A capability that is `AVAILABLE` with nothing yet aggregated for
  the requested year is a *different* `NOT_AVAILABLE` reason, so the two absences are never
  conflated (ADR-007).
- **The route guard is `CAN_READ_FINANCE_CONFIG`, not `IS_DC_ROLE`** as API_CONTRACT.md's one line
  says — a discrepancy identified and resolved in favour of FIN-070A's explicit isolation test
  requirement, which needs `AUDITOR` admitted (FIN-D-071).
- **District scope reuses `MappingAdminServiceImpl`'s exact pattern**: only `DISTRICT_COLLECTOR`
  and `DC_STAFF` are checked against `JurisdictionGuard`, because that guard's own null-check would
  otherwise treat an `AUDITOR`'s legitimately absent `districtId` as a corrupted token.
- **Two independent freshness dates** (API_CONTRACT §3): when the platform last synced
  (`fin_sync_batch.finished_at`) and separately the newest business date any fact records
  (`fin_revenue_fact`, never a source query) — never merged into one.

**What it deliberately does not do.** No field-level DACVM masking is wired — API_CONTRACT §0
names it, but nothing these endpoints return is PII (no devotee names, no receipt numbers; §6's
exclusions), so there is nothing yet for it to mask. Expenditure is `NOT_AVAILABLE` for every
temple unconditionally: no expense pipeline or aggregate table exists anywhere on the platform
(FIN-100+), and the method that resolves it is exactly where a real read goes once one does.

**Verified:** `RevenueMetricRollupTest` (14, no database), `FinanceReportServiceImplTest` (20,
MySQL 8.0 — including the AUDITOR-statewide and DC-district-mismatch cases FIN-070A's matrix
requires), `DcFinanceControllerTest` (4, HTTP status/JSON contract only; authorization is proven at
the service layer, following `MappingAdminSecurityTest` / `FinanceMappingControllerTest`'s own
documented division of labour).

**FIN-054A-BE — Source mapping administration · COMPLETE.** The finance pipeline's first HTTP
surface, and the first place finance authorization exists at all. Nine endpoints under
`/api/v1/finance` (see [API_CONTRACT.md §7](API_CONTRACT.md)) let an authorized administrator
browse a source system's mapping rules, see what the newest batch could not classify, and create
or correct a rule.

**What it establishes beyond the endpoints themselves:**

- **Authorization on the service implementation**, not only the controller — `CAN_READ_FINANCE_CONFIG`
  for reads, `CAN_ACT_DC` for writes. `TEMPLE_AUTHORITY` is excluded from both: a mapping rule
  decides which category a temple's own income is counted under.
- **Server-side scope resolution.** A caller names a `sourceSystemId`; the temple and district are
  looked up here. A client-supplied temple id is never accepted, and a source system outside the
  caller's district is a **404**, not a 403.
- **Audit in the caller's transaction** (FIN-D-063). `AuditService` was not reused — it is
  `@Async` and swallows its failures, which is right for a declaration and wrong for a change to
  how revenue is classified. A failed audit write rolls the rule change back.
- **Optimistic locking** (FIN-D-064, V117), with the stale-read check made explicitly rather than
  left to Hibernate, because the real race is two administrators minutes apart, not two commits.
- **One definition of a valid source value** (FIN-D-062). `SourceValueKey` is shared by the API
  that validates a rule and the engine that later matches it, so the API cannot accept a rule the
  pipeline would silently ignore.

**What it deliberately does not do.** It writes no canonical fact, re-maps nothing already staged,
and triggers no pipeline run. Every write response carries a fixed sentence saying that published
figures keep their classification until their batch is re-run (FIN-D-066) — because no re-run
trigger exists, and adding one on the strength of a dropdown change would be worse than leaving a
figure visibly wrong.

**FIN-054B — Source Mapper screen · COMPLETE.** Route `/finance/source-mapper`, feature module at
`frontend/src/features/finance/`, guarded for the same four roles as the backend read expression
with writes gated separately on `CAN_ACT_DC`.

What it is careful about: **no metric is computed from a page of results** — active counts come from
the server, unresolved counts are labelled with the batch they belong to, and a null batch reads as
"not measured" rather than zero (ADR-007). An observed source value is carried into a new rule
untouched, whitespace included, because matching is exact on the server. Every write reports that
existing financial records were not changed, carrying the backend's own sentence.

What it deliberately does not offer: structural source-to-staging mapping (ADR-004), SQL, a re-run
trigger, a history tab, or any control that implies the dashboard has been corrected. Asserted over
the whole request surface, not just one render.

FIN-054B also added `FinanceMappingControllerTest` (22 HTTP contract tests), closing the status-code
and JSON half of limitation 57.

**Decisions.** FIN-D-062 … FIN-D-066.

---

## Phase 9 — Dynamic Dashboard · COMPLETE

**FIN-090/091/092/093 · COMPLETE.** `FinanceDashboardTab` replaces the static
`temple-300001-dashboard.html` mockup in the DC temple profile's finance tab, reading live from
FIN-081/082/083 for every temple rather than one hard-coded id (FIN-D-072). Capabilities, the four
summary KPIs, revenue by year/month/category, and reconciliation checks are all rendered from the
API; `AvailabilityNotice` (FIN-092) renders the backend's own reason for anything not `AVAILABLE`
and never a substitute number.

**Deliberately smaller than the mockup it replaced.** DC-approved funds, ongoing works, precious-
metal value/weight charts and the special-seva list are gone — none had a real table or endpoint
behind them, and `ASSUMED_VALUE_PER_ITEM_RS`/`ASSUMED_WEIGHT_PER_ITEM_GRAMS` are deleted with
nothing replacing them (FIN-D-072). Two false caveats the mockup carried are corrected by omission:
`PRECIOUS_METAL_WEIGHT` is genuinely `AVAILABLE` for Kollur (not an assumption), and
`/revenue/monthly` is a real monthly figure (not an illustrative split of a yearly total).

**Verified:** 23 new tests (`financeReportRequests`, `financeReportErrors`, `AvailabilityNotice`,
`FinanceDashboardTab`), the full `finance` + `finance-reporting` + `dc` suite at 155 tests across
16 files, `tsc --noEmit` clean. Detail in HANDOFF.md.

---

## Remaining Phases · NOT_STARTED

See [IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md) for the task-level breakdown. Phases 8 and 9
are complete except `revenue/sevas` (blocked on FIN-071). **Phase 13 (FIN-140, multi-temple
onboarding) has started**: slice 140-A is complete — see below. What remains is the rest of Phase 10
onward — seva reports, precious metals, Nirantara, the remaining onboarding slices, incremental
sync, monitoring and hardening — plus the FIN-041 connector, still blocked on Q4.

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

The full suite reports **1,263 tests, 0 failures, 18 errors** (measured at FIN-054A-BE;
1,142 at FIN-055, 1,173 at FIN-057, 1,200 at FIN-060, 1,216 at FIN-061 — the count grows, the 18
do not). All 18 are in
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

---

## Phase 13 — Multi-Temple Onboarding · slice 140-A COMPLETE

Plan in [FIN-140_ONBOARDING_PLAN.md](FIN-140_ONBOARDING_PLAN.md). FIN-032 was analysed inside it
rather than as a separate task.

Onboarding a temple was, until this, `V111__kollur_finance_configuration.sql` — 407 lines of
conditional SQL, written by hand. There was no API, no screen and no repeatable process, and
`V111`'s own header already recorded the consequence: *"in a database where temple 300001 is created
AFTER this migration runs, the seed is a no-op and Kollur configuration must be applied through the
onboarding path (FIN-140) instead."* The first onboarded temple already depended on a path that did
not exist.

### The finding that reshaped the task

**A probe cannot be a button**, established by reading the runtime boundary rather than assuming it:

| Fact | Evidence |
|---|---|
| The registry runtime holds no bean from `com.templeregistry.connector.**` at all | `RegistryRuntimeContextTest` — a package-wide assertion, not just a missing `ConnectorRegistry` |
| The worker refuses to boot as a web application | `SyncWorkerBoundaryGuard`; `SyncWorkerProfileBoundaryTest.WebRuntimeRefusal` |
| No controller may exist in the integration packages | `FinanceIntegrationBoundaryTest` |
| The only channel between the two runtimes is the shared database | No broker dependency, no HTTP client, no worker address, no poller |
| Zero production connectors exist | `implements TempleFinanceConnector` → four hits, all in `src/test` |

So FIN-032 splits in two. **Class A** — configuration coherence — needs no connector and ships here.
**Class B** — reachability and schema drift — needs the worker, and is **deferred rather than
sequenced**: with no connector implementation in existence, every Class B check would return the
same answer for every source, and building transport for a constant is infrastructure without a
product.

### Delivered

One migration, one pure validator, one service, one controller, four DTOs, one frontend module.
**No connector package touched. No worker touched. No ADR status changed. No Kollur migration
altered.**

| Endpoint | Authorization |
|---|---|
| `POST /finance/source-systems` | `ADMIN_ONLY` |
| `GET /finance/source-systems/{id}` | `ADMIN_ONLY` |
| `PUT /finance/source-systems/{id}` | `ADMIN_ONLY` |
| `GET /finance/source-systems/{id}/readiness` | `ADMIN_ONLY` |

Contract in [API_CONTRACT.md §8](API_CONTRACT.md). Screen at `/finance/source-systems`, SUPER_ADMIN
route, `frontend/src/features/finance-onboarding/`.

**13 checks, and the two that earn the task** are `MAPPING_CANONICAL_UNKNOWN` and
`MAPPING_RULE_MALFORMED` — failures the mapping engine already detects as `INVALID_CONFIGURATION`
and as a rule that shows Active while never matching anything, per row, during a run, long after
whoever wrote the rule has gone. Detecting them here costs one set lookup and moves the discovery to
while the configuration is still a draft.

**The genericity property that matters most:** required declarations and mapping rules are demanded
only when revenue is actually reportable. A source declaring `REVENUE` as `NOT_AVAILABLE` — Temple
B's expenses-only variant — needs neither, and demanding them would have made such a source
impossible to onboard. It has its own test.

**What no endpoint does, and why it is absence rather than omission.** None activates a source
system: readiness reports `activationAllowed` and slice 140-A ends at the check. None probes: the
runtime serving these endpoints cannot reach a temple, so `connectivityVerified` is `false` on every
response with a sentence saying what was and was not checked — shown on the clean verdict most of
all, because that is where READY would otherwise be misread as connected.

### A defect the tests found and reasoning had not

`uk_fss_temple_system` is `(temple_id, system_code)` and does **not** include `is_deleted`, so a
retired source still holds its code. The first duplicate check filtered deleted rows out, passed,
and let the insert reach the constraint — surfacing as a 500 rather than a refusal a caller could
act on. The check now matches the constraint and says so when the row in the way is retired.

### Verified

| Suite | Result |
|---|---|
| `OnboardingReadinessValidatorTest` | **23 run, 0 failures** — no database |
| `SourceSystemAdminServiceTest` | **26 run, 0 failures** — MySQL 8.0 Testcontainers, full context |
| `SourceSystemAdminSecurityTest` | **14 run, 0 failures** — every assertion invokes the service as a role |
| Frontend `finance-onboarding` | **30 run, 0 failures** |
| Finance regression | **634 run, 0 failures, 0 errors, 0 skipped** |
| Worker-boundary suites | **23 run, 0 failures** — ADR-001 intact |
| Frontend regression (finance · finance-reporting · finance-onboarding · dc) | **20 files, 185 tests, 0 failures** |
| `tsc --noEmit` | clean |

TiDB not verified. Decisions FIN-D-073…078.

---

## Phase 13 — slice 140-B · Capability declarations · COMPLETE

Declaring what a source system can answer. The row this slice writes is the one ADR-007 is built
on: it is what makes an absent figure render as a reason rather than as a zero, and until now the
only way to create one was a hand-written migration.

### No new table, no new vocabulary

`fin_temple_capability` has existed since `V110` with every column this needs — availability, a
user-facing reason, a coverage window, documented gaps, a review timestamp, soft delete and audit
columns. `FinanceCapability` (19) and `DataAvailability` (4) are the canonical vocabularies and were
not duplicated: the API serves them from the enums so no client keeps a copy that drifts.

The one schema change is `V121`, adding an optimistic-lock column — which `V120`'s own comment had
already scheduled for *"the slice that writes"* this table.

### Endpoints

| Method | Path |
|---|---|
| GET | `/finance/capability-catalogue` |
| GET | `/finance/source-systems/{id}/capabilities` |
| POST | `/finance/source-systems/{id}/capabilities` |
| PUT | `/finance/source-systems/{id}/capabilities/{declarationId}` |

All `ADMIN_ONLY`. Contract in [API_CONTRACT.md §8.7](API_CONTRACT.md).

**No temple id in any path or body** — it is resolved from the source system, which makes "a
declaration cannot be created for an unrelated temple" true by construction rather than by a check.
**No delete**: withdrawing a capability is a statement, and the vocabulary has the words for it
(`NOT_AVAILABLE`, `NOT_APPLICABLE`). Deleting the row would leave a reader unable to tell either
from "nobody has looked yet".

### The constraint trap, caught this time by design

`uk_ftc_temple_capability` is `(temple_id, capability)` — it excludes `is_deleted` **and**
`source_system_id`. Slice 140-A hit exactly this shape on `uk_fss_temple_system` and returned a 500.
Here the duplicate check reads both retired rows and other sources' rows, and the refusal says which
case applies: retired and holding its slot, or held by another source system while D9 is open.

### Readiness

`NO_CAPABILITY_DECLARED` clears as soon as anything is declared. A new **warning**,
`CAPABILITY_NOT_DECLARED`, lists capabilities nobody has declared at all — because an undeclared
capability and one declared `NOT_AVAILABLE` look identical to a reader while being different
statements, and `V111` declared all nineteen for the first onboarded source precisely so *"'not
declared' never has to be guessed at"*. A warning rather than a blocker: a half-configured source is
a legitimate overnight state.

Revenue requirements stay conditional. A source declaring `REVENUE` as `NOT_AVAILABLE` is still
never asked for source-of-truth declarations or mapping rules, and the expenses-only Temple B case
has its own test. Declaring a capability activates nothing, triggers no worker job and opens no
connection — asserted directly.

### Frontend

`CapabilityMatrixPanel` on the existing Source Systems screen lists **the whole canonical
catalogue**, not only what is declared, with undeclared rows marked as such — the gap is invisible
otherwise on the one screen whose job is to close it. The capability list and the availability
vocabulary both come from the server. Declaring and revising happen in a Sheet following the Source
Mapper's form conventions, with the reason required client-side exactly where the server requires
it. Still no activate control and still no test-connection control.

### Verified

| Suite | Result |
|---|---|
| `OnboardingReadinessValidatorTest` | **27 run, 0 failures** (no database) — 23 before, 4 new |
| `SourceSystemAdminServiceTest` | **43 run, 0 failures** (MySQL 8.0 Testcontainers) — 26 before, 17 new |
| `SourceSystemAdminSecurityTest` | **25 run, 0 failures** — 14 before, 11 new |
| Frontend `finance-onboarding` | **47 run, 0 failures** — 30 before, 17 new |

Decisions FIN-D-079…082. TiDB not verified.

---

## Phase 13 — slice 140-C · Source-of-truth declarations · COMPLETE

Which field in a source carries a metric, and why that one rather than the others considered
(ADR-008). Until now the only way to write one was a hand-authored migration, and both existing
declarations came from exactly that.

### No new table, no new column, no migration

`fin_source_of_truth_decl` has existed since `V110` with everything this needs — a domain
`version`, an effective window, sign-off columns, the rejected alternatives and their measured
evidence. The metric vocabulary comes from `RevenueField`, which is what normalization actually
reads, and is served through the existing catalogue endpoint so no client keeps a copy.

**No `@Version` was added, unlike V120 and V121.** The reason is FIN-D-083: those tables are
edited in place and an optimistic lock guards what happens to them. This one is append-only, and
the race it actually has — two administrators each computing "version 2" — is caught by
`uk_fsotd_source_metric_version`, which a `@Version` column would not have caught while appearing
to.

### Endpoints

| Method | Path |
|---|---|
| GET | `/finance/source-systems/{id}/source-of-truth` |
| POST | `/finance/source-systems/{id}/source-of-truth` |

Both `ADMIN_ONLY`. Contract in [API_CONTRACT.md §8.8](API_CONTRACT.md).

**No `PUT` and no `DELETE`.** Every change is a new version; the previous one is closed with an
`effective_to` and kept. A revenue fact carries the `source_of_truth_version` that produced it, so
an in-place edit would leave that stamp pointing at a row which no longer says what it said when
the figure was published.

### Concurrency, caught twice

`supersedesVersion` states which version the caller was looking at, and a mismatch is a 409 with a
sentence naming what happened — that is the real-world case of two administrators opening the
screen minutes apart. `uk_fsotd_source_metric_version` catches two transactions interleaved too
closely for that check, and the service converts the violation into a 409 rather than letting it
surface as a server error. Both writes are in one transaction, so a refused supersession leaves
nothing behind.

`effectiveTo` is derived rather than accepted, and `effectiveFrom` may not be in the future
(FIN-D-085): nothing compares `effective_from` to today, so a future-dated version would take
effect the moment it was saved while claiming otherwise.

### Readiness

`SOURCE_OF_TRUTH_MISSING` clears once a declaration **in force** names each required metric. A
superseded declaration does not satisfy it — the readiness snapshot reads the same
`effective_to IS NULL` filter normalization does, and a verdict that counted a closed row would
pass a source every row of which the pipeline would then reject. Revenue requirements stay
conditional; an expenses-only source is never asked for any of this. Declaring activates nothing,
triggers no worker job and opens no connection.

### Sign-off

The existing `approved_by` / `approved_at` columns, set by a checkbox (FIN-D-086). Readiness was
deliberately **not** changed to fault an unapproved declaration: both declarations for the first
onboarded source are unapproved on purpose, and faulting them would change a readiness verdict
this slice was told to preserve. A warning for it is recorded as a proposed decision awaiting
approval.

### Frontend

`SourceOfTruthPanel` on the existing Source Systems screen lists the whole server-supplied metric
vocabulary with required ones marked, shows the version in force and keeps superseded versions
visible behind a disclosure — they are why the table is versioned. Rejected alternatives render
key by key, so a migration-seeded row's own measurement keys survive. The drawer says it creates a
new version before the button is pressed. Still no activate control and still no test-connection
control.

### Verified

| Suite | Result |
|---|---|
| `SourceSystemAdminServiceTest` | **68 run, 0 failures** (MySQL 8.0 Testcontainers) — 43 before, 25 new |
| `SourceSystemAdminSecurityTest` | **37 run, 0 failures** — 25 before, 12 new |
| `OnboardingReadinessValidatorTest` | **27 run, 0 failures** (unchanged — no validator change was needed) |
| Frontend `finance-onboarding` | **67 run, 0 failures** — 47 before, 20 new |

Decisions FIN-D-083…086. TiDB not verified. D9 untouched.

---

## Phase 13 — slice 140-D · Activation · COMPLETE

An administrator can now grant the platform permission to contact a source system. That sentence
is the whole feature, and every word of it is chosen: **permission**, and **contact in future**.

### READY ≠ ENABLED FOR SYNC ≠ SYNC RUNNING

| State | Means | Implemented |
|---|---|---|
| `READY` | Registry-side configuration is coherent | Yes, since 140-A — computed on read |
| **ENABLED FOR SYNC** | Somebody has authorised future synchronisation | **Yes — this slice** |
| SYNC RUNNING / SYNC FAILED | A batch is executing or has failed | **No.** Connector/worker path |

Enabling a source today does **nothing observable**. There is no deployed connector, and
`findBySyncEnabledTrueAndDeletedFalse` — the only finder for the flag — has no production caller,
so nothing reads what this slice writes. That is stated in the response payload, in the audit
line, on the screen and here, rather than left for somebody to discover.

### No migration, no new state

`sync_enabled` has existed since `V110` (*"Kill switch; defaults OFF so registering a source never
starts traffic"*), and the onboarding plan's state model already assigned the concept to it. The
plan's alternative — a finance-local `onboarding_status` column — was rejected at planning time as
*"precisely the third status vocabulary `WorkflowStatus` was created to eliminate"* (FIN-D-087).

### Endpoint

| Method | Path |
|---|---|
| POST | `/finance/source-systems/{id}/activation` |

`ADMIN_ONLY`. Body `{enabled, reason}` — the **desired end state**, not a toggle, which is what
makes it idempotent. No `version`: requiring one would turn a harmless repeat into a 409. Contract
in [API_CONTRACT.md §8.9](API_CONTRACT.md).

Enabling requires zero blocking readiness findings, recomputed by the existing validator inside
the transaction. Warnings never block. **Disabling is never gated** — a switch that only turns on
is not a switch — but needs a reason, which is audited. A no-op short-circuits *before* the
readiness check, so re-confirming an enabled source whose configuration has since drifted does not
fail (FIN-D-088).

### What it does not do

Resolves no credential, builds no connector, creates no `fin_sync_batch`, schedules nothing, sends
nothing to the worker. A test asserts the batch table is unchanged; `RegistryRuntimeContextTest`
continues to assert this runtime holds no connector at all.

### Sign-off

Unsigned source-of-truth declarations in force are listed in the response `warnings` — the moment
of enabling is when that question bites. FIN-D-086's proposed readiness finding is **still not
implemented** and approval is **not** a gate, so no existing verdict moved (FIN-D-089).

### Verified

| Suite | Result |
|---|---|
| `SourceSystemAdminServiceTest` | **86 run, 0 failures** (MySQL 8.0 Testcontainers) — 68 before, 18 new |
| `SourceSystemAdminSecurityTest` | **49 run, 0 failures** — 37 before, 12 new |
| `OnboardingReadinessValidatorTest` | **27 run, 0 failures** (unchanged — no validator change) |
| Frontend `finance-onboarding` | **85 run, 0 failures** — 67 before, 18 new |

Decisions FIN-D-087…089. Kollur not activated and not touched. TiDB not verified. D9 untouched.

---

## Phase 14 — FIN-040 · Generic JDBC connector · COMPLETE

The first connector implementation. `SyncWorkerConfig` has said since FIN-016 that *"the first
connector implementation (FIN-040) arrives later and will be registered as a `@Bean` method
here"*; it now is.

### What ADR-004 actually promised

> *"A generic `JdbcTableConnector` still covers simple sources with no new code, so 'a connector
> per temple' is the exception rather than the rule."*

One class. Two temples with different schemas are two sets of worker properties and the same bean.
A structural test fails the build if a temple name, a source table name or a write statement ever
appears in the implementation package — because the moment one does, onboarding the next temple
becomes a code change again.

### Where it lives, and why not in `connector/finance`

`ConnectorContractPurityTest` forbids `java.sql`, `ResultSet` and `PreparedStatement` anywhere in
the contract package, so the contract stays equally implementable by a push agent, an API client
and a file drop. A JDBC class there would have forced that test to be weakened. The implementation
is therefore in `service/finance/sync/jdbc`, assembled only under the `sync-worker` profile
(FIN-D-090) — exactly where the contract javadoc already said implementations belong.

### Read-only by construction

There is **no method that accepts SQL**. Every statement is composed from validated identifiers and
bound parameters, so `INSERT`, `UPDATE`, `DELETE`, DDL and procedure calls are not operations the
connector can be asked to perform — a blocklist was rejected because blocklists on SQL lose
(FIN-D-091). `setReadOnly(true)` is set too, and is explicitly the weaker defence: the connector
logs when a driver declines it rather than trusting it.

Identifiers go through an allow-list, not an escape routine. `filter_predicate` is deliberately
**not** executed.

### No migration, no registry change

Connection target and table come from `trm.finance.jdbc.<system_code>.*` in the worker environment
(FIN-D-092). Nothing was added to `fin_source_system`: it holds no host, port, URL or password by
design, and giving the registry a path to a temple's connection details is what ADR-001 exists to
prevent. Credentials continue to resolve through the existing `SourceCredentialProvider` — no new
credential mechanism was invented, and Q5 is untouched.

### It starts nothing

Registering a connector makes a name resolvable. It creates no batch, schedules nothing, and
`sync_enabled` still has no consumer. Activation remains what FIN-140-D made it: a permission.

### Verified

| Suite | Result |
|---|---|
| `JdbcTableConnectorTest` (H2, synthetic schema) | **29 run, 0 failures** |
| `SqlIdentifierTest` | **28 run, 0 failures** |
| `JdbcConnectorBoundaryTest` | **11 run, 0 failures** |
| `ConnectorContractPurityTest` | **8 run, 0 failures** (unchanged, still passing) |
| Worker boundary (registry/worker context, profile, integration) | **23 run, 0 failures** |

One existing assertion was updated rather than removed: `ConnectorRegistryTest` required the
registry to be empty *"no connector implementation exists yet (FIN-040)"* — it named this slice as
the thing that would change it. It now asserts the worker registers exactly one connector, and that
an unregistered name still fails loudly, which is the guarantee it was always about.

**No temple database was contacted.** Q4 and Q5 remain unresolved; TiDB remains unverified.

---

## Phase 15 — FIN-058 · Manual worker sync trigger · COMPLETE

**The platform read its first row.**

Everything needed to synchronise a temple existed and nothing connected it. FIN-057 composed six
stages into an orchestrator, FIN-033 registered a generic JDBC connector, FIN-140-D gave an
administrator a switch to authorise synchronisation — and nothing read the switch, nothing created a
`fin_sync_batch`, and nothing called the orchestrator. A completely configured source system
synchronised nothing, for ever.

```
sync_enabled = true            sync_enabled = true
      |                              |
      X   before                     v   now
      |                        ManualSyncTrigger
nothing creates a batch              |
      |                        fin_sync_batch (PENDING, MANUAL)
      X                              |
      |                        FinancePipelineOrchestrator
orchestrator never invoked           |
                               JdbcTableConnector -> source database
```

### Manual, and deliberately only manual

No `@Scheduled` method exists anywhere in the sync worker. `SchedulingConfig` is unchanged,
`financeSyncScheduler` is given no job, and nothing polls `sync_enabled` looking for work
(FIN-D-094). The pipeline had never executed end to end; the first time it did should not also have
been unattended, at 2am, against a government temple's live finance database. A scheduler is a
later slice, and it now has a working pipeline to schedule.

**Activation still does not start anything.** FIN-140-D's endpoint is untouched, and setting
`sync_enabled = true` continues to have exactly one effect: the trigger will no longer refuse.

### Five refusals, and none of them writes a batch

| | |
|---|---|
| `trm.finance.sync.enabled` false | `WORKER_DISABLED` |
| source missing or soft-deleted | `NO_SUCH_SOURCE` |
| `sync_enabled` false | `NOT_ENABLED_FOR_SYNC` |
| readiness has a blocking finding | `READINESS_BLOCKED` |
| a batch is already PENDING or RUNNING | `ALREADY_IN_PROGRESS` |

A refusal is not an attempt, so it leaves no row in the audit spine (FIN-D-098). `FAILED` keeps
meaning one thing: the platform reached for a temple's system and something went wrong.

### Enabled is not ready, and readiness is recomputed now

Both gates are required and they answer different questions. `sync_enabled` is a person's
authorisation. Readiness is the configuration's coherence — and a *current* property, not a fact
about the day the source was switched on. A declaration superseded or a mapping rule deactivated in
the interval turns a correct configuration into one that would publish wrong figures, so the verdict
is recomputed at the moment of the run (FIN-D-097). The assembly behind it moved out of the registry
service into `OnboardingConfigurationReader`: one implementation, two runtimes, because a screen and
a gate that could disagree about whether a source is configured correctly is worse than either.

Warnings still do not block, and **FIN-D-086 is untouched** — sign-off remains a warning at
activation and is not a gate here. No readiness check was added or re-graded, so no verdict changed.

### Duplicate protection is the database's, not the JVM's

Batch creation takes `SELECT ... FOR UPDATE` on the source system row, then validates and inserts
inside that transaction (FIN-D-096). Two requests racing serialise on that lock and the loser sees
the winner's committed batch. A `synchronized` block would have been shorter and would have worked
exactly until a second worker instance was deployed — still compiling, still passing its tests, and
silently protecting nothing.

The lock is held across three reads and one insert, and **never across extraction**: the batch
transaction commits before the orchestrator is called, so no database transaction is open while a
remote system is being read.

### Verified against a synthetic source, end to end

`ManualSyncTriggerE2ETest` runs a real MySQL 8.0 registry built by the real Flyway migrations, and
reads a synthetic H2 receipts table through the real `JdbcTableConnector`, the real
`PropertiesJdbcSourceSettingsProvider`, the real `EnvironmentSourceCredentialProvider` and
`DriverManager`. Everything except the temple is production code.

```
ID | TRANSACTION_DATE | AMOUNT  | PAYMENT_MODE | SERVICE_CODE
R1 | 2026-04-01       | 1000.00 | CASH         | SVC001  ->  SEVA
R2 | 2026-04-01       |  500.00 | UPI          | SVC002  ->  UNMAPPED (no rule; routed, never dropped)
R3 | 2026-04-02       |  750.00 | CASH         | SVC001  ->  SEVA
```

3 rows extracted, 3 staged, 3 validated, 3 mapped, **3 canonical facts totalling 2250.00**,
reconciled against the source engine's own `SUM`, and published as a period aggregate for FY 2026-27.

| Suite | Result |
|---|---|
| `ManualSyncTriggerE2ETest` | **13 run, 0 failures** |

Also proven there: a failed run does not become the position the next one resumes from (FIN-D-005);
a disabled source and a blocked readiness verdict write no batch; two racing requests produce exactly
one run and one `2250.00`.

### No migration, no new status, no frontend

`fin_sync_batch` already had everything — `triggered_by = MANUAL`, the window columns, the two
watermark columns, and a status machine with a claim that is conditional on `PENDING`. Two repository
finders were added and nothing else.

### Existing tests touched, and why

Three, none weakened:

- **`ConnectorRegistryTest`** and **`SyncWorkerProfileBoundaryTest`** each assemble the whole
  `SyncWorkerConfig` against mocked repositories, and the trigger's readiness check added two to
  that list. Both got the two extra mocks — which is precisely what the second one's own comment
  anticipates: *"a pipeline stage gaining a dependency stays a change to this list rather than a
  boundary test that goes red for a reason unrelated to the boundary."* Both also gained a new
  assertion (the worker registers `manualSyncTrigger`), so the change is net stronger.
- **`SourceSystemAdminServiceTest`** asserted that the activation response says *"no connector
  implementation has been deployed"*. That sentence stopped being true when FIN-033 deployed one, and
  FIN-058 makes the rest of it stale too. The note was rewritten and the assertion moved with it —
  the test's actual subject, that enabling must not read as "running", is asserted more directly
  than before (`starts no synchronisation`, `no scheduler`).

`RegistryRuntimeContextTest` and `SyncWorkerRuntimeContextTest` gained one test each, asserting that
only the worker can execute a pipeline and that neither runtime schedules one.

### What this does not give you

The trigger has **no production caller** (FIN-D-095). It is a worker bean, and the worker is
deliberately not a web application — a startup guard fails it if it ever becomes one — so a manual
request has nowhere to arrive from yet. No REST endpoint was added to either runtime, and no
registry-to-worker channel was invented; the only channel between them remains the shared database.
The operator's route to this capability is the next task, not this one.

A worker killed between creating a batch and finishing it leaves that batch `PENDING`, and later
requests for that source are refused until somebody cancels it. There is no reaper: deciding when an
in-flight batch is abandoned rather than merely slow needs heartbeats or lease expiry, and a guessed
timeout would eventually cancel a long historical load that was working.

**No temple database was contacted. Kollur is unconnected, unactivated, and its migrations are
unchanged. Q4 and Q5 remain unresolved; TiDB remains unverified.**

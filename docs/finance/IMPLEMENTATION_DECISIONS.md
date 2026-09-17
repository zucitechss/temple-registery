# Finance Implementation Decisions

Decisions made **during implementation**. Architectural decisions made before
implementation are in [adr/](adr/) and are not repeated here.

Each entry records what was decided, why, what was rejected, and what it affects.

---

## FIN-D-001 — Config entities extend `BaseEntity`; operational entities do not

**Date:** 2026-09-16 · **Affects:** all Phase 1 entities

**Decision.** `FinSourceSystem`, `FinTempleCapability`, `FinSourceOfTruthDecl` and
`FinMappingRule` extend `BaseEntity`. `FinSyncBatch`, `FinSyncError` and
`FinReconciliationResult` are standalone with `@CreationTimestamp` / `@UpdateTimestamp`.

**Reason.** The split already exists in this codebase and encodes a real distinction.
Configuration is authored by a person, reviewed, and meaningfully soft-deletable — it
wants `created_by`, `updated_by` and `is_deleted`. A sync batch is an append-mostly
operational record whose lifecycle is its status; soft-deleting one would destroy audit
evidence. `EmailOutbox` documents exactly this reasoning for the same reason, so following
it keeps one pattern in the codebase rather than two.

**Rejected.** Everything extending `BaseEntity` — would put a soft-delete flag on the
audit spine. Nothing extending it — would lose authorship on configuration that requires
sign-off.

**Verified.** `JpaAuditConfig.auditorProvider()` falls back to user `0` when there is no
security context, so audited config entities persist correctly from headless jobs. This
was checked, not assumed; it is what makes the sync worker able to write configuration.

---

## FIN-D-002 — No credential, host or connection field on `fin_source_system`

**Date:** 2026-09-16 · **Affects:** `FinSourceSystem`, V110, FIN-020

**Decision.** The table carries `credential_ref` — an alias — and nothing else connection
related. No host, port, JDBC URL or password column exists. `source_database_name` is
documentation and is never used to build a connection.

**Reason.** ADR-001 says the registry runtime must never reach a temple database. A
comment saying so can be overlooked; a schema with nowhere to put a password cannot be
misused. This converts the constraint from a rule people must remember into a property of
the system.

**Rejected.** Storing an encrypted connection string via the existing
`AesEncryptionConverter`. It would have worked technically and reused existing code, which
made it tempting — but it would place a decryptable route to every temple database inside
the registry process, which is precisely what the constraint forbids.

**Enforcement.** `FinanceFoundationRepositoryTest.should_haveNoCredentialOrConnectionFields_when_sourceSystemInspected`
inspects the entity by reflection and fails if a field containing `password`, `host`,
`port`, `jdbcurl` or `connectionstring` is ever added.

---

## FIN-D-003 — No foreign-key constraints in V110

**Date:** 2026-09-16 · **Affects:** V110

**Decision.** Relationships are enforced by the application and supported by indexes. No
`FOREIGN KEY` clauses.

**Reason.** Consistent with `V10__add_access_control_tables.sql`, which declares none, and
appropriate to the TiDB deployment target. Finance tables also reference `temples.id`
across a module boundary that the rest of the codebase does not constrain.

**Rejected.** Full referential integrity. It is the stronger choice in a single-node
MySQL, and worth revisiting if the platform ever moves off TiDB.

**Risk accepted.** An orphaned `source_system_id` becomes possible. Mitigated by the fact
that source systems are created through a single onboarding path, not ad hoc.

---

## FIN-D-004 — `sync_enabled` defaults to `false`

**Date:** 2026-09-16 · **Affects:** `FinSourceSystem`, V110

**Decision.** Column default `0`; entity `@Builder.Default false`.

**Reason.** Registering a source system is a description of reality. Starting to contact a
government temple's production database is an operational act. Defaulting to enabled would
merge the two, so that documenting a temple would begin generating traffic to it. Turning
sync on should be a deliberate, separately auditable step.

---

## FIN-D-005 — Latest *successful* batch is a separate query from latest batch

**Date:** 2026-09-16 · **Affects:** `FinSyncBatchRepository`

**Decision.** Two finder methods:
`findFirstBySourceSystemIdAndCapabilityOrderByIdDesc` (any outcome) and
`findFirstBySourceSystemIdAndCapabilityAndStatusOrderByIdDesc` (constrained to `SUCCESS`).

**Reason.** These answer different questions and conflating them is a data-loss bug. The
watermark and reported freshness must come from the last **successful** batch. If a failed
attempt could supply the watermark, the next run would resume after a window it never
loaded, and the missing revenue would never be noticed because nothing would report an
error. Keeping them separate at the repository level means a caller has to choose
consciously.

**Tested.** `should_distinguishLatestSuccessFromLatestAttempt_when_lastBatchFailed`.

---

## FIN-D-006 — Reconciliation tolerance defaults to zero

**Date:** 2026-09-16 · **Affects:** `FinReconciliationResult`, V110

**Decision.** `tolerance_pct` defaults to `0.0000`.

**Reason.** Revenue reconciliation is a comparison of two computations of the same
receipts. They should agree exactly. A non-zero default tolerance would silently absorb
the class of error the check exists to catch — and the Kollur analysis found a 41 %
discrepancy between two candidate revenue fields, which no sane tolerance would have
caught but a habit of tolerating small gaps would have normalised.

Tolerance remains a per-row column so that a genuinely approximate metric can set one
explicitly, with the value visible in the record.

---

## Inherited Data Decisions (restated, not re-litigated)

These were established during the analysis phase and are binding on implementation. They
are repeated here because implementation code must reference them, not to reopen them.

| # | Decision | Consequence for code |
|---|---|---|
| 1 | `DailySevaNew.Amount` is the authoritative revenue amount | Declared in `fin_source_of_truth_decl`, stamped on every fact |
| 2 | `DailySevaNewOld` must **not** be read | Excluded in FIN-043; it duplicates all six FY archives |
| 3 | `DailySevaNewDetails.TotalAmount` must **not** be treated as revenue | 41 % below header total; recorded as a rejected alternative |
| 4 | Revenue coverage begins FY2019-20 | `fin_temple_capability.coverage_from` |
| 5 | Metal weight comes from `HKanikeItems.Qty` | Real grams, per item |
| 6 | The 15 g per item assumption is **wrong** | Must not appear anywhere |
| 7 | Metal valuation is not available | `PRECIOUS_METAL_VALUE` = `NOT_AVAILABLE` |
| 8 | Expenditure data does not exist | `EXPENSE` = `NOT_AVAILABLE`, never `0` |
| 9 | Grant data does not exist | `GRANT` = `NOT_AVAILABLE` |
| 10 | Nirantara execution is not recorded | `fin_nirantara_execution` created empty by design |
| 11 | Payment mode is inferred, not recorded | `payment_mode_confidence = INFERRED` |
| 12 | Missing data is `NOT_AVAILABLE`, never zero | Enforced by nullable amounts throughout |
| 13 | Receipt counts are not devotee counts | Field named `transaction_count`; UI copy must match |

---

## FIN-D-007 — Scheduling is gated centrally, not per scheduler bean

**Date:** 2026-09-16 · **Affects:** `TempleRegistryApplication`, `SchedulingConfig`

**Decision.** `@EnableScheduling` moved off the application class into
`SchedulingConfig`, annotated `@Profile("!sync-worker")`. The sync worker therefore runs no
`@Scheduled` method at all and schedules its own jobs explicitly against the
`financeSyncScheduler` `TaskScheduler`.

**Reason.** The worker is the same artifact started with a different profile, and
`@EnableScheduling` is global. Left on the application class, every existing background job
would start a second copy in the worker process. This is not hypothetical:
`EmailDeliveryService.processQueue()` runs every ten seconds and claims rows with
`findPendingBatch(50)` — a plain `SELECT ... LIMIT` with no row locking, followed by a
save. Two processes running it would both claim the same `PENDING` rows and **deliver
duplicate emails to real recipients**. `NotificationRouter.dispatchPending()` (five
seconds), `EmailRetryScheduler`, `NoticeExpiryScheduler` and `OverdueWorkflowScheduler`
have the same exposure.

**Rejected.** Annotating each scheduler bean `@Profile("!sync-worker")`. Several of those
beans also expose non-scheduled methods that other services call — notably
`EmailDeliveryService.enqueue()` — so removing the beans from the worker context would
cascade through the notification subsystem. Withholding the scheduling *infrastructure*
leaves every bean present and injectable while making its `@Scheduled` methods inert. It is
also a one-line change rather than a five-class refactor.

**Consequence.** The worker must never deliver email directly. It writes to `email_outbox`
and the registry runtime delivers — which is what the outbox pattern is for.

**Tested.** `SyncWorkerRuntimeContextTest.should_notScheduleRegistryJobs_when_syncWorkerProfileActive`
and `should_keepSchedulerBeansInjectable_when_schedulingDisabled`;
`RegistryRuntimeContextTest.should_keepSchedulingEnabled_when_registryProfileActive`.

---

## FIN-D-008 — Worker beans are registered explicitly, never component-scanned

**Date:** 2026-09-16 · **Affects:** `SyncWorkerConfig` and everything under it

**Decision.** Only `SyncWorkerConfig` carries a stereotype annotation
(`@Configuration @Profile("sync-worker")`). Every other class capable of reaching a temple
source system is a plain class registered by an explicit `@Bean` method inside it.

**Reason.** A `@Component` is visible to the application-wide scan, so its absence from the
registry runtime would depend on every future author remembering `@Profile`. A mistyped or
forgotten annotation **fails open** — the bean loads in the registry runtime, which is
exactly the outcome the boundary exists to prevent. A plain class cannot be picked up by
accident, and the worker's whole bean inventory is auditable in one file.

This is the same reasoning as FIN-D-002: prefer a structure that cannot be misused over a
rule that must be remembered.

**Rejected.** `@Component` + `@Profile` on each class (fails open on omission). Excluding
the package from the main component scan with a filter (adding `@ComponentScan` alongside
`@SpringBootApplication` replaces Boot's own filters, including `TypeExcludeFilter`, which
would affect the 94 existing test classes).

**Enforcement.** `FinanceIntegrationBoundaryTest` scans the compiled classpath and fails if
any class in `service.finance.sync` or `connector` is component-scannable under the normal
profile. Verified by mutation: adding a `@Component` to the package makes two tests fail.

---

## FIN-D-009 — Credentials resolve through an interface, from the environment, failing loudly

**Date:** 2026-09-16 · **Affects:** `SourceCredentialProvider`, Q5

**Decision.** `SourceCredentialProvider` is the seam. The interim implementation reads
`trm.finance.source.<ref>.principal` / `.secret` from the worker process environment. No
credential is committed, stored in the database, or given a fallback. A missing credential
throws `CredentialNotConfiguredException`.

**Reason.** Q5 is unresolved — there is no secrets manager — but the abstraction can be
committed now and the backing store chosen later; swapping it is one `@Bean` method. Three
properties are non-negotiable regardless of store:

- **No fallback.** A provider that substituted a default or an empty password would turn a
  configuration mistake into either a failure blamed on the temple, or a successful
  connection to something nobody intended.
- **No committed defaults.** A placeholder default in `application.yml` is precisely how
  the existing `DB_USERNAME` / `DB_PASSWORD` fallbacks reached version control. Temple
  credentials must not repeat that, so the properties are declared in no YAML at all.
- **Redacted rendering.** `SourceCredentials.toString()` hides the secret, because the usual
  way a credential reaches a log file is an exception or debug line interpolating an object
  that happens to contain one.

**Also decided.** `credentialRef` is validated against `[a-z0-9][a-z0-9_-]*[a-z0-9]`. The
value arrives from a database row, and without validation a row containing
`..spring.datasource` would let configuration read an unrelated property — including the
registry database password.

**Tested.** `EnvironmentSourceCredentialProviderTest`, 9 tests.

---

## FIN-D-010 — The sync worker is a non-web process, asserted at startup

**Date:** 2026-09-16 · **Affects:** `application-sync-worker.yml`, `SyncWorkerBoundaryGuard`

**Decision.** The worker profile sets `spring.main.web-application-type: none`, and
`SyncWorkerBoundaryGuard` fails startup if the context is a `WebApplicationContext`.
The profile also disables Flyway and sets `ddl-auto: none`.

**Reason.** If the worker served HTTP, every controller in the artifact would be reachable
from a process that can open a connection to a temple database — reassembling the coupling
ADR-001 forbids without anyone writing code that connects the two. The YAML property alone
is easy to lose to a deployment override, so the invariant is asserted in code as well.

Failing closed is deliberate: a worker that will not start is a loud, obvious problem; a
worker quietly serving HTTP while holding temple credentials is an invisible one.

Flyway and DDL are disabled because the registry runtime owns the schema. Two processes
performing DDL against one database is a race with no upside, and the worker only writes
rows.

**Tested.** `SyncWorkerProfileBoundaryTest.should_failStartup_when_syncWorkerRunsAsWebApplication`
(via `WebApplicationContextRunner`) and
`SyncWorkerRuntimeContextTest.should_startWithoutWebLayer_when_syncWorkerProfileActive`.

---

## FIN-D-011 — The contract reuses the shared finance enums rather than duplicating them

**Date:** 2026-09-16 · **Affects:** `com.templeregistry.connector.finance`

**Decision.** The connector contract imports `FinanceCapability`, `ConnectorType`,
`SourceTechnology` and `SyncType` from `com.templeregistry.entity.finance.enums`. It imports
nothing else from `com.templeregistry.entity` and no JPA entity at all.

**Reason.** These enums are the canonical vocabulary established in FIN-010, and they are
already the persisted vocabulary of `fin_temple_capability`, `fin_source_system` and
`fin_sync_batch`. Defining a parallel set inside the connector package would create two
vocabularies for one concept and a translation layer between them — and the first time they
drifted, a capability declared by a connector would stop matching the capability stored
against the temple.

**Rejected.** Duplicating the enums in the connector package (two sources of truth for the
same closed set). Moving them to a neutral package (a refactor of committed code that would
break the `entity/<domain>/enums` convention this codebase already follows in
`entity/accesscontrol/enums`).

**Why it is safe.** The package name says `entity`, but these are plain Java enums with
**zero imports and zero annotations** — no JPA, no Spring, no Hibernate. That was verified,
not assumed. The risk is that someone later adds an annotation to one and the connector
layer silently inherits a framework dependency, so
`ConnectorContractPurityTest.should_beFrameworkFree_when_sharedEnumsInspected` fails the
build if any of them ever imports `jakarta.*`, `org.springframework.*` or `org.hibernate.*`.

---

## FIN-D-012 — The framework owns the watermark; a connector cannot propose one

**Date:** 2026-09-16 · **Affects:** `TempleFinanceConnector`, `SyncContext`

**Decision.** `SyncContext` supplies `changedSince` (the watermark of the last **successful**
batch) and `changedUpTo` (chosen by the framework before the call). No contract method
accepts or returns a watermark, and extraction reports none.

**Reason.** FIN-D-005 established that a failed batch must never advance the watermark. A
connector that returned the highest change-timestamp it observed would make the opposite
easy: the natural implementation stores what the connector returned, and a batch that
extracted successfully but failed during load or reconciliation would still have moved the
mark past data it never loaded. Removing the return value removes the opportunity.

`changedUpTo` being decided in advance also makes a batch deterministic and replayable: the
same window can be re-run and must produce the same rows.

**Also decided.** `SyncContext` separates two axes that are easy to conflate:
`changedSince`/`changedUpTo` is the **change axis** (what the source modified), while
`businessDateRange` is the **business-date axis** (which transaction dates are in scope, for
a historical load or a restatement window). Filtering reconciliation by modification time
would silently exclude older records that were never touched, and the resulting total would
look correct because both sides compared the same subset.

**Tested.** `should_exposeNoWatermarkReturn_when_contractInspected`,
`should_reject_when_historicalLoadCarriesWatermark`,
`should_useDifferentAxes_when_comparingExtractAndSourceTotals`.

---

## FIN-D-013 — Extraction returns raw string values, streamed

**Date:** 2026-09-16 · **Affects:** `RawRow`, `TempleFinanceConnector.extract`

**Decision.** `RawRow` carries `Map<String, String>` and `extract` returns
`Stream<RawRow>`, not a collection.

**Reason for strings.** Staging exists so that a malformed source value **lands and is
rejected with a reason** rather than aborting a batch. Real sources contain impossible dates
(the Kollur analysis found 1955, 1969 and 2004 values), nulls where the schema promises
otherwise, and text in numeric columns. A typed extraction contract turns each of those into
a crash during extraction, losing the diagnostic record that `fin_sync_error.raw_payload_json`
is designed to keep. Money is unaffected: a decimal rendered as text and parsed back is
exact — this would not hold for binary floating point, which financial values must not use
anyway.

**Reason for streaming.** A first historical load can run to tens of millions of source
records. Nothing in the pipeline may require the whole result in memory. The stream may hold
a resource, so the contract documents that callers must close it.

**Rejected.** `Map<String, Object>` — preserves source types but invites a connector to pass
a driver-specific object (a `ResultSet` row, a vendor date type) up into staging, which is
exactly the source-vocabulary leak the boundary exists to prevent. `List<RawRow>` — simpler,
and impossible at Kollur volume.

---

## FIN-D-014 — The Kollur seed is conditional on the temple existing

**Date:** 2026-09-16 · **Affects:** `V111__kollur_finance_configuration.sql`

**Decision.** Every statement in V111 is guarded by
`WHERE EXISTS (SELECT 1 FROM temples WHERE id = 300001 AND is_deleted = 0)`. In a database
without that temple, the migration succeeds and seeds nothing.

**Reason.** No migration in this repository creates temple 300001 — `V100` seeds temples with
ids `100`... and 300001 exists only where it was created through the application. An
unconditional seed would therefore create finance configuration pointing at a temple that
does not exist in every fresh developer and CI database: a source system, nineteen capability
declarations, a source-of-truth declaration and nine mapping rules, all orphaned.

**Rejected.** Seeding unconditionally and treating the orphan rows as harmless. They are
harmless at runtime — `sync_enabled = 0` means nothing acts on them — but configuration that
describes a temple the registry has never heard of is misleading to the next person who
opens the table, and finance configuration is precisely the place where misleading data
must not accumulate.

**Rejected.** Creating temple 300001 in the migration. The finance platform does not own
temple records and must not invent one.

**Operational consequence, which must not be forgotten.** A Flyway versioned migration runs
once. In an environment where temple 300001 is created *after* V111 has run, the seed will
never apply, and Kollur configuration must be applied through the onboarding path (FIN-140)
or by re-running the statements manually. V111 is idempotent, so re-running it is safe.

**Tested.** `KollurFinanceConfigurationMigrationTest` asserts zero rows after migration and
before the temple is created, then applies the seed and asserts every row.

---

## FIN-D-015 — Mapping source values are namespaced, and the more specific rule wins

**Date:** 2026-09-16 · **Affects:** `fin_mapping_rule` seed for Kollur

**Decision.** Source values in `REVENUE_CATEGORY` rules carry a namespace prefix —
`SANNIDHI:DS`, `SEVA_CODE:430`, `STREAM:SAREE_AUCTION`. Where both could match, the more
specific (`SEVA_CODE`) takes precedence over the coarse bucket (`SANNIDHI`).

**Reason.** Two different source vocabularies map to the same canonical concept. The source
maintains a four-bucket income classification, which handles almost everything; but one
service inside the donation bucket is not a donation in the reporting sense. Donation-box
collections are booked as ordinary receipts — 13 records averaging over a crore each — and
without an override they would be reported as ordinary donations and would dominate any
ranking of services purchased by devotees.

Without prefixes, `DS` and `430` would sit in one flat key space with nothing to say which
kind of source value each was, and the precedence rule would have nowhere to live.

**Rejected.** A rule per service code (164 rows of data that the source already classifies
for us, and which would need maintaining every time the temple adds a seva). A new
`MappingType` enum value (a vocabulary change to solve a naming problem).

**Consequence.** The connector must apply the precedence rule; it is stated in the migration
comment and in the `SANNIDHI:KN` rule's own notes, where someone editing the mapping will
see it.

---

## FIN-D-016 — `connector_type` is seeded provisionally rather than made nullable

**Date:** 2026-09-16 · **Affects:** `fin_source_system` Kollur row

**Decision.** Kollur is seeded with `connector_type = 'PULL_JDBC'`, explicitly recorded as
provisional in the row's own `notes`, pending the Q4 network decision.

**Reason.** The column is `NOT NULL` and Q4 is unresolved, so something must be written. The
value reflects how the source was analysed, not a finding that the platform may reach it.
Nothing depends on it while `sync_enabled = 0`, and correcting it if the answer is
`PUSH_AGENT` is a single `UPDATE` — the connector contract (FIN-030) treats all four
mechanisms as equals precisely so that this stays a one-row change.

**Rejected.** Making `connector_type` nullable until onboarding completes. It is defensible
and is a one-line migration, but it weakens the column for every temple in order to express
uncertainty about one, and the uncertainty is better expressed where it actually is — in the
notes, and in `sync_enabled = 0`.

**Rejected.** Inventing an `UNDECIDED` enum value. That adds permanent vocabulary to model a
temporary state.

**Guard.** The seed is worthless as a signal if someone later flips the switch without the
prerequisites, so the test asserts `sync_enabled = 0` and no schedule. If a future change
enables sync, that test must be consciously changed — which is the point.

---

## FIN-D-017 — A configured connector that is not registered is a failure, not an absence

**Date:** 2026-09-16 · **Affects:** `ConnectorRegistry`, `ConnectorConfigurationException`

**Decision.** `ConnectorRegistry.resolve` returns a `TempleFinanceConnector` or throws. There
is no `Optional`, no nullable return, no default implementation and no "unconfigured" branch
anywhere in the resolution path.

**Reason.** `fin_source_system.connector_bean` is a statement of intent made by whoever
onboarded a temple; whether that code exists is decided at build time by someone else, and
the two can disagree. If resolution could express absence, the caller's easiest handling of
it — skip the source, log, continue — would produce a batch that succeeds having read
nothing. A temple would then be fully configured, visibly enabled, and reporting figures
that mean "nobody ran anything" while reading as "nothing happened". That is the exact
failure the availability model (`NOT_AVAILABLE` with a reason, never zero) exists to
prevent, and it must not be reintroduced by the plumbing underneath it.

This is not hypothetical. Kollur is completely configured today and its connector does not
exist, so the missing-connector path is the one the registry actually takes.

**Rejected.** `Optional<TempleFinanceConnector> find(...)`. It reads as the safer API and is
the opposite: it moves the decision to every caller, and the failure it invites is silent.

**Rejected.** A no-op connector for unregistered names. It makes the system startable at the
cost of making it dishonest.

**Rejected.** Discovering connectors by scanning the classpath for implementations of the
contract. It would remove the `@Bean` registration that FIN-D-008 depends on and let a
connector class drift into the registry runtime by nothing more than being on the classpath.

**Also decided.** Two smaller rules, both fail-closed:

- A connector must be registered under the same name it declares as its
  `ConnectorMetadata.connectorId()`. Configuration has one field; two names would let a
  source system name something that resolves to nothing. Violations fail at worker startup,
  not at the first sync.
- Resolution verifies that the registered connector's `connectorType()` matches the
  `connector_type` the source system declares. That column is what firewall approvals and
  the onboarding record are based on, so a source approved as a delivered extract must not
  quietly be read by a connector that reaches into the temple instead.

**Consequence.** The worker starts with an empty registry and stays healthy; the failure
surfaces when a source system is actually synchronized, naming the connector, the system
code, the source system id and the temple.

---

## FIN-D-018 — The grain is enforced through generated key columns, because NULL is not equal to NULL

**Date:** 2026-09-16 · **Affects:** `fin_revenue_fact.uk_frf_grain`

**Decision.** Three of the grain columns — `service_id`, `counter_ref`, `operator_ref` — stay
nullable, and `uk_frf_grain` is declared over stored generated columns that substitute a
concrete value for each NULL (`0`, `'~NONE~'`, `'~NONE~'`).

**Reason.** MySQL and TiDB both treat NULLs in a unique index as distinct from one another,
so the unique key documented in ADR-003, written literally, does not enforce the grain for
any fact whose service, counter or operator is unknown. That is not an edge case: a
donation-box collection has none of the three, and Kollur's hundi stream is ₹13.40 Cr a year.
Two syncs of the same day would have produced two rows and reported the money twice.

This was **verified by mutation**, not reasoned about: replacing the generated columns with
the documented key made `should_rejectDuplicate_when_nullableGrainColumnsAreNull` fail with
the duplicate silently accepted.

**Rejected.** Making the three columns `NOT NULL` with sentinel values. It enforces the grain
just as well and reads more simply, but it destroys information every consumer then has to
reconstruct: "no service was involved" and "service 0" are different statements, and a report
counting distinct services would count the sentinel.

**Rejected.** Enforcing uniqueness in the loader. The constraint exists precisely for the
case where application code is wrong; an invariant that holds only while the code is correct
is not an invariant. It also fails to protect against two workers, a manual correction, or a
replay.

**Consequence.** The generated columns are storage mechanism only. Nothing reads them —
reports read the nullable columns, where NULL keeps its meaning — and the sentinel strings
are chosen to be implausible as real counter or operator identifiers.

---

## FIN-D-019 — `operator_ref` belongs in the grain

**Date:** 2026-09-16 · **Affects:** `fin_revenue_fact.uk_frf_grain`, ADR-003

**Decision.** The canonical grain is
`(temple, transaction_date, service, category, payment_mode, counter_ref, operator_ref)` —
ADR-003's six columns plus the operator.

**Reason.** Two independent problems with the documented six:

1. **A catalogued report needs it.** R27 reports revenue *by counter and operator*, and names
   `fin_revenue_fact.operator_ref` as its source. With the operator outside the key, a day's
   takings at one counter across three operators must collapse to one row, and the operator
   column can hold only one of them or none — so the report is unanswerable from the table
   documented to answer it.
2. **It is a correctness hazard, not just a missing feature.** A connector grouping its
   extract by operator — the natural thing to do when the column exists — emits rows that
   collide on a key that omits it. The loader then either loses revenue or overwrites one
   operator's takings with another's, and both losses are silent. Whatever a connector groups
   by must be a subset of the key.

**Cost.** More rows than ADR-003's measured 121,242, which was counted at date × service ×
counter. Operators work at counters, so the multiplier is small, and the measured 184×
reduction from 22.3 M receipts is not materially affected. The figure in ADR-003 is left
as-is rather than restated, because it was measured and this estimate is not.

**Rejected.** Dropping `operator_ref` from the fact entirely and serving R27 from a separate
aggregate. That defers the same decision, and an operator column sitting unused in the fact
would eventually be populated by someone who did not know it was not in the key.

**Consequence.** ADR-003's "Consequences" section names the six-column key and is now
superseded on that one point by this decision. A temple whose source records no operator is
unaffected: the column is NULL and the generated key collapses it (FIN-D-018).

---

## FIN-D-020 — `net_amount` is computed by the database and is NULL when cancellations are unknown

**Date:** 2026-09-16 · **Affects:** `fin_revenue_fact.net_amount`

**Decision.** `net_amount` is a stored generated column, `gross_amount - cancelled_amount`.
Where `cancelled_amount` is NULL — the source does not record cancellations at all — net
revenue is NULL.

**Reason.** Every catalogued revenue report sums `net_amount`, so it is the number that
reaches a Deputy Commissioner. Computing it in the loader means each future pipeline stage,
restatement path and manual correction must reproduce the same arithmetic, and the first one
that does not produces a figure that disagrees with its own components. The database can only
have one answer.

The NULL propagation is the deliberate part. For a source that does not record cancellations,
writing `net = gross` asserts that nothing was cancelled, which is exactly the "absence
becomes zero" failure ADR-007 exists to prevent. Three states stay distinguishable:
`cancelled_amount = 150.00` (measured, deducted), `= 0` (measured, none), `= NULL` (unknown,
and net is unknown with it).

**Consequence.** A temple lacking the `CANCELLATION` capability is reported from
`gross_amount` with its capability caveat attached — never by substituting gross for net. The
aggregation layer (FIN-070) must handle this explicitly rather than summing NULLs into zero.

---

## FIN-D-021 — Staging is keyed on one source record per batch, and replay is a new batch

**Date:** 2026-09-17 · **Affects:** `fin_stg_revenue.uk_fsr_batch_record`

**Decision.** The staging grain is **one row per record a connector delivered within one sync
batch** — one `RawRow`, exactly as `extract()` produced it — and uniqueness is
`(sync_batch_id, source_record_ref)`. Staging never merges, splits or reinterprets what it
was given.

**Reason.** Three different units were candidates, and the choice decides what can be
investigated later:

- *One canonical candidate fact.* Collapsing to the daily grain at extraction would make
  staging a second copy of `fin_revenue_fact` with none of its guarantees, and would destroy
  the mapping from source records to the figure they produced — exactly the trace somebody
  needs when a temple disputes a total.
- *One source receipt.* Not available to us: connectors group at the source (ADR-003) so 22 M
  rows never cross the wire. Requiring receipt grain would undo that decision.
- *One delivered record.* What the connector actually produced, stored without
  interpretation. Several staged rows may contribute to one canonical fact; that collapse
  happens in normalization (FIN-055), where it is visible and testable.

**Uniqueness follows from the grain.** A replay stages the same source records again under a
*new* batch, which is legitimate and necessary — comparing two extractions of one window is
how a restatement is explained. What the constraint forbids is the same record twice inside
one batch, which is either a connector defect or a reference that does not identify what it
claims to. Either way it silently double-counts downstream if it lands.

This is safe only because `RawRow.sourceRecordRef` is mandatory and non-blank by contract
(FIN-030), so no staged row can be missing its key. **Note the deliberate contrast with
FIN-D-018:** in `fin_revenue_fact`, NULL-distinct index semantics had to be worked around with
generated columns; here every key column is `NOT NULL`, so the plain constraint means exactly
what it says. The same database behaviour is a hazard in one table and correct in the other,
which is why each was decided rather than copied.

**Rejected.** Uniqueness on `(source_system_id, source_record_ref)` without the batch. It
sounds stronger and is wrong: it makes re-extraction impossible, so a corrected source record
could never be staged and no restatement could ever be investigated.

**Rejected.** No constraint, with duplicates detected in validation. The duplicate would then
be a row somebody has to notice, and the first pipeline bug that skipped the check would
double a temple's revenue with nothing in the way.

**Consequence for FIN-053 and the writer.** A constraint violation while staging is a
*batch-level defect*, not a row to drop: the writer must record it against `fin_sync_error`
with stage `EXTRACT` and fail the batch, rather than catching it and continuing. A connector
whose references are not unique within a batch has a bug that must surface.

**Open.** Retention (Q7, 30–90 days proposed) is undecided, so no TTL, purge job or
partitioning exists. Staging will grow without bound until it is answered.

---

## FIN-D-022 — Validation checks what can be stated without knowing any source system

**Date:** 2026-09-17 · **Affects:** `RevenueStagingValidator`

**Decision.** FIN-053 validates six things, and deliberately not dates or amounts:

| Code | Rule |
|---|---|
| `BLANK_RECORD_REF` | the locator is blank, so the original cannot be found in the source |
| `PROVENANCE_MISMATCH` | the row's `temple_id` or `source_system_id` disagrees with its batch |
| `MISSING_PAYLOAD` | `raw_json` absent |
| `MALFORMED_PAYLOAD` | `raw_json` unparseable |
| `PAYLOAD_NOT_OBJECT` | the payload is not an object of source fields |
| `EMPTY_PAYLOAD` | the object carries no fields |
| `NON_SCALAR_FIELD` | a field holds a nested structure, which the connector contract does not deliver |

**Reason for what is absent.** Dates and amounts live inside `raw_json` under the connector's
own field names. Checking them here would mean teaching this class the field names of one
temple's source system — the exact knowledge that stops at the connector, and the reason this
platform is not a Kollur dashboard. Normalization (FIN-055) reads them against the
source-of-truth declaration, which is where the platform learns which field is authoritative
for whom. **A rule that cannot be stated generically is deferred, not approximated.**

`PROVENANCE_MISMATCH` is the one rule the database cannot enforce and the one that matters
most: a staged row whose temple disagrees with its batch would attribute one temple's money to
another, and nothing downstream would notice.

**VALID means structure only.** Not that the category is mapped, the service resolved, the
amount authoritative, the figure reconciled, or the row reportable. Those are FIN-054,
FIN-055, FIN-056 and FIN-060, and the status name must not be read as having settled them.

**Consequence.** Two rules (`MISSING_PAYLOAD`, `MALFORMED_PAYLOAD`) cannot fire while
`raw_json` is a `JSON` column, because MySQL and TiDB reject a malformed document at insert.
They remain because parsing throws a checked exception that must be handled regardless, and
because the column type is a schema decision that could change. This is recorded rather than
hidden: they are untestable through the database today.

---

## FIN-D-023 — One error per rejected row, and the batch counter is derived from them

**Date:** 2026-09-17 · **Affects:** `RevenueStagingValidator`, `fin_sync_error`, `fin_sync_batch.rows_rejected`

**Decision.** A rejected row produces exactly one `fin_sync_error` at stage `VALIDATE`. Where
several rules fail, the message names all of them and `error_code` names the first in a fixed
order. After a run, `fin_sync_batch.rows_rejected` is **set** to the batch's error count, never
incremented.

**Reason.** `rows_rejected = 143` has to mean 143 error rows an operator can open, one per
record. One error per failed *rule* would break that correspondence — a row failing three
rules would count three times — and the fixed order means one defect always produces one code,
so counting by `error_code` is meaningful rather than order-dependent.

Deriving the counter rather than incrementing it removes a whole class of bug: a re-run, an
interrupted run, or two workers cannot inflate it, because it is recomputed from rows that
exist.

**Rejected.** Incrementing per rejection. It is the obvious implementation and it drifts the
first time anything is retried, which on a finance figure means an unexplainable number in an
audit.

**Rejected.** Copying the payload into `fin_sync_error.raw_payload_json`. The staged row still
holds it, and duplicating source records into a second table would spread whatever personal
information a temple's records contain. The batch and `source_record_ref` locate the original.
**Open:** if staging retention (Q7) is ever set, this needs revisiting — purging staging would
leave errors without the evidence they explain.

---

## FIN-D-024 — Each row commits on its own, and its status transition is a claim

**Date:** 2026-09-17 · **Affects:** `RevenueStagingValidator`, `FinStgRevenueRepository.transition`

**Decision.** One transaction per staged row (`REQUIRES_NEW`), inside which the status change
and the error insert commit together. The status change is a conditional update — it matches
only a row still in the state the validator expected — and the error is written only if that
update claimed the row.

**Reason.** Three failure modes, one mechanism:

1. **A rejection recorded with no status change, or a status change with no record.** Both
   would break the guarantee that a rejected row is explainable. Committing them together
   makes the pair atomic; if the insert fails, the status rolls back and the row is validated
   again on the next run.
2. **Two validators on one batch.** Without the conditional claim, both would reject the same
   row and `rows_rejected` would exceed the rows actually rejected. Tested with two threads
   over 40 rows: each row is processed exactly once.
3. **A terminal row revived.** `REJECTED` and `LOADED` never match the expected `RECEIVED`, so
   a re-run cannot overwrite a recorded judgement.

**How much of this the tests actually prove (measured, 2026-09-17).** Removing the conditional
predicate fails exactly **one** test: `should_processEachRowOnce_when_twoValidatorsRunTogether`.
Reason 3 above is true of the method but is not what the terminal-state tests demonstrate —
the chunk query already filters on `validation_status = 'RECEIVED'`, so a `REJECTED` or
`LOADED` row is never offered to `transition` in the first place. The claim is a second line of
defence there, and the first line is the query.

Two consequences worth knowing before touching either. Reason 2 rests on a single
timing-dependent two-thread test; if it is ever disabled, quarantined as flaky, or weakened,
nothing else in the suite notices that the claim has gone. And anyone "simplifying" the query
filter should understand they would be removing the mechanism that the terminal-state tests are
really exercising.

**A persistence failure aborts the run rather than continuing.** The remaining rows stay
`RECEIVED` and are picked up by the next run. Catching and continuing would convert a database
problem into silently skipped records, which is the one outcome worse than failing.

**Rejected.** One transaction per batch. A failure at row 39,000 would roll back 38,999 correct
judgements, and a long-running write transaction over a first historical load is its own
operational problem.

**Rejected.** `@Transactional` on a per-row method. The loop is in the same class, so
self-invocation would bypass the proxy and the annotation would do nothing — silently. An
explicit `TransactionTemplate` cannot fail that way.

---

## FIN-D-025 — A JSON column preserves the payload's content, not its bytes

**Date:** 2026-09-17 · **Affects:** `fin_stg_revenue.raw_json`, and the FIN-050 record of it

**Decision.** `raw_json` stays a `JSON` column. The FIN-050 documentation claim that the
payload is stored "exactly as delivered" is corrected here: MySQL and TiDB store a parsed
representation and re-emit it with their own key order and spacing.

**Reason it was found.** A FIN-053 test asserted the stored payload was byte-identical to what
was written and failed: `{"gross":"9061629360.05","mode":"","note":null}` came back as
`{"mode": "", "note": null, "gross": "9061629360.05"}`. Every field name, value and null
survived; the formatting did not.

**Why the column type stays.** What staging must preserve is evidence — which fields the
source supplied and what each contained — and that is preserved exactly, including empty
strings and JSON nulls, which must never become `0` or `""` later. In exchange the database
guarantees that nothing unreadable can be staged at all, and `JSON_EXTRACT` makes investigation
possible without parsing in application code. Byte-level fidelity would buy nothing an
investigator needs.

**Consequence.** Any future check of a staged payload must compare parsed content, not text.
The claim in the FIN-050 handoff section is corrected in place; `V113` itself is **not** edited,
because changing an applied migration's text changes its Flyway checksum and would fail
validation on any database that has already run it.

---

## FIN-D-026 — Validation terminates by construction, through an advancing cursor

**Date:** 2026-09-17 · **Affects:** `RevenueStagingValidator.validateBatch`, `FinStgRevenueRepository`

**Decision.** The chunked read is keyed on the last id seen — `id > :afterId` — rather than
repeatedly requesting the first page of `RECEIVED` rows. Each staged row is offered to the
validator exactly once per run.

**Reason.** The offset form made termination conditional on something the loop does not
control. It exited only when the `RECEIVED` set emptied, which assumed every row it read would
leave that state; a row that did not — the ordinary result of losing a claim race to a second
validator — was handed back on the next pass indefinitely. The method could not finish.

The severity is in how that failure presents. Not an exception, not a failed batch, not a
`fin_sync_error`: a worker thread consuming a database connection for ever while the batch
stays `RUNNING`, `rows_rejected` is never written, and the dashboard shows figures that are
merely old. Everything the rest of FIN-053 does to make a lost judgement impossible is
bypassed by a run that never reaches its own final statement. **A hang is harder to notice
than a crash**, so termination must not depend on a condition being remembered.

Ids strictly increase and the cursor only moves forward, so the query is guaranteed to run
out. This is safe because the status axis is monotonic: `RECEIVED` is the state rows are
created in and nothing returns them to it, so a row passed over cannot reappear behind the
cursor.

**Rejected — a per-pass progress counter** (`claimedInPass`, break when a pass claims
nothing). It terminates, and it was the original intent, but it is a detector bolted onto a
loop that is still shaped wrongly: the loop re-reads rows it has already judged unclaimable,
so `alreadyClaimed` counts observations rather than rows, and a batch where one row is
contended still costs a full extra scan. It also leaves the O(n²) behaviour below intact.

**Rejected — a fixed iteration cap.** It substitutes an arbitrary number for an argument about
why the loop ends, and on a large batch it would stop the run early while reporting success.

**A performance defect went with it.** Asking for page 0 each time re-scanned the batch from
the beginning per chunk: quadratic in the number of staged rows, on the table that receives a
first historical load. The cursor makes it one pass. Measured on the test batch, the class
dropped from 221.4 s to 74.8 s.

**Consequence for counters.** `Result.alreadyClaimed` is now a count of rows, not of attempts,
and `validated + rejected + alreadyClaimed` is the number of rows the run looked at. Only
`processed()` — validated plus rejected — is a claim about work this run actually did.

**How it was missed.** The guard this record replaces was described in this document and in
the handoff, and the test written to prove it (`should_terminate_when_noRowCanBeClaimed`)
existed — but the guard itself was in no source file, and the suite was recorded as green
without the failing test ever being observed. See FIN-D-027: the harness that was supposed to
catch this reported a stale result.

---

## FIN-D-027 — A mutation result counts only against a freshly produced report

**Date:** 2026-09-17 · **Affects:** the FIN-053 mutation harness, and any future use of it

**Decision.** A mutation run is recorded as measured only when all of the following hold: the
patch changed the file, the Surefire report was deleted before the run, a report exists
afterwards, and its modification time is later than the moment the run began. Timeout, missing
report, stale report and compilation failure are four distinct recorded outcomes, none of them
a test result.

**Reason.** The previous harness read whatever report was on disk. When a mutated run hung, no
new report was written and the script recorded the *previous* mutation's numbers — which is
how M2 came to carry M1's result, down to the same six failing test names. Three mutations were
then reported in the tracking documents as evidence when two had never run and one had never
been executed at all.

This is worse than having no mutation testing. A mutation score is used to justify the claim
that a guarantee is enforced rather than merely coded; a harness that manufactures agreement
between runs produces confident, specific, false evidence. The unbounded loop in FIN-D-026 is
exactly what this was supposed to catch, and the stale report is why it did not.

**Also required.** The harness restores the source after every mutation including the timeout
path, verifies the restoration against a checksum taken before the first patch, and refuses to
continue if it does not match. A hang is cleaned up by killing only the JVMs that appeared
after the run started, so an unrelated process on the machine is not taken down with it.

**Consequence.** Mutation numbers in the tracking documents are only those a fresh report
supports. Where a mutation could not be executed, it is recorded as not executed, with the
reason — never omitted and never inferred from a neighbouring run.

---

## FIN-D-028 — A mapping rule's namespace names the staged field it reads

**Date:** 2026-09-17 · **Affects:** `fin_mapping_rule.source_value`, `MappingRuleResolver`

**Decision.** `source_value` is written `<field>:<value>` — `SANNIDHI:DS`, `SEVA_CODE:430` — and
the part before the colon is **the name of the staged field the rule matches against**. The
mapping stage derives the set of fields it reads from the rules themselves.

**Reason.** ADR-004 puts extraction in code and value mapping in configuration, and FIN-054 is
the configuration half. But a stage forbidden to know source column names still has to find the
value a rule is about, and nothing previously connected the two: `fin_mapping_rule` said what
`DS` means without saying where `DS` is found.

Reusing the namespace closes that with no new column and no change to the connector contract.
It also keeps the property that makes the stage generic — add a rule in a new namespace and a
new field starts being read, with no deployment and with nothing in the code learning a
temple's vocabulary. The obligation it places on a connector is to emit its payload under those
logical names, which is the one point where the two vocabularies must agree, and it is now
stated rather than implied.

**Rejected.** A `source_field` column on `fin_mapping_rule`. It would duplicate information the
namespace already carries and allow the two to disagree.

**Rejected.** A new method on `TempleFinanceConnector` declaring its mapping inputs. It puts a
business classification decision back into per-temple code, which is what ADR-004 exists to
prevent, and changes a contract no implementation has yet been written against.

**Consequence.** A rule whose `source_value` has no namespace can never fire. Those are reported
as `UNUSABLE_MAPPING_RULE` rather than ignored — the two `METAL_TYPE` rules seeded for the first
source are in this form, and will need namespacing when FIN-110 maps them.

---

## FIN-D-029 — Rule precedence is stored, and equal precedence is an ambiguity

**Date:** 2026-09-17 · **Affects:** `fin_mapping_rule.priority`, `MappingRuleResolver`

**Decision.** `fin_mapping_rule` gains `priority INT NOT NULL DEFAULT 100`; the highest matching
priority wins. Two or more matches at the top priority produce `AMBIGUOUS` and **no canonical
value at all**.

**Reason.** FIN-D-015 decided that the more specific rule wins, and left it in prose: the
consequence line says "the connector must apply the precedence rule". That put a business
classification decision inside a per-temple class, where a second temple would have to
reimplement it, and left the engine with no way to tell which of two matching rules was more
specific. Storing it makes precedence configuration, visible next to the rules it orders.

It is not academic. Without the `SEVA_CODE:430` override outranking `SANNIDHI:KN`, the first
source's donation-box collections — 13 records averaging over a crore each — are classified as
ordinary donations and dominate any ranking of services devotees actually bought.

**Why ambiguity is not resolved by picking one.** The available tiebreakers are rule id and
whatever order the database returns, and both would make a temple's published revenue depend on
an implementation detail. A contradiction in configuration is a thing for a person to fix, and
saying so costs one batch's classification; choosing silently costs the credibility of the
figure.

**Rejected.** Ordering by specificity of the namespace, inferred from how many rules share it.
It guesses at intent from a statistic, and changes behaviour when an unrelated rule is added.

**Consequence.** `V114` promotes `SEVA_CODE:%` rules to 200. Expressed as a predicate on the
namespace rather than on a temple id, so it is a statement about specificity rather than about
one source, and a source not using that namespace is unaffected.

---

## FIN-D-030 — A mapping decision lives beside the staged record, not inside it

**Date:** 2026-09-17 · **Affects:** `fin_stg_revenue_mapping`, `fin_stg_revenue`, `StagingStatus`

**Decision.** Mapping writes to a separate table keyed `(stg_revenue_id, mapping_type)`.
`fin_stg_revenue` is not modified, and `StagingStatus` gains no `MAPPED` value.

**Reason.** Staging holds what the source actually sent (FIN-050). Mapping is an interpretation
of it, and interpretations change: the supported way to fix a misclassification is to correct a
rule and run again, without re-contacting the source. Evidence that is rewritten every time the
interpretation changes has stopped being evidence.

The key is also the idempotency key. One current decision per record per mapping type means a
replay updates rather than appends, so no count taken from this table can be doubled by a retry
— the same rule FIN-D-023 applies to `rows_rejected`.

**Rejected.** Columns on `fin_stg_revenue`. It mutates the evidence, and it does not generalise:
`SERVICE`, `PAYMENT_MODE` and `METAL_TYPE` would each need their own pair of columns, where the
separate table takes them as more rows.

**Rejected.** A `MAPPED` staging status. The staged row's status describes its own lifecycle;
whether an interpretation of it currently exists is a fact about a different table. Adding it
would also give FIN-056 two places to look for whether a row is loadable.

**Consequence.** Provenance is denormalised onto each decision — temple, source system, batch
and `source_record_ref`. A decision that could only be explained by joining to staging would
become unexplainable the day staging retention (Q7) is agreed.

---

## FIN-D-031 — Five mapping outcomes, and only two of them produce a value

**Date:** 2026-09-17 · **Affects:** `MappingOutcome`, `fin_stg_revenue_mapping.canonical_value`

**Decision.** `MAPPED`, `UNMAPPED`, `AMBIGUOUS`, `NOT_APPLICABLE`, `INVALID_CONFIGURATION`.
Only `MAPPED` and `UNMAPPED` set `canonical_value`; the other three leave it NULL.

**Reason.** "We did not produce a canonical value" has several causes with different owners, and
an empty value cannot mean all of them at once:

| Outcome | What it means | Who fixes it |
|---|---|---|
| `UNMAPPED` | the source sent a value no rule covers | add a mapping rule |
| `NOT_APPLICABLE` | the source sent nothing to map | the connector, or nothing — some records genuinely have no category |
| `AMBIGUOUS` | rules contradict each other | whoever owns the configuration |
| `INVALID_CONFIGURATION` | a rule names a category that does not exist | a typo, same owner |

The distinction that matters most is the first two. An unknown value is an expected part of
onboarding; a record carrying no category field at all usually means extraction stopped
supplying one. Collapsing them hides a broken connector behind a configuration gap.

**`UNMAPPED` carries a value because it has a destination.** It is real revenue whose kind
nobody has established, and it routes to the seeded `UNMAPPED` category rather than to
`OTHER_INCOME`, whose own description forbids that use (ADR-004). The other three have no
destination and must not acquire one by default, so FIN-056 has nothing it could load them as.

**Consequence.** `INVALID_CONFIGURATION` is checked against `fin_revenue_category` at mapping
time rather than at load, where a typo would surface as a constraint failure on tens of
thousands of rows with nothing naming the rule responsible.

---

## FIN-D-032 — Mapping records one error per unresolved value, not per record

**Date:** 2026-09-17 · **Affects:** `fin_sync_error` at stage `MAP`, `fin_sync_batch.rows_rejected`

**Decision.** The `MAP` stage writes one `fin_sync_error` per distinct unresolved source value
per batch, carrying the number of records affected. Validation continues to write one per
rejected row. `rows_rejected` is now derived from **`VALIDATE`-stage errors only**.

**Reason.** A single missing rule can account for an entire batch. Forty thousand identical
row-level errors would bury the one fact an operator can act on — which value, and how much it
costs — and the per-record trail already exists in `fin_stg_revenue_mapping`, at full detail.

**The counter interaction this exposed.** FIN-053 set `rows_rejected` from
`countBySyncBatchId`, unscoped. Once mapping writes against the same batch at a different
grain, that count folds mapping's summaries into a rejected-row figure and produces a number
matching no set of rows — exactly the failure FIN-D-023 exists to prevent, arriving from a
stage FIN-D-023 did not anticipate. Now scoped to `VALIDATE`.

**Consequence, stated because it is a real inconsistency.** `fin_sync_error` no longer holds one
grain. Its comment says "row-level rejections" and `MAP` rows are not that: they carry a NULL
`source_record_ref`, because they are about a value rather than a record. The alternative — a
separate summary table — was rejected as a second place to look for the same question, but the
mixed grain is a cost, and anything counting this table must scope by stage.

**Re-running clears this stage's errors first**, scoped to `MAP`, so a replay replaces the list
rather than growing it and cannot touch what validation recorded.

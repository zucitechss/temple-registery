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

---

## FIN-D-033 — A declaration's `source_field` names the staged field, not the source column

**Date:** 2026-09-17 · **Affects:** `fin_source_of_truth_decl.source_field`, the connector contract

**Decision.** `fin_source_of_truth_decl.source_field` is the key normalization reads from
`raw_json`. A connector that renames a field on the way out must declare the name it emits, not
the name it read.

**Reason.** FIN-053 left this open: are declaration field references and connector field names
the same vocabulary? FIN-054 answered the equivalent question for mapping rules — the namespace
*is* the staged field name (FIN-D-028) — and a platform where the two configuration mechanisms
name fields differently is one where nobody can tell, from configuration alone, which spelling
a stage will use. The precedent is followed rather than rejected.

**Cost, stated plainly.** It constrains connectors: a payload's keys are part of the contract,
not an internal detail. That is the point. A connector free to rename its output silently
detaches every declaration and every mapping rule at once, and the first symptom would be a
batch of `MISSING_GROSS_AMOUNT` rejections nobody could attribute.

**Alternative rejected.** A separate "emitted field name" column beside `source_field`. It
would record both the source column and the key, which is genuinely more informative — and it
would let the two drift with no error, which is worse than the ambiguity it removes.

---

## FIN-D-034 — The financial year is computed in exactly one place

**Date:** 2026-09-17 · **Affects:** `fin_revenue_fact.financial_year`, `FinancialYear`

**Decision.** `FinancialYear.of(LocalDate)`: 1 April start, canonical `2025-26` form, used by
everything that needs a financial year from a date.

**Reason.** `financial_year` is stored rather than generated by the database, so it is
functionally dependent on a column sitting next to it. Two computations that disagree produce
facts that disagree with their own dates while both columns look entirely plausible in
isolation, and the error surfaces as a district total that moves when nobody changed anything.
One function, tested at both boundaries, is the whole mitigation.

**Not configurable.** April is statutory for Indian government accounting. A temple whose
internal books run on another cycle is still reported to the department on this one, so a
per-temple year start would be a setting whose only use is to produce wrong reports.

---

## FIN-D-035 — A field is declared for a whole source, or absent from it

**Date:** 2026-09-17 · **Affects:** `RevenueField`, `fin_revenue_fact` measures

**Decision.** `TRANSACTION_DATE` and `GROSS_AMOUNT` are required — without them the batch is
refused. Every other field is optional, and an undeclared one is NULL on every fact.

**Reason.** Absence is data (ADR-007). A source that does not record cancellations must produce
facts whose `cancelled_amount` is NULL, so the generated `net_amount` is NULL too and no report
can quietly present gross as net. Zero would assert that cancellations were counted and none
were found.

**The consequence that matters.** Because a declaration governs the source rather than the row,
a measure is available for every record or for none — so a daily group is never summed from a
mixture of known and unknown contributors by accident. Where a row is nevertheless blank,
FIN-D-036 applies.

---

## FIN-D-036 — Unknown plus known is unknown

**Date:** 2026-09-17 · **Affects:** the daily collapse in `RevenueNormalizationStage`

**Decision.** When records collapse onto one daily fact, a measure left null by any contributor
makes the group's total null, not the sum of the ones that were present.

**Reason.** A partial sum is indistinguishable from a complete one. Reporting it understates the
figure with nothing on the row to say so, which is the precise failure this platform exists to
avoid. Gross amount cannot reach this state: it is required, and a record without one is
rejected rather than contributing.

---

## FIN-D-037 — Normalization returns facts; it does not persist them

**Date:** 2026-09-17 · **Affects:** `RevenueNormalizationStage`, FIN-056

**Decision.** The stage computes the canonical facts and returns them. No
`fin_stg_revenue_normalized` table exists; FIN-056 consumes the result.

**Reason.** A third staging table would need its own idempotency key, its own cleanup and its
own retention answer — and Q7 has produced no answer for the two tables that already exist.
Computation that writes nothing is idempotent for free: running it twice produces the same facts
because it is a function of staging, the mapping decisions and the declarations, all of which it
leaves alone.

**Ceiling, and the lever.** A batch's groups are held in memory along with the staged row ids
that made each one. That is bounded by the batch, not by history — but a first historical load
is one batch, and it will not fit. Normalizing in windows of business dates is the answer when
that arrives; the grain makes it straightforward. It is not built because no batch of that size
can be produced until a connector exists.

---

## FIN-D-038 — Staged rows are not marked `LOADED` by normalization

**Date:** 2026-09-17 · **Affects:** `fin_stg_revenue.validation_status`

**Decision.** Normalization leaves `validation_status` alone. The `VALID -> LOADED` transition
belongs to FIN-056, after the fact is actually written.

**Reason.** A record has not been loaded until something loads it. Marking it earlier would
strand rows as loaded against a fact that a later failure meant nobody wrote, and the status is
monotonic by design, so there would be no way back.

---

## FIN-D-039 — A dubious value is refused, never repaired

**Date:** 2026-09-17 · **Affects:** `RevenueNormalizer`

**Decision.** An ambiguous date, a non-numeric amount and an amount carrying more precision
than the canonical column are all rejections with codes, not corrections.

**Reason, for each.** `03/04/2025` is 3 April or 4 March depending on a convention nobody
declared; guessing moves revenue between months and, in the first week of April, between
financial years. Rounding `1500.505` into a `DECIMAL(18,2)` column is a silent write-down
repeated across every affected row, and the difference reappears later as a reconciliation gap
that cannot be explained from the fact table. Reading a missing amount as zero asserts a
measurement that never happened.

**What this costs.** A source whose dates are not ISO cannot be normalized until its connector
emits ISO or its format is declared. That is a deliberate obstacle: the alternative is a
platform that publishes plausible figures from payloads it did not understand.

---

## FIN-D-040 — A grouped fact carries no single source record reference

**Date:** 2026-09-17 · **Affects:** `fin_revenue_fact.source_record_ref`

**Decision.** `source_record_ref` is carried only where exactly one record produced the fact.
For a group it is NULL, and the contributing staged row ids are the trace.

**Reason.** Naming one member of a group — the first, say — points an investigator at an
arbitrary record and hides the rest, which is worse than saying nothing. NULL here means
"several", and is honest about it.

---

## FIN-D-041 — The load assigns measures; it never accumulates them

**Date:** 2026-09-17 · **Affects:** `fin_revenue_fact`, `FinRevenueFactRepository.upsert`

**Decision.** `ON DUPLICATE KEY UPDATE gross_amount = VALUES(gross_amount)` — replacement, never
`gross_amount + VALUES(gross_amount)`.

**Reason.** A retry and a restatement are different events and this one choice handles both. A
retry must leave the totals unchanged; a later batch re-reading days already loaded must replace
those days, because the source was re-read and this is what it now says. Adding would double a
temple's reported revenue on every replay.

**Why it needs a test rather than a constraint.** An accumulating upsert satisfies
`uk_frf_grain` perfectly — the row is unique either way — so nothing in the schema can catch it.
Mutation L1 fails exactly one test, and without that test the defect would reach production
looking like a working loader.

**Consequence.** `sync_batch_id` records which batch *last* wrote a row, not every batch that
contributed. That is the honest reading: after a restatement the earlier batch's figures are no
longer what the platform reports.

---

## FIN-D-042 — A partial load keeps what it wrote and still fails the batch

**Date:** 2026-09-17 · **Affects:** `RevenueLoadStage`

**Decision.** Each fact is written in its own transaction. A failure does not roll back the facts
already written, and the batch still ends by throwing `LoadFailedException` with its errors
recorded at stage `LOAD`.

**Reason, both halves.** Keeping the written facts is safe because each is idempotent and keyed
on the grain, and discarding them would make a retry redo work that had already succeeded — on
the operation that handles the most rows in the system. Failing the batch anyway is the other
half: a batch that reports `SUCCESS` having written some of its facts is worse than one that
reports `FAILED`, because the second is investigated and the first is believed (FIN-D-017).

**Found by a mutation, not by design.** Removing the throw altogether changed no test result
until the failure path was actually exercised — nothing in the suite had ever made a write fail.
The tests now force one with an amount too large for `DECIMAL(18,2)`, which is a plausible
corrupt source value that reaches the write before anything objects.

---

## FIN-D-043 — The staging claim's guard is defence in depth, and its removal is not observable

**Date:** 2026-09-17 · **Affects:** `FinStgRevenueRepository.markLoaded`

**Decision.** `markLoaded` stays conditional on `validationStatus = VALID`. No test asserts that
the condition is present, because none can honestly.

**What the mutation showed.** Removing the condition kills nothing (L4, SURVIVED). Normalization
builds facts only from `VALID` rows, so a second load finds nothing to claim whether or not the
guard is there, and `rows_loaded` is derived from a count rather than incremented, so even two
racing loaders converge on the same number.

**Why it stays anyway.** It is correct and costs nothing, and it is the guard that would matter
if a future caller ever incremented a counter instead of deriving one. But a test written to
"cover" it would be asserting behaviour the design already guarantees by another route — the
vacuous kind. Recorded rather than faked, exactly as FIN-D-024 was.

---

## FIN-D-044 — A source deleting records cannot be detected by an incremental load

**Date:** 2026-09-17 · **Affects:** `fin_revenue_fact`, FIN-060

**Decision.** If a later batch produces no fact for a grain an earlier batch loaded, the earlier
fact stands. Nothing is deleted, and nothing guesses.

**Reason.** An incremental batch's window is a *modification* window, not a business-date range
(ADR-006), so "this day should now be empty" cannot be inferred from it. A loader that deleted
facts outside the current batch's output would erase correct history every time a batch covered
a narrower window than the one before.

**The cost, stated.** A source that deletes receipts will leave this platform overstating those
days, silently, until either a full reload of a date range or a reconciliation against source
totals (FIN-060) catches it. There is a test asserting the current behaviour so the limitation is
visible rather than discovered.

---

## FIN-D-045 — Extraction is a generic stage, not the Kollur connector's job

**Date:** 2026-09-17 · **Affects:** `RevenueExtractionStage`, Limitation 11

**Decision.** `RevenueExtractionStage` drains a connector's `Stream<RawRow>` into
`fin_stg_revenue`. It is production code, owned by no task's temple, and it is new in FIN-057.

**Why it had to be written here.** Limitation 11 recorded extraction as FIN-043's. It is not:
FIN-043 is scoped as the *Kollur* connector's `extract()` — its live receipt table and its six
financial-year archives. Taking rows from any connector and landing them in staging is generic,
and a search of production code for a write against `fin_stg_revenue` found none. The pipeline
had no first step. Faking one in the test would have made the orchestrator "execute" while
production still could not stage a single row.

**Boundary.** This stage stores; it does not interpret. A `RawRow`'s values go to `raw_json` as
delivered. No date is parsed, no amount read, no field name understood — those belong to
validation (FIN-053), mapping (FIN-054) and normalization (FIN-055), after a declaration says
what a field means. That is also what keeps the class free of `DailySevaNew`, `HKanikeItems` and
TempleCode 43.

---

## FIN-D-046 — A doomed chunk is retried row by row, from the raw rows

**Date:** 2026-09-17 · **Affects:** `RevenueExtractionStage.flush`

**Decision.** Staging writes in chunks of 500, each in its own transaction. A chunk refused by
`uk_fsr_batch_record` is retried one row at a time, and each retry builds a **fresh entity from
the `RawRow`** rather than re-saving the instance the failed `saveAll` was given.

**Why the rebuild matters.** The failed `saveAll` assigned generated ids to those instances and
the rollback did not take them back. Re-saving them makes Hibernate treat each as detached and
attempt a *merge* against a row that was never inserted — `StaleObjectStateException`, not the
constraint violation the caller is diagnosing. Discovered by a test, not by reasoning: without
the fix, any chunk containing one duplicate reference failed the whole batch with a misleading
error.

**Why not one transaction for the extract.** A single duplicate at row 40,000 would discard
everything, which is the exact failure mode staging's loose payload exists to prevent.

---

## FIN-D-047 — The orchestrator composes; it owns status and nothing else

**Date:** 2026-09-17 · **Affects:** `FinancePipelineOrchestrator`

**Decision.** `run(syncBatchId)` claims the batch, then calls extraction, validation, mapping and
load in order, each with the batch id alone. It increments no counter and writes no domain row.

**Why composition was this small.** Five stages existed before this class and none had a caller.
Each takes a batch id and returns a result; none takes a payload from another. The database
carries the data between stages, so the orchestrator passes an id and reads a result. No stage
was rewritten to fit the abstraction.

**Counter ownership, restated.** `rows_extracted` belongs to extraction, `rows_rejected` to
validation, `rows_loaded` to the load — each *derived from a count*, never incremented
(FIN-D-023). A counter written in two places is one nobody can reconcile after a partial run, and
the orchestrator writing a total on top of a stage's would be exactly that.

**Normalization has no separate call.** The load normalizes as its first step (FIN-D-037), so the
orchestrator has four calls for five stages. A failure inside normalization is reported at stage
`LOAD`, because that is the call an operator sees fail.

---

## FIN-D-048 — No stage runs inside the orchestrator's transaction, and a failure gets its own

**Date:** 2026-09-17 · **Affects:** `FinancePipelineOrchestrator`, `FinSyncBatchRepository.claimForRun`

**Decision.** `run()` is not `@Transactional`. Claim, finish and failure-recording each use
`TransactionTemplate` in a transaction of their own; every stage manages its own boundaries
internally.

**Why not one transaction.** A full extract-to-load run over a real source is minutes of work and
tens of thousands of rows. Holding one transaction across it would pin undo log for the duration,
block on TiDB's transaction size limits, and — worse — discard every successfully staged row when
the load failed on one fact. Partial progress that is *recorded* is what makes a batch
diagnosable (FIN-D-042).

**Why the failure write is separate.** The transaction a stage was using may already be doomed.
Writing the status inside it would roll back with everything else and leave the batch `RUNNING`
forever — the one state nothing recovers from automatically. `recordFailure` is additionally
best-effort: if it fails too, the original exception still propagates rather than being replaced
by a bookkeeping error.

**Claiming, not checking.** `claimForRun` is `UPDATE ... WHERE id = ? AND status = PENDING`. Two
runners racing cannot both win; the loser's update matches no row and it is refused **by name**
(`BatchNotClaimableException` naming the actual status), never skipped silently. A caller that
asked for a run and got silence cannot tell "already done" from "did nothing".

**No distributed lock, scheduler or queue was added.** The conditional claim is sufficient for the
current single-worker architecture, and a test proves two threads racing produce exactly one
winner.

---

## FIN-D-049 — The orchestrator does not advance the watermark

**Date:** 2026-09-17 · **Affects:** `FinancePipelineOrchestrator.finish`, `fin_sync_batch.watermark_after`, FIN-043

**Decision.** A successful run sets `status`, `finished_at` and `duration_ms`. It leaves
`watermark_after` null. A test asserts this explicitly, so the gap is visible rather than
discovered.

**Why.** `watermark_after` is the change-axis position a future incremental sync resumes from.
Deriving it means knowing which source column the watermark is read from, and that is a
per-connector fact that belongs to the connector's extract (FIN-043) — no generic code can name
it. Writing *something* here to make the field non-null would mean a later run skipping a window
this one only partly processed, which is the one watermark error that silently loses money.

**Boundary recorded.** Watermark advancement is FIN-043's, and must happen only from data the
connector actually observed. Until then, incremental resumption is not available and every batch
must be given its window explicitly.

---

## FIN-D-050 — Reconciliation records what it compared, because the checks are not equal evidence

**Date:** 2026-09-17 · **Affects:** `fin_reconciliation_result.check_type`, `ReconciliationCheckType`, `V116`

**Decision.** Every reconciliation row carries a `check_type`. Two values name checks that compare
this platform's numbers against each other (`STAGE_COMPLETENESS`, `REJECTION_ACCOUNTING`); two name
checks that compare against a figure the source system produced (`SOURCE_VS_CENTRAL`,
`SUSPECTED_SOURCE_DELETION`).

**Why the column had to exist.** `fin_reconciliation_result` was shaped for one kind of check, and
that kind cannot run today — no production connector implements `sourceTotals()`. The checks that
*can* run are entirely local. A table that could not tell them apart would let a screen full of
green rows be read as "the figures agree with the temple". Nobody has asked the temple.

**What `source_total` means now.** For `SOURCE_VS_CENTRAL` it is still the source's own figure. For
a local check it is the upstream side of the comparison and `central_total` the downstream side —
rows staged against rows accounted for, for instance. The column comments were changed to say so,
because silently redefining a column is how an audit trail stops being one.

---

## FIN-D-051 — A reconciliation result is idempotent per batch and append-only per period

**Date:** 2026-09-17 · **Affects:** `uk_frr_batch_check`, `FinReconciliationResultRepository.record`

**Decision.** `UNIQUE (sync_batch_id, capability, check_type, metric, period_type, period_key)`,
written through `INSERT … ON DUPLICATE KEY UPDATE` with assignment, never accumulation.

**The nullable column inside the unique key is deliberate.** NULL never matches NULL in a MySQL
unique index — the behaviour FIN-D-018 had to engineer generated columns to defeat. Here it is
exactly what is wanted, and it is used rather than worked around:

- a batch-scoped result (`sync_batch_id` present) **replaces** its own previous answer, so
  re-running reconciliation cannot leave two contradictory rows about one question;
- a scheduled re-verification of a period (`sync_batch_id` null) **appends**, which is what an
  append-only history of a period's figures requires.

One constraint, two behaviours, no generated column. `created_at` is excluded from the update
list, so the first time a question was asked survives every re-check of it.

---

## FIN-D-052 — A shortfall is a suspicion, never a deletion, and never an action

**Date:** 2026-09-17 · **Affects:** `RevenueReconciliationStage`, FIN-D-044

**Decision.** When a source reports fewer records for a **closed** period than this platform holds,
a `SUSPECTED_SOURCE_DELETION` row is written with status `FAILED` and a reason that names the
alternatives. Nothing is deleted, nothing is overwritten, and no record is marked deleted. A test
asserts the canonical facts are byte-for-byte unchanged afterwards.

**Why it cannot be a conclusion.** A partial source response, a network failure, a source filter
error, a source-side correction, and this platform having over-counted all produce the identical
observation. The connector contract offers no deletion signal and no record-level snapshot — only
`sourceTotals()`, which returns totals. Totals can raise a question; they cannot answer it.

**Why an open period does not even raise the question.** For a year that has not ended, records may
still arrive, so a shortfall is not evidence in either direction. That case records
`NOT_AVAILABLE` rather than a pass, because a clean result there would be a different lie.

**What acting on it would require.** A restatement policy, a human decision, and a record of both.
None exists, and inventing one inside a reconciler — the component whose own checks would then
pass — is the wrong place for it under any policy.

---

## FIN-D-053 — A canonical row count is not a fact count

**Date:** 2026-09-17 · **Affects:** `FinRevenueFactRepository.sumTransactionCountForSourceAndFinancialYear`

**Decision.** The canonical side of a record-count comparison is `SUM(transaction_count)`, and it
is reported as `NOT_AVAILABLE` if **any** contributing fact has a null transaction count.

**Why not `COUNT(*)`.** A canonical fact is a daily grain (ADR-003); several thousand receipts
collapse into one row. Counting rows and comparing that against a source's record count would
compare two different things and disagree by design, every time.

**Why any unknown makes the whole year unavailable.** `REVENUE_TRANSACTION_COUNT` is an optional
declaration. Where it is undeclared the count is null, and summing gives a floor. A floor compared
against a source count manufactures a shortfall that looks exactly like a deletion — turning a
missing declaration into a false accusation against a temple. That is the "convert missing data to
zero" failure in a costume (ADR-007).

**Consequence, stated.** Until a source declares `REVENUE_TRANSACTION_COUNT`, record-count
reconciliation and deletion detection are both unavailable for it, whatever the connector reports.

---

## FIN-D-054 — `RECONCILE_FAILED` is a distinct outcome, and `NOT_AVAILABLE` blocks nothing

**Date:** 2026-09-17 · **Affects:** `FinancePipelineOrchestrator`, `SyncStatus`

**Decision.** Reconciliation runs as the last stage of a pipeline run. A check that found a real
disagreement ends the batch `RECONCILE_FAILED`; otherwise `SUCCESS`. A stage that *threw* still
ends the batch `FAILED`. No new status was added — `SyncStatus.RECONCILE_FAILED` and
`SyncStage.RECONCILE` have existed unused since V110.

**Why not `FAILED`.** The rows are loaded and inspectable. What is in doubt is whether they are the
source's, not whether they were processed. `FAILED` puts a batch on the retry path, where
re-extracting would reach the same figures and the same disagreement, forever.

**Why `NOT_AVAILABLE` does not block.** A check that could not be made is not a reason to withhold
figures that loaded correctly; it is a reason not to claim they were verified, which the recorded
status already does. Treating the two the same would publish nothing at all until a connector
implements `sourceTotals()` — and the status column would stop carrying the distinction that makes
it worth recording.

**Boundary.** The orchestrator records the outcome. *Acting* on it — withholding aggregates — is
FIN-061's, and nothing here publishes anything.

---

## FIN-D-055 — The reconciler reads everything and writes one table

**Date:** 2026-09-17 · **Affects:** `RevenueReconciliationStage`

**Decision.** `RevenueReconciliationStage` writes only `fin_reconciliation_result`. It does not
touch `fin_revenue_fact`, `fin_stg_revenue`, `fin_sync_error` or the batch counters, and it is not
`@Transactional` — each finding is persisted in its own `REQUIRES_NEW` transaction.

**Reason.** A reconciler that could repair what it found could make its own checks pass, and the
difference between "the figures were right" and "the reconciler adjusted them until they were"
would exist nowhere in the record. Reading widely and writing narrowly is what makes its output
evidence.

**On completeness counting.** A row rejected by *normalization* keeps its `VALID` staging status
(FIN-D-038), so `STAGE_COMPLETENESS` counts `LOADED` + `REJECTED` + rows with a `NORMALIZE` error
as accounted for. Counting only the two terminal statuses would report every unparseable amount as
an unexplained loss and fail almost every real batch.

---

## FIN-D-056 — A constraint the design depends on gets a test, not just a column definition

**Date:** 2026-09-17 · **Affects:** `fin_reconciliation_result.check_type`, `FinanceFoundationRepositoryTest`

**Decision.** `check_type` is `NOT NULL` with no entity-level default, and
`should_refuseResult_when_checkTypeIsMissing` asserts that a result without one is refused.

**Why a default was rejected.** Making the entity supply a default check type would have fixed the
two failing tests in one line and made the column non-null in name only — every caller that forgot
to say what it compared would have silently recorded `SOURCE_VS_CENTRAL`, which is the most
consequential value to get wrong. The whole point of FIN-D-050 is that the two kinds of check are
not interchangeable evidence.

**Why the test exists.** Until it was written the constraint was enforced by the database and
asserted by nothing. A later change adding a "convenience" default would have removed a
load-bearing guarantee with a green suite.

**How it was found.** By breaking it. Two pre-existing tests built a result without a check type
and failed on **H2** — `FinanceFoundationRepositoryTest` is one of the few finance tests not on
Testcontainers, and the MySQL-based FIN-060 tests could not have caught it because every result
they write has a check type. Two engines in one suite is why this surfaced before a commit.

---

## FIN-D-057 — The publication decision is derived, never stored

**Date:** 2026-09-18 · **Affects:** `ReconciliationGate`

**Decision.** There is no decision table, no decision status column and no migration. The verdict
is computed from `fin_reconciliation_result` and `fin_revenue_fact` every time it is asked for.

**Why.** A stored verdict is a second status system that must be kept in agreement with the first
one by hand, and the first thing to go wrong with it is a period published under a verdict written
before the batch that changed it. Deriving it makes the decision idempotent by construction,
impossible to leave stale, and impossible to contradict its own evidence — which is a stronger
guarantee than "we remember to recompute it".

**What this means for the brief's "persist decisions transactionally".** The *evidence* is
persisted transactionally, by FIN-060. The decision is a pure function of it. Two callers asking at
once cannot create contradictory states because neither writes anything, so no lock was added;
concurrency safety here is the absence of mutable state, not a mechanism.

**Cost, stated.** There is no historical record of what the gate decided at a past moment — only of
what the evidence was. If an audit ever needs "what did the platform believe on 3 March", that
needs a decision log, and it should be added then rather than guessed at now.

---

## FIN-D-058 — Four publication outcomes, and the two that publish nothing new

**Date:** 2026-09-18 · **Affects:** `ReconciliationGate`, `ReconciliationStatus.PENDING`

**Decision.**

| Verdict | Publishes? | Meaning |
|---|---|---|
| `PASSED` | yes | every check that ran agreed |
| `NOT_AVAILABLE` | yes, flagged | checks could not be made; what did run agreed |
| `FAILED` | **no** | a check found a real disagreement |
| `PENDING` | **no** | figures exist that nothing has verified |

**No new vocabulary was invented.** These are the four values `API_CONTRACT.md` already documents
for the `reconciliation` field a finance response carries. `PENDING` was added to
`ReconciliationStatus` rather than to a parallel enum, because two enums covering one documented
vocabulary is exactly the duplicate status system worth more than the extra constant costs. It is
**derived only** and is never written to `fin_reconciliation_result`.

**`NOT_AVAILABLE` publishes** (following FIN-D-054). No production connector implements
`sourceTotals()`, so blocking on it would publish nothing at all, forever, for every temple — while
withholding figures that loaded correctly. What is withheld is the *claim* that they were verified,
which the status carries.

**`PENDING` does not publish.** Absence of a result is not absence of a problem. A batch that loads
facts and then dies before reconciliation leaves precisely this state, and it is the one a naive
"nothing failed, so publish" gate waves through.

---

## FIN-D-059 — A batch's completeness taints every period it fed

**Date:** 2026-09-18 · **Affects:** `ReconciliationGate.evaluate`

**Decision.** A period is publishable only if **both** its period-scoped checks pass **and** every
batch that contributed facts to it passed its own batch-scoped checks. A contributing batch that
was never reconciled at all blocks the period outright.

**Why both scopes.** `STAGE_COMPLETENESS` and `REJECTION_ACCOUNTING` are recorded against a batch,
not a period, because a batch that lost rows lost them from whichever periods those rows belonged
to. Reading only the period's own totals would let a partial extraction publish as clean whenever
the rows that *did* arrive happened to add up — which they always do, since they are summed from
themselves.

**Supersession is per question, not per period.** Two batches that both reconciled FY2025-26 each
answered the same question; the later answer is the current truth. Without that, a variance could
never be cleared by correcting it, only by deleting history. Batch-scoped answers are not
superseded: each batch's own completeness stands on its own record.

---

## FIN-D-060 — No override, because there is nobody to authorize one

**Date:** 2026-09-18 · **Affects:** FIN-061 scope

**Decision.** FIN-061 implements no override, no force-publish and no approval workflow.

**Why, in order of weight.**

1. **No requirement documents one.** Nothing in the architecture, the reconciliation framework, the
   report catalogue or the task plan describes an override. Inventing an authorization rule for a
   financial control was explicitly out of bounds.
2. **There is no authenticated caller to authorize.** The finance pipeline has no API — Phase 8 is
   `NOT_STARTED` — and RBAC (`CAN_ACT_DC`: `SUPER_ADMIN`, `DISTRICT_COLLECTOR`) is enforced at HTTP
   entry points in the registry runtime. An override added now would be reachable only from code
   with no principal attached, which is an unauthenticated bypass of a financial control wearing
   the word "override".
3. **Nothing is blocked yet that needs releasing.** There are no aggregates to withhold, so an
   override would today unblock nothing.

**When it is needed, it needs:** a finance API endpoint, `CAN_ACT_DC` or stricter, the overriding
user, timestamp and written justification persisted as an append-only record, and the override
scoped to one temple, one source and one period — never a global switch. Recorded as a follow-up,
not implemented as a guess.

---

## FIN-D-061 — The gate is not worker-only, and that is deliberate

**Date:** 2026-09-18 · **Affects:** `com.templeregistry.service.finance.publication`

**Decision.** `ReconciliationGate` is a plain `@Service` in a new `publication` package, available
to both runtimes — unlike every pipeline stage, which is a `@Bean` registered only under the
`sync-worker` profile (FIN-D-008).

**Why it is safe.** ADR-001 forbids the registry runtime reaching a *temple source system*. The gate
holds no connector, no registry, no credential provider and no source descriptor; it reads two
central tables whose repositories are already component-scanned into both runtimes. It adds no
capability that the registry runtime did not already have — only a correct reading of data it could
already query.

**Why it is necessary.** The API contract requires every finance response to carry a
`reconciliation` status per period. That is served from the registry runtime. A worker-only gate
would force the API to re-derive the same rule from the same rows, and two implementations of a
publication rule will disagree eventually.

**The package matters.** `service.finance.pipeline` and `service.finance.sync` are guarded by
`FinanceIntegrationBoundaryTest`, which fails the build on a stereotype annotation there. Putting
the gate in `service.finance.publication` keeps that guard meaningful rather than weakening it to
accommodate a class that genuinely does not belong to it.

---

## FIN-D-062 — The namespace format is refused; an unrecognised namespace is only warned about

**Date:** 2026-09-18 · **Affects:** `SourceValueKey`, `MappingAdminServiceImpl`, FIN-D-015

A mapping rule matches `SEVA_CODE:430`, where `SEVA_CODE` names the staged field the rule reads.
A rule whose stored value has no separator names no field: the database accepts it, the screen
shows it as active, and it silently never matches anything. That is the worst failure this feature
can produce, because the user has done the thing the screen asked of them and been told it worked.

**Decision, in two halves.**

*Format is a hard refusal.* No separator, an empty half, or a separator inside the field name —
rejected before anything is saved. The rule is defined once, in `SourceValueKey`, and
`MappingRuleResolver` now uses the same definition. Two copies of "what can fire" would have been
the actual bug: an API that accepts what the engine rejects produces exactly the inert rule this
decision exists to prevent.

*An unrecognised field name is a warning, not a refusal.* **There is no registry of the fields a
source emits.** The connector chooses them and the resolver derives what it reads from the rules
themselves, so the only evidence available is the keys of staged payloads. Refusing on that
evidence would mean:

- a source system could not be configured before its first extraction — there is nothing staged,
  so every namespace is unrecognised; and
- purging staging would start refusing namespaces that are correct, and staging retention is still
  unresolved (open question Q7).

So the rule is saved and the response says, in words, that no staged record carries that field and
the rule will not match anything until one does. `GET /finance/source-systems/{id}/namespaces`
exposes the observed names so the correct value can be offered as a list rather than typed.

**Rejected:** a hard-coded list of permitted namespaces. It would have to be maintained in step
with every connector, and a platform whose whole design keeps source vocabulary out of generic code
(ADR-004) must not acquire a table of it in a validator.

---

## FIN-D-063 — Mapping audit is written in the caller's transaction, not through `AuditService`

**Date:** 2026-09-18 · **Affects:** `MappingAdminServiceImpl`, `AuditDataEvent`

`AuditService` exists and writes `audit_data_events`, which is the right table. It was not reused.

It is `@Async`, runs `REQUIRES_NEW`, and catches and logs its own failures. That is correct for what
it was built for: a lost audit line must not fail a temple's declaration. It cannot satisfy the
requirement here, which is the opposite — a change to how a temple's revenue is classified must not
survive the loss of the record of who made it.

**Decision.** `MappingAdminServiceImpl` writes `AuditDataEvent` through `AuditDataEventRepository`
in the same transaction as the rule change, with no `try`/`catch`. A failed audit write rolls the
change back.

**This is reuse, not a second framework.** Same table, same entity, same repository, same actor and
action vocabulary. What is not reused is one wrapper whose failure semantics are wrong for this
caller. Changing `AuditService` itself was rejected: every existing caller depends on its
fire-and-forget behaviour, and making audit failures fatal application-wide is a far larger change
than this task.

**Tested.** `should_rollBackRule_when_auditWriteFails` makes the audit insert fail on its own —
not the rule insert — and asserts no rule survives.

---

## FIN-D-064 — `fin_mapping_rule` gets a version column, and the stale-read check is explicit

**Date:** 2026-09-18 · **Affects:** V117, `FinMappingRule`, `MappingAdminServiceImpl`

Until this task nothing outside a migration wrote `fin_mapping_rule`, so concurrency was not a
question. An administrative API makes it one, on a row that decides which category a temple's income
is counted under.

**Decision.** V117 adds `version INT NOT NULL DEFAULT 0` and the entity carries `@Version`. Every
write endpoint requires the version the caller loaded.

**Additive and backward compatible.** Existing rows take 0, which is what Hibernate expects for a row
it has not yet updated, so there is no backfill. The pipeline only reads these rows and is unaffected.
Verified on MySQL 8.0 and H2. **Not verified on TiDB** — unchanged from every migration in this
project.

**Not on `BaseEntity`.** That would put a lock on every audited entity in the application. The
entities that need one declare it individually, as `Temple`, `Trust`, `Notice`, `AssetDeclaration`
and `WorkflowInstance` already do.

**The explicit check matters more than the annotation.** The race this feature actually has is not
two simultaneous commits — it is two administrators who opened the same list minutes apart. The
entity loaded inside the write transaction is fresh, so Hibernate's own comparison would pass and the
earlier reader's change would vanish with both callers told it succeeded. The service therefore
compares the submitted version against the loaded one and refuses first.

---

## FIN-D-065 — Only `REVENUE_CATEGORY` can be written, and only the current batch is counted

**Date:** 2026-09-18 · **Affects:** `MappingAdminServiceImpl`

Two smaller decisions, both of the same kind: refusing to show a number or accept a value that
would read as more than it is.

**A rule may only be created or edited as `REVENUE_CATEGORY`.** `MappingType` declares six values
and `RevenueMappingStage` reads one. Accepting a `PAYMENT_MODE` rule would let an administrator
configure payment mode, see it listed as active, and conclude it had taken effect. Existing rules of
the other types stay listed and can still be *deactivated* — retiring them is the one useful thing
that can be done with a rule nothing reads, and hiding the two seeded `METAL_TYPE` rules would
conceal limitation 24 rather than address it.

**The unresolved-values list is scoped to the newest batch, and names it.** Aggregating across
batches would be wrong, not merely expensive: re-extracting a period stages the same source records
again, so a value present in three batches would be reported as costing three times the records it
costs. When nothing has been mapped the response carries a null batch id, so an empty list can be
read as "not measured" rather than "nothing unresolved" (ADR-007).

---

## FIN-D-066 — The screen tells the truth about what saving a rule does not do

**Date:** 2026-09-18 · **Affects:** `MappingRuleMutationResponse`, FIN-055, FIN-056

Editing a mapping rule changes how the *next* run classifies a value. Every figure already in
`fin_revenue_fact` keeps the classification it was loaded with. Nothing in this API re-processes
anything, and no trigger to do so exists.

A user who corrects a misclassification and sees "Saved" will reasonably believe the published
figures are now right. They are not.

**Decision.** Every write response carries `historicalEffect`, a fixed sentence saying so, and the
screen is required to show it at the point of edit. It is a field rather than documentation because
documentation is not in front of the person clicking Save.

**No re-run trigger, deliberately** (plan decision D4). Re-processing historical financial data on
the strength of a dropdown change is a far worse failure than leaving a figure visibly wrong, and
there is no authorization model for a pipeline trigger. Asserted by
`should_leaveFactsUntouched_when_ruleIsEdited` and `should_leaveStagedDecisionsUntouched_when_ruleIsCreated`.

---

## FIN-D-067 — `source_system_id` joins the canonical grain, and the migration was never the hard part

**Date:** 2026-09-18 · **Affects:** `fin_revenue_fact.uk_frf_grain`, V118, ADR-003, FIN-070B D1

`uk_frf_grain` was declared over seven columns in V112 and omitted `source_system_id`, although the
column has always been `NOT NULL` and populated on every row. Two source systems reporting the same
temple, business date, service, category, payment mode, counter and operator therefore collided, and
the loader's `ON DUPLICATE KEY UPDATE` *replaced* the first source's figures with the second's —
carrying `source_system_id` and `sync_batch_id` across with them. The first source's revenue was not
added to; it was gone, with nothing recording that it had existed (limitation 47, found while writing
the FIN-060 scope test).

**Decision.** V118 widens the constraint to eight columns, with `source_system_id` second so the
index also serves the `(temple_id, source_system_id)` prefix every source-scoped reconciliation query
already filters on. The three generated stand-ins are untouched and still carry the nullable members,
because MySQL and TiDB treat NULLs in a unique index as distinct (FIN-D-018).

**The deadline analysis in FIN-070A was wrong, and correcting it is the point of this entry.** That
plan argued the change had to happen before any data landed, "because it will never be this cheap
again". Adding a column to a UNIQUE key *widens* it: rows distinct over seven columns are still
distinct over eight, so the ALTER cannot fail on data, and with `source_system_id` already `NOT NULL`
and populated there is nothing to backfill at any table size. What actually expires is narrower —
once a second source has overwritten a first, the destroyed figures are unrecoverable, because the
upsert replaced them in place and no history of prior values exists. The real deadline was therefore
the **second source system**, not the first fact. FIN-070B recorded that correction; V118 acts on it
early anyway, because it costs one index swap and removes a class of silent loss permanently.

**ADR-003 is amended in substance and not overturned.** "A temple's figure for a day is one figure"
becomes true after summing the temple's sources rather than at the row — arguably the more honest
reading, since two systems genuinely did report separately. **ADR-003's text was deliberately not
edited by FIN-052A:** amending an architecture decision record is a governance act, and the task's
own instruction was to explain such a change rather than make it. It needs the architect's amendment.

**`RevenueNormalizer.GrainKey` needed no change**, and that is not luck. Normalization runs over one
batch, and a batch has exactly one temple and exactly one source system, so both are constant across
every key it builds — which is why the in-memory key has six fields against the constraint's eight and
the two still agree. A future change letting one normalization run span batches would have to add both.

**`source_system_id = VALUES(source_system_id)` was removed from the upsert's update list.** Once the
column is part of the key, a matched row necessarily already holds the value being written, so the
assignment is a no-op. Before V118 it was the mechanism by which one source took ownership of
another's figures.

**What this does not do.** It recovers nothing already overwritten. It does not make the platform
multi-source-capable: `uk_ftc_temple_capability (temple_id, capability)` and
`uk_fsd_temple_service (temple_id, service_code)` carry the same single-source assumption and stay
deferred under FIN-070B D9, because neither is a mechanical widening — each forces an unanswered
question about what a temple-level answer means when two sources disagree.

Verified on MySQL 8.0 by `FinanceCanonicalRevenueMigrationTest$SourceSystemGrain` (5 tests, including
the index definition read back from `information_schema` and a check that no column was relaxed) and
by `RevenueLoadStageTest` (2 tests through the real loader). `RevenueReconciliationStageTest`'s
two-source scoping test was moved onto a *single* shared day — it had been forced onto separate days
by this very defect, on a day the old seven-column grain could not have kept apart. TiDB is not verified.

---

## FIN-D-068 — The aggregate table, and four deliberate departures from the plan that designed it

**Date:** 2026-09-22 · **Affects:** `fin_agg_revenue_period`, V119, FIN-070B §11.1, ADR-011

FIN-070 builds ADR-011's precomputed aggregate. FIN-070B specified its columns; four of them changed
once the code was written, and each change is a narrowing, not an addition.

**No `availability` column.** ADR-011 asks for one so "the API never has to infer it". Carrying it
would mean reading `fin_temple_capability`, whose `uk_ftc_temple_capability` is
`(temple_id, capability)` and cannot describe two sources for one temple (limitation 67, open
decision D9). An aggregate row *is* per source. Writing a per-temple answer into a per-source row
would bake the unresolved conflict into published data, where it would be discovered by a reader
rather than by a developer. The reporting layer joins the capability itself, and pays a join.

**No `contributing_batch_ids` column.** FIN-070B proposed `VARCHAR(500)`. A truncatable list of ids
is ADR-011's single `sync_batch_id` defect one size larger — it works until a period has enough
batches, and then it silently holds a prefix. The batches are derivable whenever they are wanted
from `FinRevenueFactRepository.findContributingBatchIds`, which is where `ReconciliationGate` already
gets them.

**`net_amount` is generated by the database**, where FIN-070B said it should be a stored column.
FIN-070B expected net to depend on a capability lookup ("store it only when `CANCELLATION` is
`AVAILABLE`"). It does not need one: if the aggregator nulls `cancelled_amount` whenever *any*
contributing fact did not record cancellations, then plain SQL NULL propagation gives exactly the
right answer, and the database can own the arithmetic as it does in V112. This removes a dependency
rather than adding one, and it is why the aggregator needs no repository at all.

**No `fin_agg_run` table yet.** A run record has no content until something orchestrates runs, which
is FIN-072. An empty audit table invites a reader to trust it.

**What was kept exactly as FIN-070B decided it.** The grain
`(temple_id, source_system_id, period_type, period_key, category_id, payment_mode)`, every column
`NOT NULL`; totals computed at read time rather than stored as nullable-category rows, because MySQL
and TiDB do not constrain a NULL in a unique index and each run would insert another total (the
FIN-D-018 defect one layer up); `FINANCIAL_YEAR` and `MONTH` only, no `DAY`; and months inheriting
their parent year's verdict, recorded on the row, because reconciliation writes no month-scoped
evidence.

**The write path requires a verdict but does not fetch one.** `RevenueAggregationWriter.write` takes
a `ReconciliationGate.Decision` as an argument and refuses to write without a publishable one, and
refuses aggregates outside its scope. It holds no reference to the gate and asks it nothing —
deciding *when* to recompute a period is FIN-072's, and building that here would be building the
trigger this task must not build. Requiring rather than calling threads both constraints: existing in
this table is what publication means, so a writer callable without a verdict is one forgotten call
away from publishing figures reconciliation does not stand behind.

**One aggregation rule is not symmetrical, on purpose.** An unknown `transaction_count` on any
contributing fact makes the period's count NULL, following FIN-D-053 — a count exists to check
agreement against a source's own total, and a floor cannot do that job. An unknown `gross_amount`
does *not* null the period; it is summed from what was recorded and `facts_with_unknown_gross` says
how many facts contributed nothing. Nulling a year's revenue because one fact of fifty lacked an
amount would hide real money, which is the worse error of the two.

Verified by 82 tests: 66 that need no database, and 16 in `RevenueAggregatePersistenceTest` against MySQL 8.0 with the real migrations. All five required mutations were applied and killed, including the two that need a database — an accumulating upsert and a `uk_farp_grain` without `source_system_id`. TiDB is not verified, as for every migration in this project, and no performance figure has been measured.

---

## FIN-D-069 — FIN-071 is blocked: no canonical fact has ever carried a `service_id`

**Decision:** FIN-071 (`fin_agg_revenue_service`) is moved from `NOT_STARTED` to **BLOCKED**, and no
migration, entity or aggregator is written for it yet. Building it now would create a table that
cannot receive a row.

**The finding.** `fin_revenue_fact.service_id` is nullable by design — V112 says *"NULL where revenue
is not a service (e.g. a donation box)"* — but in practice it is not merely sometimes null, it is
**always** null. Three independent checks:

1. `RevenueNormalizer` has exactly one construction site for a normalized row, and it passes a
   literal `null` for `serviceId`. Nothing else in the pipeline sets the field; `serviceId` is
   threaded through `Row`, `GrainKey`, `RevenueNormalizationStage` and the upsert parameter list, but
   the value being threaded is always that `null`.
2. **`fin_service_dim` has no repository.** No main-source file references the entity except its own
   declaration, so nothing can look a service up even if it wanted to.
3. **No migration inserts a single `fin_service_dim` row**, and no test ever sets a non-null
   `serviceId`. The dimension is empty everywhere it has ever existed.

The category on a fact comes from the mapping rule's canonical category code, not from a service —
`categoryIdsByCode.get(canonicalCategory)`. Service resolution is not a step that exists.

**Why this was not visible earlier.** The plumbing is complete and the column, the index
(`idx_frf_temple_service`) and the grain stand-in (`grain_service_key`, FIN-D-018) all exist, so the
schema reads as though services were supported. FIN-070A §19 and FIN-070B §11 both specify FIN-071 in
detail without noting that its input is empty. **FIN-070B §11's rule — "facts with a NULL
`service_id` are not services and belong only in the period table" — is correct and, applied to the
current data, excludes every fact that exists.**

**What FIN-071 actually needs first:** a service-resolution step — seeding `fin_service_dim` from a
source's service list, a `MappingRule` target that resolves a source service code to a
`fin_service_dim.id`, and a repository to do the lookup. That is a mapping feature of roughly
FIN-054's size, not a detail inside an aggregation task, and it is **not** created here.

**Two corrections to the documented FIN-071 column list, recorded now so they are not re-litigated:**

- **`rate_card_amount` should not be carried on the aggregate row.** FIN-070B §11 says to carry it as
  context. It lives on `fin_service_dim`, which the row is already keyed to by `service_id`, so it is
  one join away and needs no copy. Denormalizing it would freeze a mutable list price into a
  published row and go stale the moment the rate card changes — the identical argument FIN-070B used
  to *drop* `pct_of_total` (D5). Join at read time.
- **`avg_transaction_amount` should not be stored.** It is `gross_amount / booking_count` over the
  row's own inputs: one division at read time, and storing it forces a decision about what the
  average of a NULL gross or a zero booking count means, in a column whose name promises a number.
  Both operands are already on the row.

**Not decided here:** whether to build the service-resolution prerequisite, to scope FIN-072 to
period aggregates only, or to move to Phase 8. That is a sequencing call, and FIN-071 stays BLOCKED
until it is made.

---

## FIN-D-070 — The rebuild rebuilds years, not months, and asks the gate once per year

**Decision:** FIN-072's period scope is implemented as `RevenueAggregationRebuilder`, wired into
`FinancePipelineOrchestrator` as a sixth stage (`SyncStage.AGGREGATE`) per FIN-070A D7. Its unit of
work is one `(temple, source, financial year)` scope — the same triple `ReconciliationGate` decides
on, and the same triple `RevenueAggregationWriter` validates every row against.

**No distinct-months-per-batch query, contrary to FIN-070B section 12.** That document assumed months
would need their own affected-scope query. They do not: `RevenueAggregator.aggregate` emits a `MONTH`
candidate beside every `FINANCIAL_YEAR` one from the same facts, so rebuilding a year rebuilds the
months inside it by construction. The months a batch touched are always a strict subset of the years
it touched. Adding the second query would buy nothing and introduce a way for two scope calculations
to disagree — and when they disagreed, the months would be the ones left stale.

**Affected scopes, never a time window.** `findFinancialYearsBySyncBatchId` returns the financial
years the batch's facts actually landed in. A batch's change window says only when rows were modified
in the source; one incremental batch routinely carries corrections to three different years (ADR-006),
and a window-derived scope would silently skip the ones it failed to guess.

**Each year is gated independently, and a blocked year is skipped rather than fatal.** The gate is
asked inside the loop, per year. Hoisting it — evaluating once and reusing the verdict — is the
obvious-looking optimisation and is wrong: a batch touching three years can be publishable in two of
them. A blocked year writes nothing, leaves its previous rows exactly as they were, and is named in
`Result.blocked()` so an operator sees *which* year was withheld rather than a count. The remaining
years still rebuild; one unverifiable year must not block eleven good ones.

**Aggregation runs unconditionally after reconciliation**, not conditionally on the batch's
reconciliation outcome, for the same reason: the batch-level outcome is one boolean over a set of
years that can legitimately disagree. The per-year gate call is the only place with the scope to
decide correctly.

**`ReconciliationGate` is registered as a Spring bean for the first time here.** FIN-061 built the
class and nothing ever constructed it — no caller meant no bean. It is now an explicit `@Bean` in
`SyncWorkerConfig` like every other worker collaborator (FIN-D-008), which also makes its
`@Transactional(readOnly = true)` effective; a hand-constructed instance would silently lose the proxy
and the annotation with it. This closes the shape of limitation 52.

**No rerun API, no button, no override.** The rebuilder is reachable only from the orchestrator, on
the sync worker profile. It is not a correction mechanism and its javadoc says so in those words: a
rebuild recomputes aggregates from the facts as they currently stand, so a wrong fact is rebuilt just
as wrong, deterministically, every time. It re-runs no extraction, validation, mapping or load, and
writes, updates and deletes no `fin_revenue_fact` row.

**Transactions are per scope, not per batch.** Each year's write is already all-or-nothing inside
`revenueAggregationWriter` (`PROPAGATION_REQUIRES_NEW`). Wrapping the loop in a second transaction
would make one blocked or failing year roll back the years that had published cleanly.

---

## FIN-D-071 — Finance reporting reads are gated by CAN_READ_FINANCE_CONFIG, not IS_DC_ROLE

**Decision:** `DcFinanceController` (FIN-081/082/083) is route-guarded with
`RoleConstants.CAN_READ_FINANCE_CONFIG` (`SUPER_ADMIN`, `DISTRICT_COLLECTOR`, `DC_STAFF`,
`AUDITOR`), not `IS_DC_ROLE` (which excludes `AUDITOR`) as API_CONTRACT.md §0 states.

**The discrepancy.** API_CONTRACT.md's one-line route guard mention predates FIN-070A's isolation
test matrix (§15.4), which explicitly requires: *"Auditor: An AUDITOR with a null districtId reads
statewide and is not refused by the district guard"* and *"Unauthorized role: VIEWER /
TEMPLE_AUTHORITY get the documented refusal."* Read together, these only make sense if `AUDITOR`
is an authorized reader and `VIEWER`/`TEMPLE_AUTHORITY` are not — exactly `CAN_READ_FINANCE_CONFIG`,
and not `IS_DC_ROLE`.

**Why the plan wins over the contract here.** `CAN_READ_FINANCE_CONFIG` already admits `AUDITOR`
for mapping configuration (FIN-054A) — the rules that decide which category a temple's income is
classified under. Excluding the same reader from the figures those rules produce would be the more
surprising inconsistency, and the plan's test requirement is specific and reasoned where the
contract's mention is a single unexplained word.

**District scope**, implemented exactly as `MappingAdminServiceImpl.outOfScope` already does it:
only `DISTRICT_COLLECTOR` and `DC_STAFF` are checked against `JurisdictionGuard.assertDistrictScope`.
Calling it unconditionally for every role would treat an `AUDITOR`'s legitimately null `districtId`
as a corrupted token — `assertDistrictScope` only exempts `SUPER_ADMIN`, `TEMPLE_AUTHORITY` and
`VIEWER` by role, and `AUDITOR` is not in that list, so calling it for `AUDITOR` throws
`IllegalStateException` on every single request. This is a real defect in `JurisdictionGuard`
itself, not touched here — FIN-081/082/083 route around it exactly as FIN-054A already does,
consistent with the instruction not to modify shared security infrastructure without a verified
compile-breaking need.

**API_CONTRACT.md's route-guard line is corrected** to name `CAN_READ_FINANCE_CONFIG`, since it is
one of the four documents this project keeps current alongside the code it describes.

**Not decided here:** whether `JurisdictionGuard.assertDistrictScope` itself should be fixed to
exempt `AUDITOR` directly, which would let every future caller drop the same routing-around this
decision and FIN-054A both needed.

---

## FIN-D-072 — The real dashboard is smaller than the static mockup, on purpose

**Decision:** FIN-091's `FinanceDashboardTab` renders exactly what FIN-081/082/083 can serve —
capabilities, gross/net revenue, receipt count, expenditure, revenue by year/month/category, and
reconciliation checks. It does **not** reproduce the static mockup's DC-approved-funds section,
ongoing-works list, precious-metal donation charts, special-seva list, or cash-payment gauge.

**Why.** Every one of those sections in `temple-300001-dashboard.html` was fabricated or assumed
data with no backing table or endpoint: government funds and ongoing works were invented figures
with a "sample photo" placeholder graphic; the precious-metal charts multiplied a real item count
by `ASSUMED_VALUE_PER_ITEM_RS = 12000` and `ASSUMED_WEIGHT_PER_ITEM_GRAMS = 15` — constants the
temple's own source data was never asked to justify; the special-seva amounts and the cash-share
gauge had no `payment_mode` breakdown endpoint behind them. Reproducing any of that in the real
dashboard, even styled identically, would be the exact "invent financial numbers" and "estimate
metal value without reliable data" failures this platform exists to refuse. FIN-093 removes both
named constants entirely, and nothing replaces them — a chart with no data behind it is not drawn.

**The two false caveats FIN-093 named are corrected by omission, not by relabeling.** The static
page said gold/silver weight is only an assumption ("value and weight use flat assumptions...not
real appraisals") — false: Kollur's `PRECIOUS_METAL_WEIGHT` capability is declared `AVAILABLE`
(real gram weights, `HKanikeItems.Qty`), only unwired because FIN-023 scoped the source-of-truth
declaration to `REVENUE_AMOUNT` alone (HANDOFF limitation, still open). It said revenue is "by
financial year only, never by month" — false: FIN-081's `/revenue/monthly` is exactly a real
monthly figure, not an illustrative split of a yearly total. The dashboard's capabilities panel
now shows `PRECIOUS_METAL_WEIGHT: AVAILABLE` truthfully, and a DC can see it is available even
though no chart exists for it yet — which is honest, and different from claiming a value that was
never real.

**Building a precious-metal, DC-funds or Nirantara chart is FIN-110/FIN-120/FIN-130's job**, each
requiring its own aggregate table and reporting endpoint, not a frontend addition against data that
does not exist. Nothing here should be read as those tasks being smaller than scoped.

**The financial-year picker is buttons drawn from `revenue/trend`'s own years, not a free-form
selector.** A control offering a year the backend has never aggregated would be a promise the
dashboard cannot keep; the trend endpoint is the one source of truth for which years exist.

---

## FIN-D-073 — Onboarding readiness is computed, never stored

**Decision:** `GET /finance/source-systems/{id}/readiness` recomputes every finding on every call.
No table, column or cache holds a verdict.

**Why.** A readiness verdict is a pure function of configuration that an administrator is actively
editing — a mapping rule saved a minute ago changes it. A stored verdict is therefore wrong from
the moment the next edit lands, and the one thing this feature must never do is tell somebody a
configuration is safe to switch on because it was safe earlier. The response carries `evaluatedAt`
so the reader can see how fresh the answer is, and the client sets `keepUnusedDataFor: 0` so the
cache cannot reintroduce the problem the backend refused to create.

**Consequence.** Readiness cannot be queried historically, and no report can say "this source was
ready on the 3rd". Nothing described by slice 140-A needs that; a slice that does should persist an
*activation decision* with its verdict attached, not start storing verdicts continuously.

---

## FIN-D-074 — One finance source system per temple, refused at registration

**Decision:** registering a second source system for a temple is refused with a 422 naming decision
D9. `uk_ftc_temple_capability` and `uk_fsd_temple_service` are **not** widened. D9 stays open.

**Why.** Both unique keys are per temple rather than per source, and — the part that matters more
than the constraint — `FinTempleCapabilityRepository` has no finder that filters by source system,
so the capability read path resolves a temple's answer without ever considering which source gave
it. Widening the keys would leave every capability read needing a rule for whose answer wins when
two sources disagree, and no such rule exists because the situation has never had to be modelled.

FIN-D-068 already rejected a smaller version of this: *"Writing a per-temple answer into a
per-source row would bake the unresolved conflict into published data, where it would be discovered
by a reader rather than by a developer."* Refusing costs one guard and is reversible in a commit;
widening is not reversible once figures have been published under it.

**Consequence.** A temple whose source system is being replaced must have the old one retired
first. The readiness validator also reports `MULTIPLE_SOURCE_SYSTEMS_FOR_TEMPLE` as a blocker, so a
database that already contains two — which nothing prevents at the schema level — shows the
limitation where it bites rather than as a constraint violation mid-run.

---

## FIN-D-075 — The credential alias is write-only, and absent from the response type

**Decision:** `credentialRef` can be set and cleared through the onboarding API and is returned by
nothing. `SourceSystemDetailResponse` carries `credentialRefSet: boolean` and **has no field for
the value**. Audit detail records `credentialRefSet=true|false` and never the alias.

**Why.** The alias is not itself a secret — it is a lookup key the sync worker resolves from its own
environment (FIN-D-009). But it names the environment variable holding the secret, which is the
first thing worth knowing in order to go looking for one, and `API_CONTRACT` section 7.5 already
excluded it from every finance response. Masking would have been the obvious compromise and is
weaker: a masked field still exists on the record, and the next serialiser, log line or
`toString` added by somebody who did not read this decision will carry it.

**Consequence.** A caller cannot echo the current alias back on update, so
`UpdateSourceSystemRequest` treats it as tri-state: omitted keeps, a value replaces, an empty string
clears. Section 7.5 of the API contract is amended to scope its exclusion to reporting endpoints,
since the administrative endpoint must necessarily accept the field it refuses to return.

---

## FIN-D-076 — Onboarding is SUPER_ADMIN only, unlike the Source Mapper

**Decision:** every endpoint on `FinanceOnboardingController` is `RoleConstants.ADMIN_ONLY`,
including the reads. The Source Mapper's `CAN_READ_FINANCE_CONFIG` (four roles) is deliberately not
reused, and a separate controller carries the stricter annotation rather than overriding a looser
class-level one.

**Why.** A mapping rule says what a source *value* means. These rows say **which external database a
temple's published figures come from**, and the detail response carries the connector bean, the
source database name and whether a credential is configured — the operational topology of an
integration. `SourceSystemSummaryResponse`, which four roles can already read, excludes all three
for exactly that reason, and its javadoc says so. The nearer precedent is
`SystemConfigServiceImpl.update`, a state-level configuration write that is `ADMIN_ONLY`.

Two controllers rather than one because the stricter rule must not depend on every future method
remembering to override the looser class annotation.

**Consequence.** A District Collector cannot see or register their own temples' source systems in
this slice. If that turns out to be needed, the change is one annotation plus a decision about which
fields a district-scoped caller may see — and `SourceSystemAdminServiceImpl` already carries the
`outOfScope` jurisdiction check, unused today, so widening does not silently leak across districts.

---

## FIN-D-077 — Readiness has three values, and NOT_READY is not one of them

**Decision:** `ReadinessStatus` is `READY | WARNING | BLOCKED`, used for both a single finding's
severity and the whole report's verdict. Whether a source may be switched on is a separate boolean,
`activationAllowed`.

**Why.** The four labels the task brief named — READY, NOT_READY, BLOCKED, WARNING — mix two
different things. `WARNING` grades one finding; `READY` grades a report. And `NOT_READY` and
`BLOCKED` name one state: a configuration with an unresolved blocking finding is not ready, and one
that is not ready is blocked by something. Carrying both would leave every future writer choosing
between two spellings of the same fact and every future test asserting one of them. A separate
severity enum would have held the same two write-values under different names, which is the
duplication the brief asked to avoid.

**Consequence.** `statusOf` folds findings to the worst severity present, so a report's verdict can
never disagree with its own contents — which a separately-stored status field could.

---

## FIN-D-078 — Possible mapping ambiguity is a warning, never a blocker

**Decision:** two or more rule namespaces sharing one priority produces
`MAPPING_RULE_POSSIBLY_AMBIGUOUS` at `WARNING`. It never blocks activation.

**Why.** The mapping engine resolves by priority and returns `AMBIGUOUS` when two matches tie, but
two rules only tie if a single record carries both of their staged fields — and no registry table
records which fields a source emits together, so this cannot be established from configuration.

Blocking would refuse the first onboarded source's own configuration. `V114` set `SEVA_CODE:430` to
priority 200 and deliberately left the four `SANNIDHI` and two `STREAM` rules at 100, recording that
they *"do not overlap each other"*. A check that refuses a configuration whose author reasoned
through the exact question and wrote the answer down is a false positive, and false blockers are how
a validator gets switched off.

**Consequence.** A genuine ambiguity still reaches the pipeline and is rejected there, per row, as
it was before. What onboarding adds is the earlier, weaker signal — with a message that names the
namespaces and the priority, and says when it can be ignored.

---

## FIN-D-079 — Capability declarations are created and revised one at a time, not as a matrix

**Decision:** FIN-140-B implements `POST` and `PUT` on a single declaration.
`FIN-140_ONBOARDING_PLAN.md` §5 proposed a whole-matrix `PUT` instead; that is superseded.

**Why.** The plan's argument was atomicity — *"a capability matrix read half-updated would misreport
a temple"*. Per-row writes give that anyway: each declaration is its own row, its own transaction
and its own unique-key slot, and readiness recomputes from whatever exists rather than from a
snapshot that had to be written all at once.

What per-row writes give in addition is a per-row audit line and a per-row optimistic lock. A matrix
upsert would have had one version for nineteen rows, so two administrators editing two different
capabilities minutes apart would have collided over changes that never overlapped — and an audit
entry saying "the matrix changed" is not evidence anybody can act on.

**Consequence.** Declaring a source system fully is nineteen calls rather than one. The screen makes
that ordinary by listing the whole catalogue with a control on each row, and the cost falls on a
one-time onboarding rather than on a routine edit.

---

## FIN-D-080 — An undeclared capability is a finding, and the whole catalogue is offered

**Decision:** readiness reports `CAPABILITY_NOT_DECLARED` — a **warning**, naming every capability
nobody has declared. The administrative screen lists all of the canonical catalogue rather than only
what exists, and the catalogue is served by the API rather than held as a frontend constant.

**Why.** An undeclared capability and one declared `NOT_AVAILABLE` are indistinguishable to every
reader downstream, and they are different statements: the second says the source does not record
this, the first says nobody has looked. `V111` declared all nineteen for the first onboarded source
precisely so that *"'not declared' never has to be guessed at"* — until now that was a convention
one migration happened to follow, with nothing to make the next onboarding follow it.

A warning rather than a blocker because a half-configured source is a legitimate overnight state,
and because the dashboard already renders an absent capability honestly. One finding listing all of
them rather than one per capability, because nineteen warnings on a freshly registered source would
bury the blocking findings underneath them.

**Consequence.** A source reaches `READY` only once every capability has been declared, including
with `NOT_APPLICABLE` where the question does not arise. The FIN-140-A test fixtures were updated to
declare the full matrix rather than one capability, which is the standard the real configuration
already met.

---

## FIN-D-081 — Write validation is stricter than readiness, deliberately

**Decision:** the API refuses a declaration that is `NOT_AVAILABLE`, `PARTIALLY_AVAILABLE` or
`NOT_APPLICABLE` with no reason, and one whose coverage window ends before it begins. Readiness
keeps the same two checks.

**Why.** These are not duplicate rules with one owner; they answer different questions. Readiness
grades rows that already exist — including the nineteen a migration seeded, which no API validated
and which must still be assessed. Write validation stops this API creating a row that readiness
would immediately fault. Saving something known to be wrong and then reporting it wrong on the next
screen is a worse experience than refusing it with the same sentence at the point of edit.

**Consequence.** A row can exist that the write path would now refuse — any row predating the API.
That is intended, and it is why the readiness check was not removed when the write check was added.

---

## FIN-D-082 — Capability administration extends the source system service rather than adding one

**Decision:** `SourceSystemAdminService(Impl)` gained the capability operations.
No `CapabilityAdminService` was created.

**Why.** A declaration is reached through its source system, scoped by the same jurisdiction rule,
audited the same way and refused the same way. A second service would have had to copy
`requireInScope`, `outOfScope`, `currentClaims` and the audit helper — roughly sixty lines of
security-critical code, in two copies that would drift, with the failure mode being a scope check
that silently stopped matching.

**Consequence.** The class is larger. That is the trade, and it is the same one
`MappingAdminServiceImpl` already makes for rules, unresolved values and namespaces. If it grows
again for 140-C, the split worth making is by aggregate rather than by endpoint group — and it
would still have to share the scope helpers rather than copy them.

---

## FIN-D-083 — `fin_source_of_truth_decl` gets no `@Version` column, and no migration

**Decision:** FIN-140-C adds no schema change. The entity keeps its domain `version` and gains no
JPA optimistic lock, unlike `fin_source_system` (V120) and `fin_temple_capability` (V121).

**Why.** Those two tables are edited in place, so an optimistic lock guards the thing that
actually happens to them. This table is append-only: a change is a new row, and the only in-place
write is setting `effective_to` on the version being superseded. A lock on that row would guard a
field the writer is the only one touching, in the same transaction that inserts its replacement.

What the race actually needs guarding is *version assignment*, and the schema already does it:
`uk_fsotd_source_metric_version` is `(source_system_id, metric, version)`, so two transactions
that each compute "version 2" cannot both commit. Adding a `@Version` column would not have
caught that race — both administrators read version 1 cleanly — while looking as though it had.

**Consequence.** Two of three onboarding tables carry an optimistic lock and one does not, which
reads as an inconsistency until the reason is known. It is recorded here and in the entity, and
the asymmetry is the point: the guard matches the write.

---

## FIN-D-084 — The caller states which version they are superseding

**Decision:** `POST …/source-of-truth` takes `supersedesVersion` — the version the caller believes
is in force, or absent when they believe none is. A mismatch is a 409.

**Why.** The database constraint catches two genuinely simultaneous writes. It does not catch the
common case: two administrators who open the screen minutes apart, both read version 1, and both
declare a replacement. The first commits version 2; the second computes version 2, fails the
constraint — or, if the first had already finished and the second re-read, silently supersedes a
version it never saw. On a row that decides which column a temple's published revenue comes from,
"somebody else changed this, reload" is the only honest answer.

This is the same explicit-version-check convention the rest of finance already follows rather than
leaving the question to Hibernate, and for the same reason: Hibernate's own check passes for both
writers here, because neither is overwriting the row they read.

**Consequence.** A client cannot declare blind. It has to read the current state first, which is
what the screen does anyway.

---

## FIN-D-085 — `effectiveTo` is derived, and `effectiveFrom` may not be in the future

**Decision:** the request carries no `effectiveTo`. Superseding sets the previous version's
`effectiveTo` to the new version's `effectiveFrom`, and a new version may not begin before the one
it closes. `effectiveFrom` in the future is refused.

**Why.** Two inconsistencies become unrepresentable rather than validated. A caller who could send
both dates could close a window before it opened, or leave a gap in which no declaration was in
force and the pipeline would refuse every row without anything on the screen saying why.

The future-date rule is the subtler one. Nothing compares `effective_from` to today — the pipeline
asks only whether `effective_to` is null — so a future-dated version would take effect the moment
it was saved while its own dates claimed otherwise. That is a silent restatement, which is the
failure ADR-008 exists to prevent, arriving through the feature meant to prevent it.

**Consequence.** A change cannot be scheduled ahead of time. Declaring on the day it starts
applying is the workflow, and a scheduled change would need a reader that honours the date
— which is a pipeline change, not an API one.

---

## FIN-D-086 — Sign-off is the two existing columns and a checkbox, not a workflow

**Decision:** `approved` on the create request sets `approved_by` and `approved_at`. There is no
approval state machine, no pending state, and readiness does **not** fault an unapproved
declaration.

**Why.** D-140-1 already settled that no separate workflow would be introduced, on the grounds
that ADR-008 asks for sign-off exactly here and this table already has the columns. What remained
was how they get set, and the smallest answer that keeps the distinction alive is a flag: the
declaration applies either way, and whether anybody has confirmed it is separate information.

That distinction is not hypothetical. Both declarations for the first onboarded source carry null
approval deliberately — their evidence is inference from a filter predicate, and the source has
never been reachable to confirm it. Auto-approving whatever an administrator saves would erase
that state and, with it, the reason V115 was written the way it was.

**Consequence.** An unapproved declaration is in force and readiness says nothing about it. That
is a real gap and it is deliberate for now: making it a warning would change the first onboarded
source's readiness verdict, which this slice was told to preserve.

**Proposed, requires approval:** a `SOURCE_OF_TRUTH_UNAPPROVED` readiness **warning** naming
declarations in force with no sign-off. It would add two warnings to the first onboarded source
immediately — correctly, since both are awaiting confirmation — and that is a change to existing
readiness behaviour rather than an addition, so it is not being made without a decision.

---

## FIN-D-087 — Activation is `sync_enabled`, and the request carries a desired state

**Decision:** FIN-140-D adds no column, no status enum and no lifecycle field. It sets the
existing `fin_source_system.sync_enabled` through one endpoint taking `{enabled, reason}`.

**Why.** The onboarding plan's own state model already assigned the concept: *"Live — may the
platform contact the source? — `fin_source_system.sync_enabled` — no, already exists, already
defaults 0."* Its option C, a finance-local `onboarding_status` column, was rejected at planning
time as *"precisely the third status vocabulary `WorkflowStatus` was created to eliminate"*, and
nothing has changed to reopen that. A second field would also have needed a rule for what to do
when the two disagreed.

One endpoint for both directions rather than `/activate` and `/deactivate` because the state is a
boolean and the caller states the outcome they want. That is what makes it idempotent: two
administrators who both want it enabled produce one enabled source and one audit line.

**No `version` on the request**, unlike the source system update. An optimistic lock there
protects a dozen fields where a lost update silently discards an edit. Here a concurrent write
either wanted the same outcome or is a later decision that should win, and requiring a version
would turn a harmless repeat into a 409 — the opposite of idempotent.

**Consequence.** A caller cannot detect that somebody else flipped the switch between their read
and their write. `changed` tells them whether *their* call did anything, which is the question
they can act on.

---

## FIN-D-088 — Enabling is gated on readiness; disabling never is

**Decision:** enabling requires zero blocking readiness findings, recomputed inside the
transaction by the existing validator. Disabling is always permitted, but requires a reason. A
no-op short-circuits before the readiness check.

**Why.** Readiness is the gate the whole slice exists to enforce, and restating its rules here
would create a second copy whose failure mode is a source enabled against a check that would have
refused it. Warnings do not block: they are legitimate configuration somebody had a reason for —
undeclared capabilities overnight, equal-priority mapping rules that genuinely never overlap — and
a gate that refused them would make the warning level meaningless.

Disabling is ungated because a switch that only turns on is not a switch. The moment an
administrator most needs to turn a source off is the moment its configuration has gone wrong,
which is exactly when readiness would refuse them.

The no-op ordering matters for the same reason. A source enabled last month whose configuration
has since drifted must not fail a call that changes nothing; refusing there would tell an
administrator their unchanged system is broken at the one moment they were not touching it.

**Consequence.** A source can be enabled and subsequently become `BLOCKED`. That is not corruption
and is not hidden: readiness is recomputed on every read and the screen shows both. Activation
asserts readiness *at the moment of enabling*, and cannot assert anything about afterwards —
configuration is editable at any time, so no design could.

---

## FIN-D-089 — Sign-off warns at the moment of activation, and still does not gate

**Decision:** the activation response lists source-of-truth declarations in force with no
`approved_at`, as a warning. FIN-D-086's proposed readiness finding is **still not implemented**,
and approval is **not** an activation precondition.

**Why.** FIN-140-C left the gap deliberately: making approval a readiness finding would change the
verdict on the first onboarded source, whose two declarations are unapproved on purpose. That
argument still holds, so the validator was not touched and no existing verdict moved.

But enabling is the moment the question actually bites. It is when somebody decides the platform
may read a temple's revenue from a field nobody who runs that source has confirmed. A warning on
the response says so, at the point of decision, without changing what readiness means anywhere
else.

**Consequence.** Approval remains advisory. A source whose declarations are entirely unconfirmed
can be enabled by an administrator who reads the warning and proceeds — which is the correct
outcome while sign-off has no owner, and is why this is recorded rather than assumed.

**Still open, still requiring approval:** whether unsigned declarations should become a readiness
warning (FIN-D-086) or an activation blocker. Either changes existing behaviour and neither should
be adopted silently.

---

## FIN-D-090 — The generic JDBC connector lives in the worker package, not the contract package

**Decision:** `JdbcTableConnector` and its supporting types are in
`com.templeregistry.service.finance.sync.jdbc`, not in `com.templeregistry.connector.finance`.

**Why.** `ConnectorContractPurityTest` scans every source file in the contract package and fails on
`java.sql`, `javax.sql`, `DataSource`, `ResultSet`, `PreparedStatement` and
`org.springframework.jdbc`. That is not incidental strictness: the contract is deliberately
transport-free so that `PULL_JDBC`, `PUSH_AGENT`, `SOURCE_API` and `FILE_DROP` stay equally
implementable, and across a hundred government temples an inbound connection is frequently refused
as policy rather than capability. A JDBC implementation placed in that package would either fail
the test or force it to be weakened, and the second is how an architecture quietly stops being one.

The contract already said where implementations go — *"Implementations live in the worker; this
contract is visible to both runtimes"* — and `SyncWorkerConfig` already said this class would be
registered there as an explicit `@Bean`. Both were followed rather than reinterpreted.

**Consequence.** The connector cannot be referenced from the registry runtime even by accident,
because the package is only assembled under the `sync-worker` profile. A new structural test
asserts the contract package still contains no JDBC, so the next implementation faces the same
fork deliberately rather than by surprise.

---

## FIN-D-091 — Read-only is structural: the connector has no API that accepts SQL

**Decision:** `JdbcTableConnector` composes every statement itself from validated identifiers and
bound parameters. There is no method taking a query, a predicate or a fragment. `setReadOnly(true)`
is set as well, and is explicitly the weaker of the two defences.

**Why.** A filter that rejects `INSERT`, `UPDATE`, `DELETE` and DDL is a blocklist, and blocklists
on SQL lose — to comments, to nested statements, to vendor syntax nobody thought of. Making a write
*unrepresentable* has no such failure mode: there is nowhere to put one. `setReadOnly` alone would
not do, because it is a hint several drivers ignore, which is why the connector logs when a driver
declines it rather than trusting it.

The one place configuration contributes text to SQL is a table or column name, because JDBC has no
placeholder for an identifier. Those go through `SqlIdentifier`, which is an allow-list — "is this
a plain identifier?" has one answer, where "can this string be made safe?" has an endless supply of
counter-examples.

**Consequence.** `fin_source_of_truth_decl.filter_predicate` is **not executed** by this connector.
It is free text an administrator wrote, and running it would reintroduce exactly the arbitrary SQL
this excludes. A source needing predicate filtering needs a database view or its own connector.
That is a real limitation of the generic path and is recorded rather than worked around.

---

## FIN-D-092 — Connection settings are worker configuration, not registry data

**Decision:** which table to read and where the database is come from
`trm.finance.jdbc.<system_code>.*` in the sync-worker environment, through
`JdbcSourceSettingsProvider`. No column was added to `fin_source_system`; no migration was written.

**Why.** The settings carry a JDBC URL. `fin_source_system` deliberately holds no host, port, URL,
database name, username or password (FIN-D-002), and the registry runtime must have no path to a
temple's connection details at all (ADR-001). Putting them in the registry database would give an
HTTP request a way to read where a temple's system lives — the precise thing the boundary exists to
prevent — and would have needed a migration to do it.

The shape deliberately mirrors `EnvironmentSourceCredentialProvider`, including the property
namespace guard: `system_code` comes from a database row, and a value like `..spring.datasource`
would otherwise let a configuration row read an unrelated property.

**Consequence.** Onboarding a simple source is now *configuration rather than a new Java class*,
which is what ADR-004 promised — but it is worker configuration plus registry configuration, not
the registry screen alone. Closing that gap means finding somewhere to hold a JDBC URL that the
registry process cannot read, which is an architectural decision rather than a refactor.

**Proposed, requires approval:** a worker-readable, registry-unreadable store for connection
targets, so that onboarding a JDBC source becomes a single administrative act. It is entangled with
Q5, and choosing one without the other would produce two mechanisms.

---

## FIN-D-093 — The connector reports source totals only when an amount column is configured

**Decision:** `sourceTotals` runs its own aggregate over the business-date axis when
`amount-column` is set, and returns `SourceTotals.notAvailable()` otherwise. It is never zero.

**Why.** The contract requires reconciliation's numbers to be independent of extraction's, or the
comparison marks its own homework. A separate statement on a separate axis satisfies that. What it
cannot do is *discover* which column holds the authoritative amount — that is what a source-of-truth
declaration records, and reading registry declarations from inside a connector would put semantic
configuration in the one layer ADR-004 keeps free of it.

Making it opt-in keeps the honest outcome available: a source with no amount column configured
reports "could not ask", which reconciliation maps to `NOT_AVAILABLE` rather than to a pass.

**Consequence.** The amount column is named twice for a JDBC source — once in worker settings for
reconciliation, once in a source-of-truth declaration for normalization — and nothing checks that
they agree. They answer different questions in different runtimes, but they can drift, and a
readiness check comparing them is the obvious follow-up. Recorded as a limitation rather than
patched over.

---

## FIN-D-094 — The first execution mode is manual, and automatic scheduling is deliberately deferred

**Decision:** `ManualSyncTrigger` starts a run only when it is called. No `@Scheduled` method exists
in the sync worker, `SchedulingConfig` is unchanged, `financeSyncScheduler` is given no job, and
nothing scans `sync_enabled` looking for work.

**Why.** The pipeline had never run. Six stages, a connector, an orchestrator and an activation
switch all existed and had been unit-tested in isolation, and no line of production code had ever
carried a row from a source system to a published figure. Turning that on with a cron expression
would mean the first end-to-end execution in the platform's history happening unattended, against a
government temple's live finance database, at 2am.

The order matters more than the delay. A manual run is observable: somebody is watching it, the
window is one they chose, and a failure is noticed in the minute it happens rather than in the
morning. Everything a scheduler needs to be safe — overlapping-run protection, back-off, a stale-batch
policy, a defensible extraction frequency for a temple's production server — is easier to design
against a pipeline that has demonstrably worked once than against one that has never executed.

**Consequence.** Nothing synchronises on its own, and `sync_enabled = true` still causes no traffic.
Automatic scheduling is its own slice, and it now has a working pipeline to schedule.

---

## FIN-D-095 — The trigger is a worker bean with no transport, and the registry gets no run endpoint

**Decision:** The manual trigger is `ManualSyncTrigger`, registered as an explicit `@Bean` in
`SyncWorkerConfig` under the `sync-worker` profile. No REST endpoint was added, to either runtime.

**Why.** The trigger is the one object that can, in a single call, reach a temple database and write
a canonical revenue figure. In the registry runtime that would put a temple's finance database one
HTTP request away, which is precisely what ADR-001 exists to prevent — so it cannot live there, and
`RegistryRuntimeContextTest` now asserts that it does not.

That leaves the worker, which is deliberately not a web application: `SyncWorkerBoundaryGuard` fails
startup if it ever comes up as one, because a process holding temple credentials and a network path
into temple estates should not also be listening on a port. Adding a web layer to give the trigger a
URL would have traded the strongest property this architecture has for a convenience.

And a registry endpoint that called the worker directly would need a registry-to-worker channel,
which does not exist. The only channel between the two runtimes is the shared database (ADR-001), so
inventing an HTTP one as a side effect of this slice would be an architectural change smuggled in as
plumbing.

**Consequence.** The trigger has no production caller yet. That is the honest state: the capability
is real, tested end to end, and reachable from the worker process — what is missing is the operator's
route to it. The two candidates are a CLI argument on the worker, and a database-backed request row
the worker reads (which would reuse the existing shared-database channel rather than adding one).
Choosing between them is a task, and it is named as one rather than decided here.

---

## FIN-D-096 — Duplicate runs are prevented by a database row lock, not by a check or a JVM lock

**Decision:** Batch creation takes `SELECT ... FOR UPDATE` on the source system's own row
(`FinSourceSystemRepository.findByIdForUpdate`), then validates and inserts inside that transaction.
A second request finds a `PENDING` or `RUNNING` batch and is refused with `ALREADY_IN_PROGRESS`.

**Why.** Deciding whether a run may start and inserting the batch that starts it are two statements,
and everything dangerous lives between them. Without a lock, two requests both read "nothing is
active", both insert, and the same window is extracted from a temple's database twice — survivable,
because the stages are idempotent and the grain's unique constraint refuses duplicate facts, but at
double the load on somebody else's production server and with two sets of counters describing one
window.

A `synchronized` block or a static lock would have been shorter and would have worked exactly until
the second worker instance was deployed, at which point it would have kept compiling, kept passing
its tests, and silently stopped protecting anything. The database is the only thing both processes
share.

A unique index would be better still, and MySQL cannot express it: the constraint wanted is *at most
one batch per source whose status is PENDING or RUNNING*, which is a partial index. Writing a
migration to approximate it — a nullable "active batch" column, say — would add schema and a second
source of truth for a state `fin_sync_batch.status` already records. No migration was written.

**Multiple worker instances.** Nothing prevents them and nothing has been tested with them. The
worker carries an `instance-id` property used in logs and thread names, which is the shape of a
design that expects more than one, but no deployment runs more than one today. The concurrency model
was chosen to be correct either way rather than to assume the current deployment — which is the same
reason `FinancePipelineOrchestrator.claimForRun` is a conditional update rather than a check.

**Consequence.** The lock is held across three small reads and one insert, and never across
extraction, so it does not serialise the runs themselves. Two *different* source systems run
concurrently without contention, which is the case that matters at a hundred temples.

The known gap: a worker killed between creating a batch and finishing it leaves that batch `PENDING`
for ever, and every later request for that source is refused. There is no reaper, deliberately —
deciding when an in-flight batch is abandoned rather than slow is a judgement that needs heartbeats
or lease expiry, and guessing at a timeout would eventually cancel a long historical load that was
working. Recorded as a limitation, with manual intervention (cancel the batch) as the current answer.

---

## FIN-D-097 — Readiness is recomputed at the moment of the run, and warnings still do not block

**Decision:** The trigger recomputes readiness with `OnboardingReadinessValidator` before creating a
batch, and refuses if any finding is `BLOCKED`. Warnings never block. The assembly that feeds the
validator moved out of the registry service into `OnboardingConfigurationReader` so that both
runtimes use one implementation.

**Why.** `sync_enabled` and readiness answer different questions, and both are required.
`sync_enabled` is a person's authorisation, recorded by FIN-140-D; it says nothing about whether the
configuration is still correct. Readiness is the configuration's coherence — and it is a *current*
property, not a fact about the day the source was switched on. In the interval a declaration can be
superseded, a mapping rule deactivated, or a canonical category retired, and each of those turns a
configuration that would have loaded correct figures into one that would not. Trusting the verdict
from activation time would mean publishing wrong numbers because nobody re-checked.

Warnings stay non-blocking for the reason FIN-140-D gave: a half-configured source left overnight is
a legitimate state, and a validator that refuses work over things that are merely probably wrong is a
validator somebody eventually bypasses.

**Why extract rather than copy.** The worker needed the assembly and it was a private method on a
registry `@Service`. Copying it would have created two definitions of what readiness reads, and they
would have drifted — producing a source that passes the screen an administrator is looking at and is
refused by the trigger, or the reverse, which is worse. One class, two callers.

**Consequence.** `FIN-D-086` is untouched: sign-off is still a warning at activation and is still not
a gate here. Kollur's readiness verdict is unchanged, because no check was added or re-graded.

---

## FIN-D-098 — A refused request writes no batch; only an attempted run does

**Decision:** All five refusals — worker disabled, no such source, not enabled, readiness blocked,
already in progress — throw `SyncRefusedException` and leave `fin_sync_batch` untouched. Only a
request that reaches the orchestrator produces a row.

**Why.** `fin_sync_batch` is the audit spine: one row per extraction *attempt*, retained
indefinitely, and the evidence behind every published figure. A refusal is not an attempt. Recording
one would put rows in that log describing reads that never happened, and would make the question an
operator actually asks — how often does this source fail? — unanswerable without first learning which
statuses mean "tried and could not" and which mean "was never going to try".

It also keeps `FAILED` meaning one thing: the platform reached for a temple's system and something
went wrong. That is an operational event somebody should look at. A source that is simply switched
off is not.

**Consequence.** A refused request leaves no trace in the database, only in the worker's log with its
`Reason` as the category. If refusals ever need to be counted, that is an operational log concern or
its own table, not a status in the batch lifecycle.

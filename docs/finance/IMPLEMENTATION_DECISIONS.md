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

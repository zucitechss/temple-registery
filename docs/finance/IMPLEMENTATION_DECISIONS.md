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

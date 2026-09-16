# Finance Platform — Handoff

**Updated:** 2026-09-16
**Branch:** `feature/db-integration`
**Read first**, then [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md),
[IMPLEMENTATION_TASKS.md](IMPLEMENTATION_TASKS.md),
[IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md).

---

## Current State

**What works.** Phase 1 is complete: the finance data foundation and the runtime boundary
that the whole architecture depends on.

*Foundation (FIN-010 … FIN-015).* Seven tables, seven entities, seven repositories, eleven
enums. The schema can record which external system feeds which temple, what each temple
can and cannot answer, which source field is authoritative for each metric, how source
values map to canonical ones, and the audit trail of every sync attempt and reconciliation.

*Boundary (FIN-016).* The registry and the finance sync worker are two runtimes built from
one artifact:

```
  --spring.profiles.active=prod          registry / web / API
  --spring.profiles.active=sync-worker   finance ingestion (non-web)
```

The registry runtime contains **no bean** that can resolve a temple credential or reach a
temple source system. That is asserted against the fully assembled application — all 39
controllers and 155 services — not merely against the configuration classes. The worker
runs without a web layer and refuses to start if it ever comes up serving HTTP.

**What does not work yet.** Nothing is connected to anything. There is no connector, no
extraction, no canonical fact table, no API, and the dashboard is still the static HTML
file it was. No row of temple financial data has been read or stored, and no credential
exists anywhere. This is expected: Phases 1 built the spine and the boundary, not the
pipeline.

---

## Last Completed Task

**FIN-016 — sync-worker profile split.** Verified: 32 new tests across 5 classes, all
passing; full suite shows no regression.

---

## Currently In Progress

Nothing is half-built. Every committed file compiles and is covered by a passing test.

---

## Next Task

There is no `FIN-017` in this numbering — Phase 1 ends at FIN-016.

**FIN-030 — define the `TempleFinanceConnector` contract and its supporting types.**

The worker now has a place for connectors and nothing to put in it. The contract is
self-contained: it needs no credentials, no network access and no Kollur knowledge, so it
is unblocked by both Q4 and Q5. It also gives `fin_source_system.connector_bean` something
real to name.

Register it, and every later connector, as an explicit `@Bean` in `SyncWorkerConfig` —
**not** as a `@Component`. `FinanceIntegrationBoundaryTest` will fail the build otherwise,
which is deliberate (FIN-D-008).

FIN-021 … FIN-024 (seeding Kollur configuration) are equally unblocked if configuration-first
is preferred; they are pure data and contact nothing.

**Do not start the Kollur connector (FIN-040+).** It is blocked on Q4.

---

## Files Recently Changed

**Modified (1)**
- `backend/src/main/java/com/templeregistry/TempleRegistryApplication.java` —
  `@EnableScheduling` removed; see `SchedulingConfig` for why.

**Added — configuration**
- `backend/src/main/java/com/templeregistry/config/FinanceProfiles.java`
- `backend/src/main/java/com/templeregistry/config/SchedulingConfig.java`
- `backend/src/main/resources/application-sync-worker.yml`

**Added — worker runtime** (`backend/src/main/java/com/templeregistry/service/finance/sync/`)
- `SyncWorkerConfig.java` — the only place worker beans are registered
- `SyncWorkerProperties.java` — `trm.finance.sync.*`, extraction off by default
- `SyncWorkerBoundaryGuard.java` — fails startup if the worker is a web application
- `SourceCredentialProvider.java` — the Q5 seam
- `EnvironmentSourceCredentialProvider.java` — interim environment-backed implementation
- `SourceCredentials.java` — redacting record
- `CredentialNotConfiguredException.java`

**Added — tests** (`backend/src/test/java/com/templeregistry/service/finance/sync/`)
- `SyncWorkerProfileBoundaryTest.java` (7)
- `FinanceIntegrationBoundaryTest.java` (6)
- `EnvironmentSourceCredentialProviderTest.java` (9)
- `SyncWorkerRuntimeContextTest.java` (5)
- `RegistryRuntimeContextTest.java` (5)

Earlier in this branch: `V110__finance_foundation.sql`, 11 enums, 7 entities, 7
repositories, `FinanceFoundationRepositoryTest`, and the documentation set.

---

## Database Changes

**None in FIN-016.** No migration, no schema change, no new column.

The worker profile explicitly disables Flyway and sets `ddl-auto: none`: the registry
runtime owns the schema, and two processes performing DDL against one database is a race
with no upside.

---

## APIs Added or Changed

None. No controller, no endpoint, no DTO. The contract is written
([API_CONTRACT.md](API_CONTRACT.md)) but unimplemented.

The worker adds no endpoint by construction — it has no web layer at all.

---

## Tests

| Command | Result |
|---|---|
| `mvn -o compile -DskipTests` | BUILD SUCCESS |
| `mvn -o test -Dtest=SyncWorkerProfileBoundaryTest` | 7/7 pass |
| `mvn -o test -Dtest=FinanceIntegrationBoundaryTest` | 6/6 pass |
| `mvn -o test -Dtest=EnvironmentSourceCredentialProviderTest` | 9/9 pass |
| `mvn -o test -Dtest=SyncWorkerRuntimeContextTest` | 5/5 pass |
| `mvn -o test -Dtest=RegistryRuntimeContextTest` | 5/5 pass |
| `mvn -o test -Dtest=FinanceFoundationRepositoryTest` | 11/11 pass |
| `mvn -o test` (full suite) | **898 run · 0 failures · 18 errors** — all 18 pre-existing |

Full-suite comparison: 866 errors-18 before any finance work → 888 after FIN-015 → 898
after FIN-016, with the **same 18 errors in the same 4 report files** throughout. No
regression at any step.

**What the boundary tests actually assert**

- The registry runtime, fully assembled, has zero beans of type `SourceCredentialProvider`,
  zero beans from `service.finance.sync` or `connector`, and no worker scheduler.
- The worker runtime is not a `WebApplicationContext` and registers no `DispatcherServlet`.
- The worker registers no `ScheduledAnnotationBeanPostProcessor`, so no registry background
  job runs in it — while `EmailDeliveryService` remains present and injectable.
- The registry still schedules its jobs, so moving `@EnableScheduling` broke nothing.
- Starting the worker as a web application fails with a clear message.
- No committed configuration file declares a `trm.finance.source.` property.
- No credential resolves unless explicitly supplied; a missing one throws.

**The classpath guard was verified by mutation**, not assumed: a temporary `@Component` was
added to the worker package, two tests failed with the intended messages, and the probe was
removed. A guard that cannot fail is not a guard.

---

## Known Problems

**1. FIN-X-001 — 18 pre-existing test errors, unrelated to finance.**

```
Schema-validation: missing column [field_names_json] in table [declaration_clarifications]
```

`DeclarationClarification` maps the column; `V1__initial_schema.sql:657` does not create it
and no migration adds it. It exists in running environments only because
`ddl-auto: update` creates it silently. Affects `ApplicationContextIntegrationTest` (1) and
all 17 `TrustIntegrationTest` cases. Confirmed pre-existing by stashing all finance code and
reproducing the identical failures. This is architecture risk **R7**, observed.

Recommended fix, owned by the declaration module:
`ALTER TABLE declaration_clarifications ADD COLUMN field_names_json JSON NULL;`

**2. FIN-X-002 — the `test` profile cannot boot a full application context.**
Found while building the FIN-016 tests. Two independent causes, both pre-existing:

- `src/test/resources/application-test.properties` points `app.jwt.public-key-path` at
  `classpath:jwt-test.pub`, which is a placeholder (`...Qw1Qw1Qw1...`), not a real RSA key.
- `application.yml` sets `connection-init-sql` to the TiDB-only
  `SET tidb_enable_noop_functions=1`, which H2 rejects.

Both were invisible before because every existing full-context test fails earlier on
FIN-X-001. `SyncWorkerRuntimeContextTest` and `RegistryRuntimeContextTest` override both
locally via `@TestPropertySource`, adding no key material and changing nothing in
production configuration. Worth fixing centrally — the project has no working full-context
test on the `test` profile.

**3. Namespace collision.** `com.templeregistry.event.finance` already exists and belongs to
the **trust financial declaration workflow**. It has nothing to do with this platform. New
work goes in `entity.finance`, `repository.finance`, `service.finance`, `connector.finance`.

---

## Remaining Risks

| Risk | State after FIN-016 |
|---|---|
| **R12** — the profile split is collapsed "just for Kollur" | **Substantially mitigated.** Two runtime tests and a classpath guard fail the build if worker beans appear in the registry runtime. The remaining exposure is someone deleting the tests |
| **R7** — `ddl-auto: update` masking missing migrations | Unchanged, now observed twice (FIN-X-001). The worker sets `ddl-auto: none`, so it cannot contribute to drift |
| Duplicate background jobs across two processes | **Closed** by FIN-D-007, before a second process ever ran |
| Credentials reaching version control | **Mitigated:** no credential property in any YAML, no fallback, a test asserting their absence, and a redacting `toString()`. Not eliminated until Q5 chooses a real store |
| Worker accidentally serving HTTP | **Closed:** property plus a startup guard that fails closed |
| A future connector wired into the registry runtime | **Mitigated** by FIN-D-008 and the classpath guard, verified by mutation |

---

## Q4 / Q5 Status

**Q4 — network path to Kollur: UNRESOLVED, and not assumed.** Nothing built so far presumes
inbound JDBC works. `ConnectorType` models `PULL_JDBC`, `PUSH_AGENT`, `SOURCE_API` and
`FILE_DROP` as equals, and `SourceCredentials` carries an *optional* principal precisely so
that a token or shared key fits the same shape as a database user — a push agent
authenticating inbound needs a secret but no username.

If inbound access to Kollur is refused, the change is a `connector_type` value and a
different connector implementation. The canonical model, aggregation, Finance APIs and
dashboard are untouched. Across government temples, refusing inbound database connections
is usually a policy position rather than a technical one, so `PUSH_AGENT` should be treated
as a likely outcome, not a fallback.

**Q5 — secret storage: UNRESOLVED, abstraction delivered.** No temple credential exists
anywhere in the repository, the database, or any configuration file. The seam is
`SourceCredentialProvider`; the interim implementation reads
`trm.finance.source.<ref>.secret` from the worker process environment and throws
`CredentialNotConfiguredException` when absent.

Deliberately not done: no encrypted connection string (rejected in FIN-D-002 — it would put
a decryptable route to every temple database inside the registry process), no credential
columns on `fin_source_system`, and no placeholder defaults in YAML. That last point is
specific: `application.yml` already carries committed fallback values for `DB_USERNAME` and
`DB_PASSWORD`, and declaring finance credentials with placeholder defaults is exactly how
those got there.

Choosing the permanent store changes one `@Bean` method in `SyncWorkerConfig`.

---

## Important Decisions

FIN-D-001 … FIN-D-010 in [IMPLEMENTATION_DECISIONS.md](IMPLEMENTATION_DECISIONS.md). The
four from this session:

- **FIN-D-007** — `@EnableScheduling` moved to a registry-only configuration rather than
  annotating five scheduler beans, several of which expose methods other services call.
  This also closed a duplicate-email hazard.
- **FIN-D-008** — worker beans are explicit `@Bean` registrations, never `@Component` +
  `@Profile`, because a forgotten annotation fails *open*.
- **FIN-D-009** — credentials resolve through an interface, from the environment, with no
  fallback, redacted rendering, and validation of `credential_ref` so a database row cannot
  read an unrelated property.
- **FIN-D-010** — the worker is non-web, asserted at startup, and owns no schema.

---

## How To Continue

1. Read this file, then `IMPLEMENTATION_STATUS.md` and `IMPLEMENTATION_TASKS.md`.
2. `git status` and `git log --oneline -6` on `feature/db-integration`.
3. Confirm the baseline:
   `cd backend && mvn -o test -Dtest='*Finance*,*SyncWorker*,*RegistryRuntime*,*SourceCredential*'`
   — expect **43 passing, 0 failures**. (Verified; omitting `*SourceCredential*` yields 34.)
4. Implement FIN-030 only. Do not implement a connector in the same change.
5. Register anything new in `SyncWorkerConfig` as a `@Bean`; do not annotate it `@Component`.
6. Run the suite, update the four tracking documents, commit as `FIN-030 ...`.

Do not repeat the architectural analysis. It is complete and in `docs/finance/`.

---

## NEXT ACTION

Implement **FIN-030**: define the `TempleFinanceConnector` interface and its supporting
types (`SyncWindow`, `ExtractionContext`, `RawRow`, `ConnectionResult`,
`SchemaFingerprint`, `ReconMetric`) in `com.templeregistry.connector.finance`, with no
implementation and no temple-specific knowledge. Capabilities must be explicit so that no
temple is forced to implement what it cannot supply.

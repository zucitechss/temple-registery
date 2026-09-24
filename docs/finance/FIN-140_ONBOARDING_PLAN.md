# FIN-140 — Generic Multi-Temple Onboarding

## Analysis and Implementation Plan (incorporating FIN-032)

**Status:** Plan approved 2026-09-23. **Slices 140-A through 140-D implemented and verified**;
140-E and 140-F outstanding. Sections 1–17 are the analysis as written before implementation and are left unedited,
so the reasoning can be checked against what was built. §18 records what was decided and delivered.

> **§5 and §10 are superseded on one point.** Both proposed a whole-matrix `PUT` for capabilities.
> 140-B implements per-declaration `POST`/`PUT` instead, because per-row writes give the atomicity
> the plan wanted *and* a per-row audit line and optimistic lock, which one version across nineteen
> rows could not. Recorded as FIN-D-079.
**Date:** 2026-09-23
**Branch:** `feature/db-integration`
**Parent documents:** [MULTI_TEMPLE_FINANCE_ARCHITECTURE.md](MULTI_TEMPLE_FINANCE_ARCHITECTURE.md) · [TEMPLE_FINANCE_ONBOARDING.md](TEMPLE_FINANCE_ONBOARDING.md)
**Supersedes nothing.** Amends no ADR. Changes no ADR status.

> **Location.** Moved from `docs/` to `docs/finance/` on approval of slice 140-A, matching
> every sibling finance plan. No document referenced it at the time of the move.

---

## 0. Executive summary

Onboarding a temple's finance source system is today a hand-written Flyway migration
(`V111__kollur_finance_configuration.sql`, 407 lines). There is no API, no screen and no
repeatable process. FIN-140 is the task that turns that into product.

The investigation found **one architectural fact that reshapes the whole task**, and it is not
the one the task description anticipated:

> **The probe cannot be a button.** A `TempleFinanceConnector` instance exists only in the
> sync-worker runtime; the registry web application is test-enforced to contain no connector
> bean at all; the worker refuses to boot as a web application; and the only channel between
> the two runtimes is the shared database. A synchronous "Test Connection" request is therefore
> not merely discouraged — it is architecturally unreachable.

Two consequences follow, and they are the plan's spine:

1. **FIN-032 splits in two.** Roughly 80 % of what a probe should catch — missing capability
   declarations, an absent source-of-truth declaration, a mapping rule naming a canonical
   category that does not exist, two rules that would resolve ambiguously — needs **no
   connector at all**. It reads only registry tables and runs synchronously in the registry
   runtime. Only "can we reach the source" and "has the schema drifted" need the worker.
2. **The half that needs the worker cannot be built usefully yet.** Zero production connectors
   exist (`grep "implements TempleFinanceConnector" src/main` → nothing), so a live probe would
   fail for every source on earth today. Worse, the worker has **no production trigger of any
   kind** — nothing creates a `fin_sync_batch` row, nothing polls for one, and `@Scheduled` is
   disabled in the worker profile. Building a worker-side probe means building the worker's
   first production trigger, which is a substantial piece of infrastructure in its own right.

**Recommendation: ship the validation that needs no connector; defer the probe that needs one.**
That ordering delivers a genuinely useful onboarding surface now, and it does not build a UI
whose final button can only ever fail.

**Verdict: PLAN COMPLETE · IMPLEMENTATION BLOCKED** on three decisions (§16: D-140-1, D-140-3,
D-140-4). Each has a recommendation; none is large; all three are needed before the *first*
slice, because they determine what the create endpoint is allowed to do and return.

---

## 1. Repository documents and code inspected

### Documents

| Document | What it contributed |
|---|---|
| `docs/finance/TEMPLE_FINANCE_ONBOARDING.md` | **The single most useful document.** A 16-step onboarding pipeline already exists, with each step classified Manual / Configuration / Code / Automated, and a fully specified hypothetical "Temple B" used as the genericity proof. This plan implements that document rather than re-deriving it. |
| `docs/finance/MULTI_TEMPLE_FINANCE_ARCHITECTURE.md` | G10 ("onboarding is configuration plus one connector, never a dashboard change"); §"Phase 7 — Multi-temple proof" explicitly sanctions **"a synthetic second source"** as the acceptance test. |
| `docs/finance/IMPLEMENTATION_OWNERSHIP.md` | §4 "Adding a Second Temple" — the list of things that must **not** change, and the statement that this is "the acceptance test for the whole platform". |
| `docs/finance/API_CONTRACT.md` §5, §7 | Error-code conventions and the FIN-054A administrative endpoint precedent. §7.5 produced a direct conflict — see D-140-4. |
| `docs/finance/adr/ADR-001` | No runtime source access. The constraint that makes the probe hard. |
| `docs/finance/adr/ADR-004` | Code extraction / configured mapping. Promises a generic `JdbcTableConnector` that **does not exist** (§15). |
| `docs/finance/adr/ADR-007`, `ADR-008` | Capability declarations and source-of-truth declarations as reviewable data — the two things onboarding chiefly writes. |
| `docs/finance/IMPLEMENTATION_TASKS.md`, `IMPLEMENTATION_STATUS.md`, `HANDOFF.md`, `IMPLEMENTATION_DECISIONS.md` | Current state, limitations 1–73, decisions FIN-D-001…072. |
| `docs/finance/FIN-070B_AGGREGATION_DECISIONS.md` D9 | The open multi-source question this task forces (§8). |
| `.github/copilot-instructions.md`, `.github/instructions/{backend,frontend,testing,ui_ux}.instructions.md` | House conventions. |

### Code

`V110__finance_foundation.sql`, `V111__kollur_finance_configuration.sql`,
`V115__kollur_revenue_date_declaration.sql`, `V117__finance_mapping_rule_version.sql` ·
`FinSourceSystem`, `FinTempleCapability`, `FinSourceOfTruthDecl`, `FinMappingRule`, `FinSyncBatch`
and their repositories · `TempleFinanceConnector` and all ten supporting connector types ·
`ConnectorRegistry`, `SyncWorkerConfig`, `FinanceProfiles`, `SyncWorkerBoundaryGuard`,
`SchedulingConfig`, `SourceCredentialProvider` · `FinancePipelineOrchestrator` ·
`FinanceMappingController`, `MappingAdminService(Impl)` and every FIN-054A DTO ·
`RoleConstants`, `ScopeHelper`, `JurisdictionGuard`, `PolicyEvaluationServiceImpl`,
`PolicyEnforcementAspect` · `WorkflowInstance`, `WorkflowStatus`, `WorkflowAction`,
`WorkflowEntityType`, `WorkflowEngineImpl` · `AuditService`, `AuditDataEvent` ·
`RegistryRuntimeContextTest`, `SyncWorkerRuntimeContextTest`, `SyncWorkerProfileBoundaryTest`,
`FinanceIntegrationBoundaryTest` · `frontend/src/features/finance/**` (all 15 files),
`routes/index.tsx`, `RoleRoute`, `usePermissions`, `targetKeys.ts`.

### Working tree

Branch `feature/db-integration`, **22 modified / 21 untracked paths**, all of them the
uncommitted FIN-070/072/080-084 (backend reporting API) and FIN-090…093 (dashboard) work from
prior sessions. **Nothing was reset, rebased, amended or committed. No untracked file was
modified.** This plan adds exactly one new file and touches nothing else.

---

## 2. Existing onboarding capability — what is already there

More exists than the `NOT_STARTED` label suggests. Onboarding is not greenfield; it is mostly
*unexposed*.

| Already built | Where | Reusable for FIN-140? |
|---|---|---|
| The four configuration tables | `V110` — `fin_source_system`, `fin_temple_capability`, `fin_source_of_truth_decl`, `fin_mapping_rule` | **Yes, entirely.** No new configuration table is needed. |
| Entities + repositories for all four | `entity/finance/`, `repository/finance/` | Yes. |
| Mapping-rule administration, full stack | FIN-054A-BE + FIN-054B — 9 endpoints, a screen at `/finance/source-mapper` | **Yes — step 9 of the onboarding pipeline is already shipped.** FIN-140 links to it rather than rebuilding it. |
| `GET /finance/source-systems` (read-only list, jurisdiction-filtered) | `FinanceMappingController` | Yes — extend, do not replace. |
| Namespaced source-value validation | `MappingAdminServiceImpl`, FIN-D-062 | Yes — the same validator serves onboarding's mapping checks. |
| Jurisdiction scoping for finance admin | `MappingAdminServiceImpl.outOfScope(...)` | Yes — copy the pattern verbatim (it already routes around the AUDITOR/`assertDistrictScope` defect). |
| Audit in the caller's transaction | FIN-D-063, `AuditDataEventRepository` | Yes. |
| A generic, entity-agnostic approval state machine | `WorkflowInstance` / `WorkflowStatus` / `WorkflowAction` / `WorkflowEntityType` / `WorkflowEngineImpl` | **Candidate — see D-140-1.** Its own header says adding a governable module needs "no new tables, no new service classes, no new status enums". |
| `sync_enabled` kill switch, defaults `0` | `V110` line 57 — *"defaults OFF so registering a source never starts traffic"* | **Yes — this is already the activation mechanism.** |
| `SyncType.DRY_RUN` and `SyncTrigger.ONBOARDING` | `entity/finance/enums/` | **Yes — declared, documented, and used by zero production code.** Designed-in hooks for exactly this feature. |
| `fin_source_of_truth_decl.version / approved_by / approved_at / effective_from / effective_to` | `V110` | **Yes — a per-declaration approval and versioning mechanism already exists in the schema.** |
| `SourceProbeResult`, `SchemaFingerprint`, `probe()`, `fingerprintSchema()` | `connector/finance/` | Contract only — **no implementation anywhere.** |

**The single most important reuse finding:** `fin_sync_batch` already carries
`sync_type ∈ {…, DRY_RUN}` and `triggered_by ∈ {SCHEDULER, MANUAL, ONBOARDING}`, and V110's own
comment documents the dry run as part of onboarding. The dry-run step needs **no new table**.

---

## 3. How Kollur is onboarded today

Entirely through `V111__kollur_finance_configuration.sql` plus `V115` — 461 lines of
conditional SQL, executed by Flyway, authored by hand.

| # | What | Rows | Mechanism |
|---|---|---|---|
| FIN-021 | Source system | 1 | `INSERT IGNORE … SELECT` guarded on temple 300001 existing |
| FIN-022 | Capabilities | 19 (all of them) | same guard |
| FIN-023 | Source of truth — `REVENUE_AMOUNT` | 1, with measured rejected alternatives | same guard |
| FIN-024 | Mapping rules | 9 | same guard |
| FIN-055/V115 | Source of truth — business date | 1 | same guard |

### The finding that makes FIN-140 mandatory rather than merely desirable

`V111` lines 28–30, verbatim:

> *"Consequence, recorded in HANDOFF.md: in a database where temple 300001 is created AFTER this
> migration runs, the seed is a no-op and Kollur configuration must be applied through the
> onboarding path (FIN-140) instead."*

**Kollur itself already depends on FIN-140** in any environment where the temple was created
after the migration ran — which, since no migration creates temple 300001 (V100 seeds ids
100…), is every environment where the temple was created through the application. FIN-140 is
not only about temple #2.

### Configuration classified

The task brief asks for a five-way split. Here it is, with the reason each item lands where it
does.

**A · Runtime configuration** — read by a running process, changeable without deployment.
`sync_enabled`, `sync_schedule_cron`, `staleness_threshold_hours`, `source_timezone`.
*Why:* each is read per-request or per-run; none changes the shape of anything.

**B · Database configuration** — the reviewable substance of onboarding. `fin_source_system`
identity columns, all `fin_temple_capability` rows, all `fin_source_of_truth_decl` rows, all
`fin_mapping_rule` rows.
*Why:* ADR-004 puts "what a value means" in configuration; ADR-007 makes availability
first-class *data*; ADR-008 makes source-of-truth a reviewed *artefact*. All three are explicit
that these are rows, not code. **This is the category FIN-140 exposes through an API.**

**C · Deployment configuration** — requires a build and a release. `connector_bean` *resolving
to an actual Spring bean*; the connector implementation class itself; `connector_type` insofar
as it must match the deployed connector's declared mechanism.
*Why:* `ConnectorRegistry` is built in `SyncWorkerConfig` from
`getBeansOfType(TempleFinanceConnector.class)`. A bean enters the registry by being declared as
a worker bean and **by no other route** (FIN-D-008). A row in a table cannot create a Java
object. See §5 for the consequence.

**D · Secret / credential configuration** — never in the database, never in the API.
The credential itself, resolved by `SourceCredentialProvider` from the worker's environment.
`credential_ref` is an **alias**, not a secret — it is the lookup key, and it lives in category B.
*Why:* `V110` lines 50–52 and FIN-D-009. `RegistryRuntimeContextTest` asserts the registry
runtime has **no** `SourceCredentialProvider` bean at all.

**E · Manual operational approval** — a human judgement that no validator can make.
The written data analysis (onboarding step 5); the source-of-truth sign-off (step 7); the
decision to set `sync_enabled = 1`; the per-financial-year reconciliation gate (step 12).
*Why:* `TEMPLE_FINANCE_ONBOARDING.md` §2 calls step 5 *"the step that cannot be skipped or
automated"* and step 7 *"a gate, not a formality"*. Kollur's own analysis found a 41 % revenue
understatement and a duplicate-archive trap that no schema check would have caught.

---

## 4. What is missing

| Gap | Evidence |
|---|---|
| No write API for `fin_source_system` | `FinanceMappingController` has GET only; create/update exist nowhere |
| No API for capability declarations | No controller or service references `FinTempleCapabilityRepository` for writes |
| No API for source-of-truth declarations | Same — `FinSourceOfTruthDeclRepository` has no write caller outside the pipeline's reads |
| No configuration validation of any kind | Nothing checks that a configured source is coherent before it goes live |
| No probe, no dry run | `probe()` implemented only by four test stubs; `DRY_RUN` used by no production code |
| No onboarding lifecycle | `fin_source_system` has no status column; only the `sync_enabled` boolean |
| No onboarding UI | `frontend/src/features/finance/` is the mapping screen only |
| No optimistic locking on three of four config tables | `@Version` exists on `FinMappingRule` only (V117) |
| No way to check a connector exists | `ConnectorRegistry` is worker-only; the registry runtime cannot see it |
| **No generic `JdbcTableConnector`** | Promised by ADR-004 and `TEMPLE_FINANCE_ONBOARDING.md` §8; implemented nowhere |

---

## 5. The generic onboarding workflow

Mapped onto the 16 steps already defined in `TEMPLE_FINANCE_ONBOARDING.md` §1. "In scope"
means FIN-140 builds software for it.

| # | Step | Actor | Permission | In scope? |
|---:|---|---|---|---|
| 1 | Temple registered | SA | existing | No — existing registry flow |
| 2 | Source system survey | Integration + temple IT | — | No — offline |
| 3 | Connectivity decision | Infra + temple | — | No — offline (Q4) |
| 4 | **Register source system** | SA | D-140-2 | **Yes** |
| 5 | Data analysis | Analyst | — | No — offline, but §6 requires its **link/reference** to be recorded |
| 6 | **Declare capabilities** | Analyst + DC | `CAN_ACT_DC` | **Yes** |
| 7 | **Declare source of truth** | Analyst + sign-off | `CAN_ACT_DC` | **Yes** |
| 8 | Build/select connector | Engineering | deployment | No — category C |
| 9 | Seed mappings | Analyst | `CAN_ACT_DC` | **Already shipped** (FIN-054A/B) |
| 10 | **Validate configuration** | Platform | read | **Yes — registry-side, the core of FIN-032** |
| 10b | Probe / dry run | Platform | — | **Deferred** — §6 |
| 11 | Historical load | Platform | — | No — needs a connector (Q4) |
| 12 | Reconcile per FY | Platform + analyst | — | Already built (FIN-060/061) |
| 13 | Build aggregates | Platform | — | Already built (FIN-070/072) |
| 14 | **Activate (`sync_enabled = 1`)** | SA | D-140-2 | **Yes — gated on 10** |
| 15 | Enable reports | — | — | **Automatic.** The dashboard composes from `GET /finance/capabilities`; FIN-093 removed the temple-id gate. Nothing to build. |
| 16 | Monitor | Platform | — | No — FIN-160 |

### Stage detail for the steps in scope

**Step 4 — Register source system.** Input: `templeId`, `systemCode`, `systemName`,
`sourceTechnology`, `connectorType`, `connectorBean`, optional `sourceTempleCode`,
`sourceDatabaseName`, `credentialRef`, `sourceTimezone`, `notes`. Output: the created row,
`syncEnabled = false` always. Validation: temple exists and is in the caller's jurisdiction;
`uk_fss_temple_system` not violated (→ 409); `connectorType` is a valid enum; **D-140-3 decides
whether a second source for the same temple is refused**. Failure: 404 (absent *or* out of
scope), 409 (duplicate), 422 (semantic), 400 (bean validation). Automated. No approval.

**Step 6 — Declare capabilities.** Input: the full capability matrix for the source, as one
array. Output: the persisted matrix. Validation: every `capability` in the canonical 19; an
availability of `NOT_AVAILABLE` or `PARTIALLY_AVAILABLE` **must** carry a non-blank
`availabilityReason`, because `V110` documents it as *"USER-FACING. Shown verbatim"*;
`AVAILABLE` should carry `coverageFrom` (warning, not block). Writes as a whole-matrix upsert,
never a partial patch — a capability matrix read half-updated would misreport a temple.
**Idempotency:** replaying the same matrix is a no-op.

**Step 7 — Declare source of truth.** Input: `metric`, `sourceObject`, `sourceField`,
`filterPredicate`, `rejectedAlternatives[]`, `rationale`. Output: a **new version row**, never
an update — `uk_fsotd_source_metric_version (source_system_id, metric, version)` exists exactly
so history is kept, and ADR-008's whole point is that this decision is reviewable. Supersede by
setting the prior row's `effective_to`. Validation: `rejectedAlternatives` non-empty is a
**warning** not a block (Kollur's value came from recording measured rejects, but a genuinely
unambiguous source has none). `approved_by`/`approved_at` set on sign-off.

**Step 10 — Validate.** See §6. Read-only, synchronous, registry-side.

**Step 14 — Activate.** Input: `syncEnabled` + the expected config version. **Refused with 422
if step 10 reports any blocking issue.** This is the one place the validator has teeth. Output:
the updated row, audited. Manual, deliberate, `ADMIN_ONLY` (recommended).

### Connector registration must stay deployment-controlled

The brief asks explicitly whether connector registration can be self-service. **It cannot, and
the reason is structural rather than a policy preference.**

`SyncWorkerConfig` builds the registry from `getBeansOfType(TempleFinanceConnector.class)`
(FIN-D-008: worker beans are explicit `@Bean` registrations, never component-scanned). A
connector is a Java class in the deployed artefact. No row in any table can produce one. An
onboarding screen can therefore let an administrator **name** a connector bean — and that name
is just a string until a release makes it resolvable.

The honest UI consequence, which the plan builds in: the source-system form must state that
naming a connector does not create one, in the same spirit as FIN-054A's `historicalEffect`
sentence (FIN-D-066) telling an operator that saving a mapping rule does not correct published
figures. Both are the same class of lie-by-omission, and this codebase already has the
convention for refusing to tell it.

---

## 6. FIN-032 — probe and validation design

### 6.1 The constraint, established by evidence

| Fact | Evidence |
|---|---|
| Registry runtime has no connector bean, and no bean from `com.templeregistry.connector.**` at all | `RegistryRuntimeContextTest` — a **package-wide** assertion, plus explicit `ConnectorRegistry` / `TempleFinanceConnector` emptiness checks |
| Worker refuses to boot as a web application | `SyncWorkerBoundaryGuard` throws `IllegalStateException("…must not serve HTTP…")`; `application-sync-worker.yml` sets `web-application-type: none`; `SyncWorkerProfileBoundaryTest.WebRuntimeRefusal` asserts the startup failure |
| No controller may exist in the integration packages | `FinanceIntegrationBoundaryTest` — *"The worker holds temple credentials and does not serve web traffic."* |
| Worker cannot use `@Scheduled` | `SchedulingConfig` is `@Profile("!sync-worker")` |
| Only channel between runtimes = the shared database | No broker dependency in `pom.xml`; no HTTP client; no worker address property; `findRetryable(...)` has **no production caller** |
| No production trigger exists at all | `FinancePipelineOrchestrator.run(long)` is called only from tests; nothing constructs a `FinSyncBatch` in `src/main` |
| Zero production connectors | `implements TempleFinanceConnector` → four hits, all in `src/test` |

**Therefore a synchronous probe endpoint is impossible**, and an asynchronous one requires
building the worker's first production trigger.

### 6.2 The split

**Class A — configuration validation. Registry-side, synchronous, no connector. IN SCOPE.**

| # | Check | Blocks? | Reuses | Why it is needed |
|---:|---|---|---|---|
| A1 | Temple exists and is in the caller's jurisdiction | Block | `JurisdictionGuard` + `outOfScope` pattern | A source pointing at a temple the caller cannot see is a scope leak |
| A2 | Required source-system fields present and well-formed | Block | Bean validation | `connector_bean` is `NOT NULL`; an empty one guarantees a resolution failure later |
| A3 | At least one capability declared | Block | `FinTempleCapabilityRepository` | Without one, every dashboard metric is `NOT_AVAILABLE` with no reason — the exact failure ADR-007 exists to prevent |
| A4 | Every `NOT_AVAILABLE` / `PARTIALLY_AVAILABLE` capability has a reason | Block | — | `V110`: the reason is *"USER-FACING. Shown verbatim"*. A blank one renders an empty explanation to a District Collector |
| A5 | `AVAILABLE` capability has `coverage_from` | **Warn** | — | The dashboard can state bounds without it, but will overstate coverage |
| A6 | If `REVENUE` is available, a current source-of-truth for `REVENUE_AMOUNT` exists (`effective_to IS NULL`) | Block | `FinSourceOfTruthDeclRepository` | `RevenueNormalizer` refuses without it — every row would reject at normalization |
| A7 | A business-date declaration exists | Block | same | Exactly the gap `V115` was written to close: *"the amount could be read and never placed in time"* |
| A8 | ≥1 active `REVENUE_CATEGORY` mapping rule | Block | `FinMappingRuleRepository` | Otherwise every value routes to `UNMAPPED` and no revenue is classified |
| A9 | Every rule's `canonical_value` exists in `fin_revenue_category` | Block | `FinRevenueCategoryRepository` | This is the mapping engine's `INVALID_CONFIGURATION` outcome, detected **statically instead of at run time** |
| A10 | No two active rules share namespace+value at equal priority | Block | `RevenueCategoryMapper` precedence logic | This is the engine's `AMBIGUOUS` outcome, likewise detected before any data flows |
| A11 | Every rule's stored value is well-formed `NAMESPACE:value` | Block | the FIN-D-062 validator | A malformed rule can never match anything |
| A12 | Rule namespaces have been observed in staged payloads | **Warn** | FIN-054A already computes this | Legal but probably wrong; identical to the existing `warnings` field |
| A13 | Duplicate source system for the temple | Block/Warn | `uk_fss_temple_system` | Depends on D-140-3 |

A9 and A10 are the most valuable checks in the list and the strongest argument for doing this
at all: both are failure modes the mapping engine currently discovers **at run time, per row,
after the pipeline has already started**. Onboarding can catch them while the configuration is
still a draft.

**Class B — source contact. Worker-side, asynchronous. DEFERRED.**

`probe()` (reachability), `fingerprintSchema()` (drift vs `fin_source_system.schema_fingerprint`),
`sourceTotals()` sanity, and a real `DRY_RUN` extraction.

**Why deferred, not merely sequenced:** with no connector implemented, every Class B check
returns "not registered" for every source that exists. Building the transport for an answer
that is currently constant is work whose only output is infrastructure. It should be built
alongside FIN-040/041, when there is something to probe.

**The design for when it is built** (recorded now so it is not re-derived):

```
Registry                     shared DB                        Worker
--------                     ---------                        ------
POST …/probe        ──▶  INSERT fin_sync_batch
  (202 Accepted)            sync_type   = DRY_RUN
                            triggered_by= ONBOARDING
                            status      = PENDING
                                  │
                                  │        poll + claimForRun()   ◀── the missing piece:
                                  │◀───────────────────────────────    the worker's FIRST
                                  │                                    production trigger,
                            UPDATE status, schema_fingerprint,          driven by the existing
                                   last_failure_reason                  `financeSyncScheduler`
                                  │                                     bean (which today has
GET …/probe/{ref}   ◀─────────────┘                                     no caller at all)
```

No new table. `DRY_RUN` and `ONBOARDING` already exist for this. The genuinely new component is
the worker-side poller — and note it cannot use `@Scheduled`, so it must drive the existing
`financeSyncScheduler` `TaskScheduler` from an `ApplicationRunner` or `SmartLifecycle`.

### 6.3 Checks deliberately NOT proposed

Rejected because nothing in the architecture or requirements asks for them: source row-count
sampling (would read temple data into the registry), credential validity checking (the registry
must never resolve a credential), automatic capability *inference* from the source (ADR-007
makes availability a declared, reasoned statement — inferring it would produce a confident
machine guess where a human reason belongs), and connector version pinning (no versioning
scheme exists).

---

## 7. Security and credential boundaries

| Concern | Decision | Evidence |
|---|---|---|
| Registry gains source DB access? | **Never.** All Class A checks read `fin_*` registry tables only. | ADR-001; `RegistryRuntimeContextTest` |
| Where does a probe execute? | Worker only, when built. | §6.1 |
| How is it requested? | A `fin_sync_batch` row — the shared database, the only channel. | §6.2 |
| Credential in an API? | Never in, never out. | `V110`; FIN-D-009 |
| `credential_ref` (the alias)? | **Writable, never returned.** Return `credentialRefSet: boolean`. | It is a lookup key, not a secret — but returning it names the environment variable holding the secret, which `API_CONTRACT` §7.5 forbids and which is a genuine reconnaissance leak |
| New code placement | Onboarding service in `service/impl/finance/`, controller in `controller/finance/`, **nothing** in `connector.**` or `service.finance.sync.**` | Those packages are banned from the registry runtime by a package-wide test assertion |
| Authorization site | On the service impl *and* the controller, per house convention | Source Mapper duplicates every `@PreAuthorize` |
| Jurisdiction | Copy `MappingAdminServiceImpl.outOfScope(...)` verbatim | It already routes around the `assertDistrictScope` AUDITOR defect |
| Audit | `AuditDataEvent` via its repository, in the caller's transaction | FIN-D-063 — *"a change to how revenue is classified, recorded nowhere, is worse than a change refused"*; this applies with **more** force to which database feeds a temple |
| Out-of-scope response | **404, never 403** | `API_CONTRACT` §7.4 — a distinguishable refusal enumerates every temple's integrations |

**One new audit obligation.** Activation (`sync_enabled = 0 → 1`) is the single most consequential
act in this feature: it is the moment the platform is permitted to contact a government temple's
production database. It must be audited with actor, timestamp, and the validation verdict that
was in force at the time.

---

## 8. D9 — multiple sources per temple

### What the code actually assumes

| Object | Multi-source ready? | Evidence |
|---|---|---|
| `fin_revenue_fact` | **Yes** | `uk_frf_grain` includes `source_system_id` (V118 / FIN-052A) |
| `fin_agg_revenue_period` | **Yes** | `uk_farp_grain` includes it (V119) |
| `fin_source_system` | Yes | `uk_fss_temple_system (temple_id, system_code)` permits many |
| `fin_temple_capability` | **No** | `uk_ftc_temple_capability (temple_id, capability)` — a second source declaring `REVENUE` collides |
| `fin_service_dim` | **No** | `uk_fsd_temple_service (temple_id, service_code)` |
| Capability **read path** | **No** | `FinTempleCapabilityRepository` queries by `templeId` only — `source_system_id` is a column it never filters on |

The read path matters more than the constraint. Even after widening the unique key, every
capability read would still need to decide *which source's answer is the temple's answer*.

### Scenario analysis

| Scenario | Today's behaviour |
|---|---|
| One temple, one source | Correct. The only tested case. |
| One temple, two sources | Second capability insert **fails** on `uk_ftc_temple_capability`. Facts and aggregates would be fine. |
| Two sources, same capability | Cannot be expressed. |
| Two sources disagree | Unrepresentable — so the question has never had to be answered. |
| Two sources, different services | Collides on `uk_fsd_temple_service`. |
| A source becomes unavailable | No per-source freshness in the capability model; staleness is computed per temple. |
| A source is replaced | Soft-delete the old, register the new; capability rows must be reassigned by hand. |
| A source is deactivated | `sync_enabled = 0` stops ingestion. Already-published facts remain — correct, and the honest behaviour. |

### Options

| | Approach | Migration | Read impact | Risk |
|---|---|---|---|---|
| **A** | **Enforce one source per temple** — onboarding refuses a second with a clear message | None | None | Blocks a genuine future need; must be undone deliberately |
| **B** | Widen `uk_ftc_temple_capability` to include `source_system_id`, teach the read path a conflict rule | 2 `ALTER`s + read-path change in `FinanceReportServiceImpl` | Every capability read needs a resolution rule | **Bakes an unanswered semantic question into published data** |
| **C** | Add `fin_source_system.is_primary`; temple-level answer = the primary's | 1 column + read-path change | Moderate | A "primary" is a real concept only if sources are genuinely redundant, which no evidence supports |

### Recommendation — Option A, and record D9 as still open

FIN-D-068's own reasoning against a related change applies directly: *"Writing a per-temple
answer into a per-source row would bake the unresolved conflict into published data, where it
would be discovered by a reader rather than by a developer."* Option B does exactly that, for a
second source that does not exist yet and whose disagreement semantics nobody has needed to
define. Option A is a one-line refusal with a clear message; it is fully reversible; and it
makes the limitation visible to an administrator at the moment it bites, which is the only
moment anyone will have the context to answer D9 properly.

**This requires approval (D-140-3)** — it is a product constraint, not an implementation detail.

---

## 9. Data model

**No new configuration table is proposed.** All four exist.

### Required migration — minimal

| Change | Why | Risk |
|---|---|---|
| `@Version` column on `fin_source_system`, `fin_temple_capability`, `fin_source_of_truth_decl` | Only `fin_mapping_rule` has one (V117). Concurrent edits to a capability matrix would otherwise silently last-write-win. | Additive `INT NOT NULL DEFAULT 0`; cannot fail on existing rows |
| *(D-140-1 dependent)* one new `WorkflowEntityType` value | If the lifecycle reuses the workflow engine | Enum value + seeded `workflow_instance` rows; touches the governance module |

Everything else is reads and writes against existing tables.

### Deliberately NOT created

- **A probe-results table.** `fin_sync_batch` with `sync_type = DRY_RUN` already models a run
  with a status, a failure reason and a schema fingerprint.
- **A validation-results table.** Validation is a **pure function of current configuration**,
  computed on read. Persisting it would create a second source of truth that goes stale the
  moment a mapping rule changes — and the one thing this validator must never do is tell an
  administrator a configuration is clean because it was clean an hour ago.
- **An onboarding-status column**, pending D-140-1.
- **An approval-history table.** `fin_source_of_truth_decl` already has `approved_by` /
  `approved_at` / `version`; `AuditDataEvent` covers the rest.

---

## 10. Backend API

Base path `/api/v1/finance`, matching FIN-054A. Read = `CAN_READ_FINANCE_CONFIG`. Write roles
per D-140-2. Every response in `ApiResponse<T>`. Every out-of-scope or absent resource → **404**.

| Method | Path | Purpose | Auth | Codes |
|---|---|---|---|---|
| GET | `/source-systems/{id}` | One source system — **never** `credentialRef`; `connectorBean`/`sourceDatabaseName` per D-140-4 | read | 200, 404 |
| POST | `/source-systems` | Register. `syncEnabled` forced false | write-A | 201, 400, 404, 409, 422 |
| PUT | `/source-systems/{id}` | Update; `version` checked | write-A | 200, 404, 409, 422 |
| GET | `/source-systems/{id}/capabilities` | Declared matrix | read | 200, 404 |
| PUT | `/source-systems/{id}/capabilities` | Upsert the whole matrix | write-B | 200, 404, 409, 422 |
| GET | `/source-systems/{id}/source-of-truth` | All declarations, all versions | read | 200, 404 |
| POST | `/source-systems/{id}/source-of-truth` | New **version**; supersedes prior | write-B | 201, 404, 422 |
| GET | `/capability-catalogue` | The 19 capabilities + metric vocabulary, for form options | read | 200 |
| GET | `/source-systems/{id}/readiness` | **The validator.** Blocking issues + warnings, each with a code and a human sentence | read | 200, 404 |
| POST | `/source-systems/{id}/activation` | Set `sync_enabled`. **422 if readiness has any blocking issue** | write-A | 200, 404, 409, **422** |
| *(deferred)* | `POST /source-systems/{id}/probe` · `GET …/probe/{batchRef}` | Class B | write-A | 202 / 200 |

**Transaction boundaries.** One transaction per write, audit inside it (FIN-D-063). The
capability matrix upsert is a single transaction — a half-written matrix is a misreported temple.

**Idempotency.** Create → `uk_fss_temple_system` → 409 naming the existing row. Matrix upsert →
naturally idempotent. Source-of-truth POST → **not** idempotent by design; each call is a new
reviewable version, which is the point of ADR-008.

**Reused verbatim from FIN-054A.** The `{ …, "warnings": [...] }` envelope on writes; the
allow-listed `sort`; 404-not-403; `IllegalStateException` → 422; explicit version check →
`OptimisticLockingFailureException` → 409.

**Not built:** no endpoint triggers re-processing (FIN-D-066 holds); no endpoint returns a
credential; no endpoint accepts SQL, a table name or a column name; no delete (deactivate
covers retirement, exactly as FIN-054A concluded).

---

## 11. Frontend

New module `frontend/src/features/finance-onboarding/`, following the five-file convention
exactly: `financeOnboardingApi.ts` · `financeOnboardingRequests.ts` · `financeOnboardingTypes.ts`
· `financeOnboardingErrors.ts` · `pages/` · `components/` · `__tests__/`. Registered in
`rootReducer.ts`, `store.ts` middleware, `store.ts` cache-reset, and `renderWithProviders.tsx`.

| Screen | New/extend | Notes |
|---|---|---|
| Source systems list | New page `/finance/source-systems` | `DataTable` + `StatusBadge`; "Register" button gated on write role |
| Source system detail | New | Tabs: Configuration · Capabilities · Source of Truth · Mapping · Readiness |
| Register/edit form | New | `Sheet` + react-hook-form + zod, copying `MappingRuleDrawer` |
| Capability matrix editor | New | 19 rows, availability select + reason textarea; **reason required when not AVAILABLE**, enforced client-side and server-side |
| Source-of-truth editor | New | Emphasises rejected alternatives; states that saving creates a new version |
| Mapping tab | **Link to the existing Source Mapper** | Already shipped; rebuilding it would duplicate FIN-054B |
| Readiness panel | New | Blocking issues vs warnings, visually distinct; Activate button disabled while any blocker stands, with the reason shown next to it |

**Deliberately not a wizard.** The only stepper in the codebase is
`DeclarationCreatePage` (a one-sitting, three-step form). Onboarding is not one sitting — step 5
alone is 2–10 days of offline analysis, and configuration is revisited over weeks. A tabbed
detail page with a persistent readiness panel models "long-running, partially complete
configuration" correctly; a wizard would imply a linear flow that must be finished now.

**Permission-driven UI.** Mirror the backend role constant in `RoleRoute` exactly, as
`/finance/source-mapper` does. Note: the Source Mapper ships with **no** DACVM target key, so
adding one here would be a new precedent — recommend matching the existing pattern
(`RoleRoute` + role-derived capabilities) and leaving DACVM alone.

Reuse: `DataTable`, `StatusBadge`, `EmptyState`, `TableSkeleton`, `ReadOnlyBanner`, `Sheet`,
`Form`, `Select`, `Alert`, `Tabs`, `sonner` toasts, `lucide-react` icons.

---

## 12. Test strategy — the fake second source

The acceptance test of the whole platform, sanctioned by
`MULTI_TEMPLE_FINANCE_ARCHITECTURE.md` ("a synthetic second source") and specified in
`TEMPLE_FINANCE_ONBOARDING.md` §4 as **Temple B**: PostgreSQL, `PUSH_AGENT`, amounts in paise,
**has expenses**, **no Nirantara**, no precious metals, coverage from FY2022-23.

Use that spec verbatim — it was designed to be maximally dissimilar to Kollur.

| # | Assertion | Level |
|---:|---|---|
| 1 | A source with a different technology, connector type and capability set onboards with no code change | Service, MySQL Testcontainers |
| 2 | Temple B declares `EXPENSE` available; Kollur does not; both render correctly | Service |
| 3 | Temple B declares no Nirantara; the capability is absent, not zero | Service |
| 4 | **Kollur's configuration is byte-identical before and after** | Service — the regression guard |
| 5 | Capability/source isolation: B's rows never surface on Kollur's reads | Service |
| 6 | Readiness blocks on missing source-of-truth; passes once declared | Service |
| 7 | Activation refused (422) while any blocker stands | Service |
| 8 | Activation permitted, audited, once clean | Service |
| 9 | Duplicate `(temple, systemCode)` → 409 | Service |
| 10 | Second source for one temple → refused per D-140-3 | Service |
| 11 | Ambiguous rules (A10) and unknown canonical value (A9) detected statically | Unit — no DB |
| 12 | Out-of-scope DC gets 404, not 403; AUDITOR reads statewide | Service + security |
| 13 | Write endpoints refuse the wrong role | `@WebMvcTest` |
| 14 | **No response contains `credentialRef`** | Controller — serialise and assert absence |
| 15 | **The registry runtime still has no connector bean** | `RegistryRuntimeContextTest` must stay green, unmodified |
| 16 | No dashboard, API-contract or canonical-model change was required | Manual review checkpoint |

**Harness:** `FinanceApiTestBase`-style MySQL 8.0 Testcontainers with
`@Testcontainers(disabledWithoutDocker = true)`; `@WebMvcTest` + `@MockBean` for controllers
(including `@MockBean ScopeHelper` — `JwtAuthenticationFilter` needs it). Unit-testable
validation logic lives in a pure class with no Spring dependency, mirroring `RevenueAggregator`
and `RevenueMetricRollup`.

**A known hazard to design around:** `FinanceReportServiceImplTest` hit a
`Duplicate entry` failure because all methods share one container with no rollback and seeded a
platform-unique code literally. Seed Temple B's `systemCode` with a UUID suffix.

**Assertion 15 is non-negotiable** — `RegistryRuntimeContextTest`'s own javadoc calls it *"the
assertion that matters most in the whole finance platform"*. If FIN-140 ever requires weakening
it, the design is wrong.

---

## 13. Lifecycle

Three **orthogonal** axes. The task brief's suggested list (`DRAFT`, `PROBE_FAILED`,
`VALIDATION_FAILED`, `APPROVED`, `ACTIVE`, …) collapses all three into one enum, which would
produce impossible states (`PROBE_PASSED` + later config edit = silently stale) and duplicate
two mechanisms that already exist.

| Axis | Question | Mechanism | New? |
|---|---|---|---|
| **Configuration readiness** | Is the config coherent? | **Computed on read** (§6 Class A) | No — a pure function, never stored |
| **Live** | May the platform contact the source? | `fin_source_system.sync_enabled` | **No — already exists, already defaults 0** |
| **Approval** | Has a human signed this off? | D-140-1 | Undecided |

`PROBE_PASSED` / `VALIDATION_FAILED` are **evidence with a timestamp**, not states. A probe
result is a `fin_sync_batch` row; a validation verdict is recomputed every read. Storing either
as a status guarantees it eventually contradicts the configuration it describes.

### D-140-1 options

| | Approach | Cost | Assessment |
|---|---|---|---|
| **A** | **No formal approval.** Readiness gates activation; activation is audited | Nothing | Sufficient for today. `fin_source_of_truth_decl.approved_by/at` already provides sign-off where ADR-008 actually asks for it |
| **B** | Reuse `WorkflowInstance` with a new `WorkflowEntityType` | One enum value + seeded rows | Architecturally the "right" answer and the engine invites it — but it touches the governance module, which `IMPLEMENTATION_OWNERSHIP.md` puts outside finance, and imports 13 states of which ~4 apply |
| **C** | A finance-local `onboarding_status` column | One migration | **Rejected** — precisely the third status vocabulary `WorkflowStatus` was created to eliminate |

**Recommendation: A now, B when a second temple creates real demand for a review queue.** The
strongest argument for A is that approval already exists exactly where the architecture asks
for it: ADR-008 requires sign-off on the *source-of-truth declaration*, and that table already
has `approved_by`, `approved_at` and immutable versioning. Wrapping the whole source system in
a 13-state governance workflow would add ceremony above a gate that is already in the right place.

**Requires approval (D-140-1)** — option B touches another module's enum.

---

## 14. Implementation phases

| Slice | Objective | Backend | Frontend | Tests | Depends on | Completion criteria |
|---|---|---|---|---|---|---|
| **140-A** | **Source system registration + readiness.** The smallest useful vertical slice | `FinanceOnboardingController`, `SourceSystemAdminService(Impl)`, `OnboardingValidator` (pure), DTOs, `@Version` migration | List + detail + register form + readiness panel | Tests 1, 4, 6, 9, 10, 11, 12, 13, 14, 15 | D-140-1/3/4 | Register a source; readiness reports blockers; Kollur unchanged; registry still has no connector bean |
| **140-B** | **Capability matrix** | `PUT/GET …/capabilities`, `GET /capability-catalogue` | Matrix editor | Tests 2, 3, 5 | 140-A | Temple B's expense/Nirantara asymmetry renders correctly on both dashboards with no conditional code |
| **140-C** | **Source-of-truth declarations** | `POST/GET …/source-of-truth`, versioning + supersede | Editor emphasising rejected alternatives | A6, A7 coverage | 140-B | A new version never overwrites; readiness clears |
| **140-D** | **Activation** | `POST …/activation`, gated + audited | Activate button + refusal reason | Tests 7, 8, 16 | 140-C | Refused while blocked; audited when permitted |
| **140-E** | **Fake second source, end to end** | — | — | Full Temple B suite | 140-D | The architecture's acceptance test passes |
| **140-F** | **FIN-032 Class B probe** — **DEFERRED** | Worker poller (first production trigger), `DRY_RUN` execution, fingerprint comparison, `POST/GET …/probe` | Probe panel + polling | Probe pass/fail, drift | **FIN-040/041 (Q4)** | Deliberately not scheduled — see §6.2 |

**Smallest safe slice: 140-A.** It is vertical (migration → service → controller → screen →
test), it touches no existing behaviour, it adds nothing to the worker, and it delivers
standalone value even if nothing else ships: an administrator can see exactly what is missing
before a source goes anywhere near a temple database.

---

## 15. Non-goals

**Supported by existing project scope:**

- Automatic connector deployment — structurally impossible (§5).
- Self-service secret creation — Q5 unresolved; the worker reads its own environment.
- Direct source DB access from the registry — ADR-001, test-enforced.
- Real Kollur extraction — FIN-041, blocked on Q4.
- Precious metals (FIN-110), Nirantara (FIN-120) — both need real extraction first.
- Service-level revenue aggregation — FIN-071 is BLOCKED (FIN-D-069); no fact has ever carried a `service_id`.
- Historical restatement — FIN-070B D3 resolved as Option A (facts immutable).
- Monitoring (FIN-160), load testing, TiDB verification, permanent secret store (Q5).
- Triggering a re-run after a config change — FIN-D-066 holds; FIN-054A's D4 remains out of scope.

**Proposed boundaries requiring approval:**

- **One source system per temple** — D-140-3.
- **No formal approval workflow** — D-140-1.
- **The generic `JdbcTableConnector`** — promised by ADR-004 and the onboarding runbook, implemented nowhere. Out of scope for FIN-140, but worth flagging plainly: **until it exists, "onboard a simple source with configuration only" is not achievable** — every source still needs a deployed connector class. It is the single highest-leverage follow-up to this task.

---

## 16. Open decisions

| ID | Decision | Recommendation | Blocks |
|---|---|---|---|
| **D-140-1** | Onboarding approval: none / `WorkflowInstance` / local column | **A — none now.** Readiness gates activation; ADR-008 sign-off already lives on the source-of-truth row. Revisit when a review queue is real | 140-A |
| **D-140-2** | Who registers and activates a source system? | **`ADMIN_ONLY` for register/activate** (matches `SystemConfigServiceImpl`; deciding which external database feeds published figures is platform-level), **`CAN_ACT_DC` for capabilities and source-of-truth** (a statement about a temple in the DC's district) | 140-A |
| **D-140-3** | Second source per temple: refuse, or widen the constraints? | **Refuse, with a clear message.** D9 stays open; Option A is reversible, Option B bakes an unanswered semantic into published data | 140-A |
| **D-140-4** | `API_CONTRACT` §7.5 forbids returning `connectorBean`, `credentialRef`, `sourceDatabaseName` — onboarding must write them | **`credentialRef` write-only** (return `credentialRefSet: boolean`); **`connectorBean` and `sourceDatabaseName` readable by the write role only**; amend §7.5 to scope the exclusion to *reporting* endpoints | 140-A |
| **D-140-5** | Should FIN-140 re-apply Kollur's configuration through the new path? | **As a test, yes; as a migration, no.** V111 stays. But test 4 should prove the new path *could* produce Kollur's configuration, since V111 line 30 already names FIN-140 as the fallback | 140-E |
| **D-140-6** | Is FIN-032's Class B probe in scope now? | **No.** Defer to FIN-040/041. Building transport for a constant answer is infrastructure without a product | 140-F |

Next free identifiers if these are adopted: decisions **FIN-D-073** onward; limitations **74** onward.

---

## 17. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **A dead-end UI** — an onboarding flow whose Activate button can never lead to real data, because no connector exists | The readiness panel states plainly that no connector is deployed and what that means. Do not hide it behind a spinner |
| R2 | The capability-matrix write silently overwrites Kollur's reviewed declarations, including the 19 hand-written user-facing reasons | Whole-matrix upsert in one transaction, audited, with `@Version`; test 4 asserts Kollur byte-identical |
| R3 | D-140-1 option B touches the governance module, outside finance's ownership | Recommendation A avoids it entirely |
| R4 | Readiness is computed, so it can pass and then silently go stale after a mapping edit | Never persist a verdict; recompute on read; show the computation time |
| R5 | An onboarding screen leaks integration topology (which DB, which bean) to four roles | D-140-4 restricts those fields to the write role |
| R6 | Scope creep into building the worker's first production trigger | D-140-6 defers Class B explicitly |
| R7 | FIN-X-002 — no full-context test boots on the `test` profile | Existing boundary tests already work around it with `@TestPropertySource`; copy that |

### Limitations this plan does not fix

`uk_ftc_temple_capability` / `uk_fsd_temple_service` single-source assumption (limitation 67, D9) ·
the `JurisdictionGuard.assertDistrictScope` AUDITOR defect (routed around, not fixed) ·
missing-query-parameter → 500 (limitation 62, application-wide) · FIN-X-001 / FIN-X-002.

---

## 18. Verdict — and what was decided

**PLAN COMPLETE.** All six open decisions were answered on 2026-09-23 and **slice 140-A is
implemented and verified**; the rest of §14 remains as planned.

| ID | Decision as approved | Recorded as |
|---|---|---|
| D-140-1 | **No approval workflow.** Readiness gates; the existing `approved_by`/`approved_at`/version fields on the source-of-truth row carry sign-off. `WorkflowInstance` not introduced | — (no code needed) |
| D-140-2 | **SUPER_ADMIN only** for registration, activation *and* the reads, for this slice. No broader roles yet | FIN-D-076 |
| D-140-3 | **Refuse a second source per temple.** D9 stays open; neither unique key widened | FIN-D-074 |
| D-140-4 | **`credentialRef` write-only**, returned by nothing; connector bean and source database name restricted to the administrator endpoint; `API_CONTRACT` §7.5 scoped accordingly | FIN-D-075 |
| D-140-5 | **Kollur untouched.** No migration re-applied or rewritten; the new path is proved with a synthetic second source instead | — |
| D-140-6 | **Probe deferred.** No worker change, no connector-package change, registry-side validation only | — |

Two decisions the implementation added, neither of which the plan had anticipated:
**FIN-D-077** (readiness has three values, and `NOT_READY` is not one of them — it names the same
state as `BLOCKED`) and **FIN-D-078** (possible mapping ambiguity warns and never blocks, because
blocking would refuse the first onboarded source's own deliberately-reasoned configuration).

Delivered: `V120`, `OnboardingReadinessValidator`, `SourceSystemAdminService(Impl)`,
`FinanceOnboardingController`, four DTOs, `frontend/src/features/finance-onboarding/`. Verified at
63 backend tests and 30 frontend tests of its own, a 634-test finance regression, and the
23-test worker-boundary suite still green. See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)
Phase 13.

**Slice 140-C, source-of-truth declarations, is also implemented and verified.** It needed no
migration at all: §12's schema review was right that the table already carries a domain version,
an effective window and the sign-off columns. The one thing the plan did not anticipate is
**FIN-D-083** — this table gets no `@Version` optimistic lock, unlike the two tables 140-A and
140-B added one to, because it is append-only and the race it actually has is version assignment,
which `uk_fsotd_source_metric_version` already catches. **FIN-D-084** (the caller states which
version they supersede), **FIN-D-085** (`effectiveTo` derived, `effectiveFrom` never future-dated)
and **FIN-D-086** (sign-off is the two existing columns, with a readiness warning left as a
*proposed* decision because it would change the first onboarded source's verdict) complete the
set. D-140-1 stands unchanged: still no workflow.

**Slice 140-D, activation, is also implemented and verified**, again with no migration. §13's
state model was right that `sync_enabled` already is the concept — *"may the platform contact the
source?"* — so **FIN-D-087** reuses it and adds nothing, and option C from §13 stays rejected. The
endpoint is `POST …/activation` taking a desired end state rather than a toggle, which makes it
idempotent and is why it carries no `version`. **FIN-D-088** gates enabling on zero blocking
findings and leaves disabling ungated, with the no-op short-circuiting before the check.
**FIN-D-089** reports unsigned declarations as an activation warning while still not making
approval a gate, so FIN-D-086 remains open and unchanged.

What 140-D deliberately does **not** deliver is any operational effect: nothing reads
`sync_enabled`, no connector exists, and the response says so in three fields rather than
implying otherwise.

**§15's flag has since been cleared.** The generic `JdbcTableConnector` was implemented as
**FIN-033** — worker-side, read-only, with no temple-specific SQL and no new driver (FIN-D-090…093).
References to it as "implemented nowhere" in §1, §2 and §15 describe the state at the time this
plan was written and are left unedited, as the rest of §1–17 is. What remains between this screen
and a real onboarding is no longer the connector: it is a worker trigger, and Q4.

**And the trigger has since been built too.** **FIN-058** delivers `ManualSyncTrigger`: the worker
now creates a `fin_sync_batch` and runs `FinancePipelineOrchestrator`, gated on `sync_enabled` and on
readiness recomputed at the moment of the run (FIN-D-094…098). §7's *"no production trigger exists at
all"* and §6.2's risk R6 are therefore resolved — in a later, separate task, exactly as this plan
insisted they should be rather than inside a 140 slice.

Two things this does **not** change about the plan. **Activation is still not execution:** the
`POST …/activation` endpoint of §11 is untouched, and setting `sync_enabled = 1` still has exactly
one effect — the trigger will no longer refuse. And **140-F stays deferred**: FIN-058 is manual-only,
with no worker poller and no `DRY_RUN` execution, so the Class B probe still waits on Q4.

**And the trigger now has an operator route too.** **FIN-059** delivers `ManualSyncCommandRunner`, a
CLI command on the worker process (`trm.finance.sync.command=run`,
`trm.finance.sync.source-system-id=<id>`) that calls the unmodified `ManualSyncTrigger` exactly once
and exits with a code describing the outcome. No endpoint was added anywhere; the worker stays
non-web. This closes the "operator-facing route" gap this section used to list, and it changes
nothing else this plan describes:

- **Configuration** (source system, capabilities, source-of-truth, mapping) is still the screens
  §5–§10 describe, done through the registry API, unchanged by this task.
- **Activation** (`sync_enabled`) is still the permission §11–§13 describe: a switch that authorises
  a future run and starts nothing by itself, unchanged by this task.
- **Execution** is the new thing FIN-058 made possible and FIN-059 made reachable: an operator (or a
  script standing in for one, today) explicitly starting one run of one source system, once, from
  outside a test.

None of this brings the platform closer to being ready for Kollur specifically. The CLI can start a
sync for any source whose configuration is complete and whose readiness is clean — a synthetic
source can be run today — but Kollur has no network path (Q4), no SQL Server driver, and no bespoke
connector, so invoking this command against Kollur's source system id would still be refused, at
best, or unable to reach anything, at worst.

What now remains between this screen and a real onboarding is Q4 (a network path), Q5 (a credential
store), and — for the first onboarded source specifically — a SQL Server driver and a bespoke
connector. The operator-facing route itself is no longer on this list.

---

## 19. Files

**Created by this planning task:** `docs/FIN-140_ONBOARDING_PLAN.md` (this file).

**Modified:** none. No code, no migration, no tracking document, no ADR. Nothing committed.

**Would be created by slice 140-A, for reference:**
`V120__finance_config_optimistic_locking.sql` ·
`service/finance/onboarding/{SourceSystemAdminService,OnboardingValidator,ReadinessIssue}.java` ·
`service/impl/finance/SourceSystemAdminServiceImpl.java` ·
`controller/finance/FinanceOnboardingController.java` · request/response DTOs under
`dto/{request,response}/finance/` · `frontend/src/features/finance-onboarding/**` ·
matching tests. **Would be modified:** `rootReducer.ts`, `store.ts`, `renderWithProviders.tsx`,
`routes/index.tsx`, `routePaths.ts`, `Sidebar.tsx`, and — per D-140-4 — `docs/finance/API_CONTRACT.md`.

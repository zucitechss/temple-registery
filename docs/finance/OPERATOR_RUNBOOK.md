# Manual Finance Sync Operator Runbook

How an authorized operator safely executes one manual finance synchronization, using the
CLI that **FIN-058** (`ManualSyncTrigger`) and **FIN-059** (`ManualSyncCommandRunner`,
FIN-D-095/FIN-D-099) already implemented and tested.

**This is a documentation-only companion to already-shipped code.** It adds no new
behaviour. Where the implementation does not do something (a role check, a retry, a
cancellation path), this runbook says so rather than describing one that does not exist.

> **Read this first:** this runbook is not, today, instructions for running a sync against
> Kollur. See [Current Kollur Readiness](#current-kollur-readiness).

---

## 1. Operator Role

The codebase defines application roles for the **registry web app** (`RoleConstants`:
`SUPER_ADMIN`, `DISTRICT_COLLECTOR`, `DC_STAFF`, `TEMPLE_AUTHORITY`, `AUDITOR`, `VIEWER`).
It defines **no role for the CLI itself** — `ManualSyncCommandRunner` runs inside the
sync-worker process and has no `@PreAuthorize`, no login and no user identity. Whoever can
start that process with the two `trm.finance.sync.*` properties can trigger a run.

| Question | Answer |
|---|---|
| Who is allowed to initiate a manual sync? | Not enforced in code. Whoever has the access to start/restart the sync-worker process with these properties. **Operational ownership must be decided by the deployment/government operations team.** |
| Who is responsible for verifying source readiness? | The registry's onboarding/readiness screens are gated `@PreAuthorize(RoleConstants.ADMIN_ONLY)` — i.e. `SUPER_ADMIN`. The CLI does not re-expose readiness; it only re-checks it at run time and refuses (`READINESS_BLOCKED`) if a finding is blocking. |
| Who owns the source database/network connectivity? | Not defined in the repository (this is Q4). **Operational ownership must be decided by the deployment/government operations team.** |
| Who investigates pipeline failures? | Not defined in the repository. **Operational ownership must be decided by the deployment/government operations team.** |
| Who investigates reconciliation/publication failures? | Not defined in the repository. The record to inspect is `fin_reconciliation_result`, regardless of who does it. |

Activating a source for sync (`sync_enabled = true`) is a separate, already-gated
`SUPER_ADMIN` action in the registry app (`FinanceOnboardingController`'s activation
endpoint). It authorises a future run; it does not perform one. See
[§9 — One-Shot Behaviour](#9-one-shot-behaviour).

---

## 2. Pre-Run Checklist

Complete all of these before running the command.

### Worker

- [ ] The artifact being started is the correct one and this is the sync-worker profile: `--spring.profiles.active=sync-worker`.
- [ ] The process is being started deliberately for this one run — it is one-shot when a command is given, not a service you leave running to "watch for work" (there is no polling to watch for).
- [ ] The worker-level master switch is enabled: `trm.finance.sync.enabled=true` (`SyncWorkerProperties.enabled`, defaults to `false`). If it is `false`, every run is refused with `WORKER_DISABLED` before anything is read.
- [ ] No scheduler or polling has been added to this deployment. There is none in the code (FIN-D-094) and none should be layered on outside it.

### Source System

- [ ] The source system id you intend to pass exists and has not been soft-deleted (`fin_source_system.deleted`). An unknown or deleted id is refused with `NO_SUCH_SOURCE`.
- [ ] You have the **correct** source-system id — the CLI accepts nothing else (no temple id) to identify the target, so passing the wrong numeric id silently targets a different source.
- [ ] The source has been intentionally activated: `sync_enabled = true`, set via the registry's `SUPER_ADMIN`-gated activation endpoint. If it is `false`, the run is refused with `NOT_ENABLED_FOR_SYNC`.

### Readiness

- [ ] The onboarding readiness report for this source has **zero BLOCKED findings** at the moment you intend to run. It is recomputed by the trigger itself at run time (not cached from when the source was activated), so a finding introduced after activation still blocks.
- [ ] Required source-of-truth declarations and mapping rules exist for the capability being synced (`REVENUE` is currently the only one the pipeline extracts).
- [ ] Capabilities are configured as intended for this source.
- [ ] Any `WARNING`-level findings have been read and understood. **A warning does not block execution** — `ReadinessStatus.WARNING` is "legal but probably wrong," not "unsafe to run." Only `BLOCKED` prevents a run.

---

## 3. Q4 — Network Path Prerequisite

The CLI does **not** solve network connectivity. Before any real (non-synthetic) sync:

```
sync-worker process
        |
        v
  network path   <-- Q4, unresolved
        |
        v
  temple database
```

**Q4 is unresolved.** No endpoint, host, or URL for reaching a live temple system is
configured anywhere in this codebase, and `ConnectorType` still treats all connection
mechanisms as equally undecided. Whether that path exists, is approved, and is verified
reachable is an infrastructure/network decision, not something this CLI can establish.

**Q4 network path must be approved, configured, and verified by the responsible
infrastructure/network team.**

The existing synthetic end-to-end test (`ManualSyncTriggerE2ETest`) runs the whole pipeline
against an in-memory **H2** database standing in for a source. It proves the pipeline
executes correctly; it proves **nothing** about reachability to Kollur, or to any real
temple network.

---

## 4. Q5 — Credential Prerequisite

The CLI accepts only a source-system id — never a username, password, JDBC URL, or
credential reference. Credential resolution happens entirely worker-side, through the
existing credential provider, and is invisible to the operator issuing the command.

**Q5 (the permanent production credential-store architecture) is unresolved.** Today,
`credential_ref` values in `fin_source_system` are aliases with no credential behind them
anywhere in Git, YAML, SQL, or the database — by design, and verified by a test that scans
every persisted value in that table for credential/endpoint patterns.

Consequences for this runbook:

- Credentials are worker-side only; the operator never sees or supplies one.
- Credentials must never be placed in `application-*.yml`.
- Credentials must never be printed to logs (see [§8 — Credential / Secret Safety](#8-credential--secret-safety)).
- **Real Kollur execution cannot safely proceed until Q5 is resolved** with an actual
  production secret store. Nothing today should be read as an interim answer to that.

---

## 5. Exact CLI Procedure

The currently implemented command (`ManualSyncCommandRunner`, reading from
`Environment`, so all three forms below are equivalent):

```bash
SOURCE_SYSTEM_ID=123

java -jar temple-registry-backend.jar \
  --spring.profiles.active=sync-worker \
  --trm.finance.sync.command=run \
  --trm.finance.sync.source-system-id=$SOURCE_SYSTEM_ID
```

`123` above is a **placeholder**, not a real source. Substitute the id you verified in
[§2](#2-pre-run-checklist).

| Argument | Meaning |
|---|---|
| `--spring.profiles.active=sync-worker` | Starts the process in the worker profile — non-web, the only profile this CLI is wired into. |
| `--trm.finance.sync.command=run` | The only recognised value is `run`. Anything else is refused (`EXIT_REFUSED`), not ignored. |
| `--trm.finance.sync.source-system-id=<id>` | The one thing the operator identifies. Must be a positive number. |

Environment-variable form (property names upper-cased, dots to underscores — Spring
Boot's standard relaxed binding, actually exercised by this implementation):

```bash
SPRING_PROFILES_ACTIVE=sync-worker \
TRM_FINANCE_SYNC_COMMAND=run \
TRM_FINANCE_SYNC_SOURCE_SYSTEM_ID=123 \
java -jar temple-registry-backend.jar
```

No other argument form is supported. Do not invent one.

---

## 6. Execution Flow

```
Operator
   |
   v
CLI (the command above)
   |
   v
ManualSyncCommandRunner        <- reads the two properties, validates only those
   |
   v
ManualSyncTrigger.runNow(id)   <- every decision below is made here, not in the CLI
   |
   v
worker enabled?  (trm.finance.sync.enabled)
   |
   v
source enabled?  (fin_source_system.sync_enabled)
   |
   v
readiness blocked?  (recomputed now, not cached)
   |
   v
source row lock + duplicate/active-batch check
   |
   v
create PENDING batch
   |
   v
FinancePipelineOrchestrator
   |
   v
extraction -> JdbcTableConnector -> staging -> validation
   |
   v
mapping -> normalization -> canonical load
   |
   v
reconciliation -> publication
```

The CLI performs none of the steps below "ManualSyncTrigger.runNow" itself. It only asks
the trigger, once, and reports what came back.

---

## 7. One-Shot Behaviour

- One command = one explicit run request. There is no polling, no cron, and no automatic
  scan of `sync_enabled` across sources anywhere in this codebase.
- **`sync_enabled = true` means "this source is authorised for a future sync,"** not "run
  now." Activation and execution are separate actions, on purpose (FIN-D-094).
- There is no automatic retry. A failed or refused run stays exactly that until an operator
  issues the command again.
- If neither property is set, the worker starts and stays idle — the same behaviour as
  before FIN-059 existed.

---

## 8. Credential / Secret Safety

Never place any of these in the command, environment, or shell history for this process:

- a password
- a username, if it is treated as a secret in your deployment
- a JDBC URL or connection string
- a secret value or secret alias, if considered sensitive
- a credential token

The command accepts, and should ever be given, only the command word `run` and a numeric
source-system id. `ManualSyncCommandRunner`'s own logging is built only from a batch id, a
source-system id, and a domain exception's own message (e.g. `SyncRefusedException`,
`PipelineFailedException`) — never a raw driver/cause message, which is where a JDBC
exception could otherwise carry a connection detail.

Operationally:

- Do not paste secrets into shell history, application YAML, Git, logs, screenshots, or
  support tickets.
- If a failure message ever appears to contain something that looks like a credential or
  connection string, treat that as a defect to report, not something to work around by
  hiding the output.

---

## 9. Batch / Watermark Safety

Every run creates a `fin_sync_batch` row. The system distinguishes **the latest batch**
from **the latest *successful* batch** (FIN-D-005), and only the latter advances
`watermark_after`.

**Practical consequence:** if a run fails, the next run derives its window from the last
**SUCCESSFUL** batch, not the failed one. Nothing is silently skipped, and nothing needs to
be manually rewound — the next invocation of the same command re-covers the failed
window automatically.

---

## 10. Duplicate Runs

If an operator (or two operators) tries to start a run for a source that already has a
batch `PENDING` or `RUNNING`, the second request is refused with `ALREADY_IN_PROGRESS`.

This is enforced with a **database row lock** (`SELECT ... FOR UPDATE` on
`fin_source_system`) — not a JVM lock, and not merely a check-then-act query — so it is
still correct even with a second worker process running elsewhere. Two requests cannot
both create competing active batches for the same source; the losing request receives the
`ALREADY_IN_PROGRESS` outcome and creates no batch of its own.

---

## 11. Success Procedure

After a command exits `0` (`EXIT_SUCCESS`):

- The console output names the batch id, batch ref, and final status (`SUCCESS`).
- Retain that batch id / batch ref (a UUID, safe to quote in a support conversation) for
  audit and troubleshooting — see [§13](#13-audit--record-keeping).
- Reconciliation and publication completed as part of this same run; a plain `EXIT_SUCCESS`
  means no disagreement blocked publication (see the reconciliation exit code below if one
  did).

This repository does not currently document a dashboard or SQL query for this step beyond
what is already in the console output and the `fin_sync_batch` row itself; none is invented
here.

---

## 12. Failure Procedure

| # | Situation | Exit code | Retry? | What to check | Who investigates |
|---|---|---|---|---|---|
| 1 | Invalid command (not `run`) | 2 (`EXIT_REFUSED`) | Fix input, then retry | The `trm.finance.sync.command` value | Whoever issued the command |
| 2 | Source not found / soft-deleted | 2 (`EXIT_REFUSED`) | Fix input, then retry | The source-system id | Whoever issued the command |
| 3 | Worker disabled (`trm.finance.sync.enabled=false`) | 2 (`EXIT_REFUSED`) | Only after enabling the switch | The worker instance's own config | Deployment operator |
| 4 | Source not activated (`sync_enabled=false`) | 2 (`EXIT_REFUSED`) | Only after activation | Registry onboarding/activation screen | `SUPER_ADMIN` |
| 5 | Readiness blocked | 2 (`EXIT_REFUSED`) | Only after the blocking finding is resolved | The readiness report's finding code(s) | Whoever owns that source's configuration |
| 6 | Already in progress | 2 (`EXIT_REFUSED`) | Wait for the existing batch to finish; do not force a second run | The existing `PENDING`/`RUNNING` batch for this source | See [§14 — Rollback / Abort](#14-rollback--abort) if it appears stuck |
| 7 | Connector/source database failure | 1 (`EXIT_FAILURE`) | **Investigate the failed batch and source condition before retrying.** | `fin_sync_batch.last_failure_reason` for this batch | Undefined in-repo — deployment/operations team |
| 8 | A pipeline stage failed (validation/mapping/normalization/load) | 1 (`EXIT_FAILURE`) | **Investigate the failed batch and source condition before retrying.** | `fin_sync_batch.last_failure_reason`, which stage failed | Undefined in-repo — deployment/operations team |
| 9 | Reconciliation disagreement | 3 (`EXIT_RECONCILE_FAILED`) | Not a retry situation by itself — the data loaded | `fin_reconciliation_result` for the disagreement | Undefined in-repo |
| 10 | Publication blocked | 3 (`EXIT_RECONCILE_FAILED`) | Same signal as #9 — loading succeeded but publication of the affected year(s) is withheld | `fin_reconciliation_result` | Undefined in-repo |

For every `EXIT_FAILURE` and `EXIT_RECONCILE_FAILED` case: **do not blindly retry.**
Nothing in the implementation classifies a failure as safe to retry automatically — the
batch's own `last_failure_reason` is the starting point for deciding whether re-running is
appropriate.

---

## 13. Rollback / Abort

**No automated stale-batch recovery procedure is currently implemented.** If a worker
process is killed between creating a batch and finishing it, that batch is left `PENDING`
or `RUNNING`, and every later run request for that source is refused with
`ALREADY_IN_PROGRESS` until the situation is resolved.

There is no cancellation API and no repository-documented, approved procedure for directly
editing that row. This runbook does not invent one. Treat a batch stuck in this state as an
open gap to escalate, not something to work around with an ad hoc database update.

---

## 14. Audit / Record-Keeping

Distinguish two things:

**Application audit persistence** — already handled, without operator effort. Every run
leaves a `fin_sync_batch` row: id, `batch_ref` (UUID), status, sync type, window, retry
count, and `last_failure_reason` on failure. `triggered_by` records `MANUAL`, but **not**
which person or script ran the command — the entity has no operator-identity field.

**Operational record-keeping** — not implemented anywhere, and therefore the operator's own
responsibility. At minimum, record outside the application:

- date/time of the run
- who ran it (the application does not capture this)
- source-system id
- resulting batch id / batch ref
- exit code and result

---

## 15. Current Kollur Readiness

- Kollur is **not connected**.
- Kollur credentials have **not** been used.
- **No** Kollur data has been read.
- `mssql-jdbc` is **not** yet added as a dependency.
- A Kollur connector is **not** yet implemented.
- **Q4** (network path) remains unresolved.
- **Q5** (credential store) remains unresolved.

**This runbook describes the operator mechanism, not instructions for running a sync
against Kollur today.** It becomes applicable to Kollur only once the real infrastructure
prerequisites in §3 and §4 are approved and implemented.

---

## 16. Synthetic Test vs. Real Execution

`ManualSyncTriggerE2ETest` — the existing, passing end-to-end proof — demonstrates that the
worker trigger, batch lifecycle, orchestrator, connector integration, staging, mapping,
normalization, canonical loading, reconciliation, and publication all work correctly,
**end to end, against a synthetic H2 source.**

It does **not** prove:

- Kollur network reachability
- SQL Server connectivity
- production credentials working
- Kollur's source schema being compatible with the generic connector
- real Kollur data being correct once loaded

Do not read a synthetic-source pass as evidence the pipeline is ready for Kollur
specifically.

---

## Related Documents

- [`HANDOFF.md`](HANDOFF.md) — FIN-058/FIN-059 sections: engineering handoff notes, exact
  exit-code table, and what to know if you are modifying this code.
- [`IMPLEMENTATION_DECISIONS.md`](IMPLEMENTATION_DECISIONS.md) — FIN-D-094 through
  FIN-D-099: why each design choice in this pipeline was made.
- [`FIN-140_ONBOARDING_PLAN.md`](FIN-140_ONBOARDING_PLAN.md) — the Configuration /
  Activation / Execution distinction this runbook assumes.

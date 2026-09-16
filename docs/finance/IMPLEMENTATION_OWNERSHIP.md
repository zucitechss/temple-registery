# Finance Component Ownership and Boundaries

Who owns what, and — more usefully — which knowledge each component is **forbidden** to
hold. The boundaries matter more than the ownership: they are what makes onboarding a
second temple a configuration exercise rather than a rewrite.

---

## 1. Layer Map

```
Temple operational DB  (external, not ours)
        |
        v
[ SYNC WORKER PROCESS ]  --spring.profiles.active=sync-worker
  connector.finance.*        temple-specific SQL lives here and nowhere else
  service.finance.sync.*     extract, validate, map, normalize, load
        |
        v
  Registry database (TiDB) — fin_* canonical tables
        |
        v
[ REGISTRY PROCESS ]  --spring.profiles.active=prod
  service.finance.report.*   reads canonical + aggregates only
  controller.dc.DcFinanceController
        |
        v
  Frontend finance dashboard
```

Both processes are built from the same artifact. Only the worker holds source credentials
and only the worker can reach a temple network.

---

## 2. Ownership

| Component | Package | Runs in | Owns |
|---|---|---|---|
| Enums | `entity.finance.enums` | both | Canonical vocabulary |
| Config entities | `entity.finance` | both | Source system, capability, source-of-truth, mapping |
| Operational entities | `entity.finance` | both | Sync batch, sync error, reconciliation |
| Repositories | `repository.finance` | both | Data access |
| Connector API | `connector.finance` | both (API) | The contract every temple implements |
| Connector impls | `connector.finance.impl` | **worker only** | Temple-specific extraction |
| Sync services | `service.finance.sync` | **worker only** | Pipeline orchestration |
| Report services | `service.finance.report` | **registry only** | Canonical reads, availability composition |
| Controllers | `controller.dc` | **registry only** | HTTP, RBAC, DTO mapping |

---

## 3. Knowledge Boundaries

This is the part that must not erode.

| Knowledge | Permitted in | Forbidden in |
|---|---|---|
| `KOLSOHAM_LOCAL`, `DailySevaNew`, `HKanikeItems`, `TempleCode 43` | `KollurFinanceConnector` only | Every other class, all DTOs, all SQL migrations, the entire frontend |
| SQL Server dialect | Connector impls | Everything else |
| Source credentials | Sync worker runtime config | Database, API responses, logs, source code |
| Canonical model (`fin_*`) | Sync + report services | Connectors treat it as output only |
| Availability semantics | Report services, DTOs, UI | Not inferred anywhere from row counts |

**The single most important rule:** the Finance Dashboard must never know that Kollur uses
`DailySevaNew`. If a future change makes the dashboard behave differently for temple
300001 than for any other temple, the abstraction has failed, and the fix is in the
backend, not a frontend conditional.

The current `DcTempleProfilePage.tsx` gate —
`.filter((tab) => tab.v !== 'finance' || id === FINANCE_DASHBOARD_TEMPLE_ID)` — is exactly
this failure in its present form. FIN-093 removes it: the finance tab becomes visible when
the temple has finance capabilities, which is a property of data, not of an id hardcoded
in a component.

---

## 4. Adding a Second Temple

What must be written: one connector class, and configuration rows.

What must **not** change: the canonical model, the aggregation logic, the Finance APIs,
the DTOs, the dashboard, or any existing connector.

If onboarding a temple requires touching anything in that second list, the design has
regressed and the change should be rejected rather than merged. This is the acceptance
test for the whole platform, and it is scheduled as a real phase (FIN-140), not left as an
aspiration.

---

## 5. Test Ownership

| Level | Owns | Location |
|---|---|---|
| Repository | Entity mapping, derived-query validity, meaningful defaults | `repository/finance/*Test` |
| Migration vs entity | Schema agreement | `ApplicationContextIntegrationTest` (currently blocked — FIN-X-001) |
| Connector | Extraction correctness against a fixture | `connector/finance/*Test` |
| Pipeline | Validation, mapping, idempotent load | `service/finance/sync/*Test` |
| API | Contract compliance, availability envelope, no source vocabulary | `controller/dc/*Test` |

The API-level test should assert the absence of source vocabulary in serialised responses.
That is cheap to write and is the only check that reliably catches a leak introduced by
someone adding a convenient debugging field.

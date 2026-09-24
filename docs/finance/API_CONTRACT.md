# Finance API Contract

**Status:** FIN-080/081/082/083/084 implemented, except `revenue/sevas` (blocked on FIN-071,
FIN-D-069) and `cancellations`/`sync-status` (not yet built).
**Base path:** `/api/v1/dc/temples/{templeId}/finance`
**Auth:** existing JWT; route guard `RoleConstants.CAN_READ_FINANCE_CONFIG` (corrected from
`IS_DC_ROLE` — that excludes `AUDITOR`, which FIN-070A's isolation test matrix requires reads
statewide; see FIN-D-071). No DACVM field-level filtering is wired: nothing these endpoints
return is PII (§6), so there is nothing yet for it to mask.

---

## 1. Rules That Constrain Every Endpoint

1. **No source vocabulary.** No response field, enum value or error message may contain
   `DailySevaNew`, `HKanikeItems`, `TempleCode`, `KOLSOHAM_LOCAL`, `SevaCode`,
   `BillCancled`, or any other source identifier. The dashboard must remain unable to tell
   which system a temple uses.
2. **No entity leaks.** Controllers return DTOs. JPA entities are never serialised.
3. **Amounts are nullable.** A metric that is unavailable returns `null` with an
   availability status and a reason. It never returns `0`.
4. **Every payload is self-describing.** A response carries enough metadata that the UI can
   render it correctly without knowing anything about the temple.
5. **No source contact.** Every endpoint reads canonical and aggregate tables only,
   whatever the scope requested.

---

## 2. The Metric Envelope

Every numeric metric is wrapped. This shape is identical whether data is present or
absent, so consumers have one code path rather than two.

```json
{
  "value": 906162936.00,
  "unit": "INR",
  "availability": "AVAILABLE",
  "reason": null,
  "asOfDate": "2026-07-26",
  "reconciliation": "PASSED"
}
```

Unavailable:

```json
{
  "value": null,
  "unit": "INR",
  "availability": "NOT_AVAILABLE",
  "reason": "The source system does not record expenditure.",
  "asOfDate": null,
  "reconciliation": "NOT_AVAILABLE"
}
```

`availability` ∈ `AVAILABLE` · `PARTIALLY_AVAILABLE` · `NOT_AVAILABLE` · `NOT_APPLICABLE`
`reconciliation` ∈ `PASSED` · `FAILED` · `NOT_AVAILABLE` · `PENDING`

`reason` is user-facing copy authored during onboarding, rendered verbatim.

---

## 3. Data Freshness Block

Present on every response. Two distinct dates, deliberately never merged:

```json
{
  "dataFreshness": {
    "lastSyncedAt": "2026-09-16T02:00:00",
    "sourceDataThrough": "2026-07-26",
    "status": "STALE",
    "stalenessReason": "The source system has no records after 2026-07-26."
  }
}
```

`lastSyncedAt` is when the platform last succeeded in talking to the source.
`sourceDataThrough` is the latest date the source actually has data for. Kollur currently
shows a gap of several weeks between the two. Reporting only the first would present stale
data as current, which is the more dangerous of the two errors.

---

## 4. Endpoints

| Method | Path | Returns |
|---|---|---|
| GET | `/finance/capabilities` | What this temple can answer, with reasons |
| GET | `/finance/summary` | Headline metrics for a financial year |
| GET | `/finance/revenue/trend` | Revenue by financial year |
| GET | `/finance/revenue/monthly` | Revenue by month within a year |
| GET | `/finance/revenue/categories` | Split by canonical category |
| GET | `/finance/revenue/sevas` | Revenue and receipt count by service |
| GET | `/finance/cancellations` | Cancelled receipts for a period |
| GET | `/finance/reconciliation` | Verification status per period |
| GET | `/finance/sync-status` | Batch health (restricted) |

Common query parameters: `financialYear` (`2025-26`), `from`/`to` (ISO dates),
`categoryCode`, `page`, `size`.

### 4.1 `GET /finance/capabilities`

Drives what the dashboard renders. The UI composes itself from this rather than branching
on temple identity — which is what allows a second temple to be onboarded without a
frontend change.

```json
{
  "templeId": 300001,
  "capabilities": [
    {
      "capability": "REVENUE",
      "availability": "AVAILABLE",
      "reason": null,
      "coverageFrom": "2019-04-01",
      "coverageTo": "2026-07-26",
      "knownGaps": []
    },
    {
      "capability": "PRECIOUS_METAL_VALUE",
      "availability": "NOT_AVAILABLE",
      "reason": "The source records no valuation or purity for donated gold and silver.",
      "coverageFrom": null,
      "coverageTo": null,
      "knownGaps": []
    }
  ]
}
```

### 4.2 `GET /finance/summary?financialYear=2025-26`

```json
{
  "templeId": 300001,
  "financialYear": "2025-26",
  "grossRevenue":    { "value": 906162936.00, "availability": "AVAILABLE", "...": "envelope" },
  "netRevenue":      { "value": 901433104.00, "availability": "AVAILABLE", "...": "envelope" },
  "transactionCount":{ "value": 3950094,      "availability": "AVAILABLE", "...": "envelope" },
  "expenditure":     { "value": null, "availability": "NOT_AVAILABLE",
                       "reason": "The source system does not record expenditure." },
  "dataFreshness": { "...": "freshness block" }
}
```

`transactionCount` is receipts. It is **not** a devotee count and no field, label or
tooltip may imply otherwise — one devotee may buy several receipts and one receipt may
cover a family.

### 4.3 Multi-temple and district scope

```
GET /api/v1/dc/finance/summary?districtId=29&financialYear=2025-26
```

Rollups carry coverage metadata, because a district total that silently omits temples with
no data is a wrong number presented as a right one:

```json
{
  "scope": "DISTRICT",
  "districtId": 29,
  "coverage": { "templesInScope": 11, "templesReporting": 4, "templesNotAvailable": 7 },
  "grossRevenue": { "value": 1240553102.00, "availability": "PARTIALLY_AVAILABLE",
                    "reason": "4 of 11 temples in this district have finance data available." }
}
```

---

## 5. Errors

Existing `ApiResponse` envelope and `GlobalExceptionHandler` conventions apply.

| Situation | Status | Behaviour |
|---|---|---|
| Temple has no source system | 200 | All metrics `NOT_AVAILABLE`, reason given. Not a 404 — the temple exists |
| Capability not available | 200 | Envelope with `null` value and reason |
| Reconciliation failed for the period | 200 | Last good figures, `reconciliation: "FAILED"`, staleness surfaced |
| Temple not found | 404 | Standard error |
| Caller lacks permission | 403 | Standard error |

A failed reconciliation is deliberately not an error response. Returning 500 would hide
the figures entirely; returning the previous verified figures, clearly flagged, is more
useful and more honest.

---

## 6. What This Contract Excludes

No endpoint exposes: devotee names or contact details, receipt numbers, source table or
column names, source connection details, or credential references. None is required by any
catalogued report, and excluding them keeps personal data out of the central platform
altogether.

---

## 7. Administrative endpoints — source mapping (FIN-054A, IMPLEMENTED)

Sections 1–6 describe *reporting* endpoints, none of which exist yet. These are
**administrative** endpoints and are the first finance endpoints actually implemented. They
configure how a source system's values translate to canonical revenue categories; they read no
temple financial figure and publish none.

`FinanceMappingController` · `MappingAdminServiceImpl` · base path `/api/v1/finance`.

### 7.1 Endpoints

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/finance/source-systems` | Source systems the caller may administer, with active rule counts | read |
| GET | `/finance/source-systems/{id}/namespaces` | Staged field names this source has been observed to emit | read |
| GET | `/finance/canonical-values` | The revenue categories a rule may name | read |
| GET | `/finance/mapping-rules` | One source system's rules, paged and filtered | read |
| GET | `/finance/mapping-rules/{id}` | One rule | read |
| GET | `/finance/mapping-rules/unresolved` | What the newest batch could not classify, worst first | read |
| POST | `/finance/mapping-rules` | Create a rule | `CAN_ACT_DC` |
| PUT | `/finance/mapping-rules/{id}` | Update a rule | `CAN_ACT_DC` |
| PATCH | `/finance/mapping-rules/{id}/status` | Retire or reinstate a rule | `CAN_ACT_DC` |

Read = `CAN_READ_FINANCE_CONFIG` (`SUPER_ADMIN`, `DISTRICT_COLLECTOR`, `DC_STAFF`, `AUDITOR`).

`GET /mapping-rules` requires `sourceSystemId`. Optional: `mappingType`, `active`,
`canonicalValue`, `q`, `page`, `size`, `sort`. **`sort` accepts an allow-listed property name
only** — `sourceValue`, `canonicalValue`, `mappingType`, `priority`, `active`, `updatedAt`,
`createdAt`, each optionally `,asc` or `,desc`. Anything else is refused. No endpoint accepts SQL,
a table name, a column name or an order-by fragment.

### 7.2 The namespaced source value

A rule is stored as one string, `SEVA_CODE:430`, whose first colon separates the **staged field
the rule reads** from the **value it matches**. The API takes and returns the two halves
separately (`namespace`, `sourceValue`) and also reports the stored string (`storedValue`) and
whether it is readable (`wellFormed`).

A malformed stored value is **refused on write** and **reported, not hidden, on read** — an
existing rule that names no field can never match anything, and that is what an administrator
opens this screen to find. See FIN-D-062.

### 7.3 What a write does not do

Every write response carries:

```json
{ "rule": { … }, "historicalEffect": "…", "warnings": [ … ] }
```

`historicalEffect` is a fixed sentence stating that the change applies to **future pipeline runs
only** and that figures already published keep their classification until their batch is re-run.
**No endpoint triggers re-processing.** A client must display this at the point of edit; a user who
sees only "Saved" will reasonably conclude the dashboard has been corrected. See FIN-D-066.

`warnings` carries things that are legal but probably wrong — chiefly a namespace no staged payload
from that source has been observed to carry. Such a rule is saved and will never match anything
until the source emits that field.

### 7.4 Status codes

| Situation | Status | Note |
|---|---|---|
| Created | 201 | |
| Source system or rule outside the caller's scope | **404** | Never 403 — a distinguishable refusal enumerates every temple's integrations |
| Source value already mapped | 409 | Names the existing rule |
| Stale `version` | 409 | `OPTIMISTIC_LOCK_CONFLICT` |
| Malformed namespace, unknown canonical value, inert mapping type, bad `sort` | 422 | Well-formed request, semantically refused |
| Missing required field, bad length | 400 | Bean validation |
| Role not permitted | 403 | |

### 7.5 Excluded, deliberately

No endpoint **in this section, or in any reporting section**, returns a credential reference, a
source database name, a connector bean or any source-side connection detail, and none returns a
temple financial figure. `DELETE` was not implemented: deactivation covers retirement, and soft
delete would add a permission tier and an audit action no described flow needs.

**Scoped to reporting and mapping administration by FIN-D-075.** §8's onboarding endpoints must
necessarily accept the connector bean and the source database name, because configuring them is what
they exist for, and they are `ADMIN_ONLY` for that reason. **The credential reference remains
excluded everywhere without exception**: §8 accepts it on write and no response type in the
application has a field to return it.

### 7.6 Frontend consumer (FIN-054B)

`frontend/src/features/finance/` consumes §7 at `/finance/source-mapper`. Two notes for anyone
changing the contract:

- **`historicalEffect` is displayed, not paraphrased.** The screen shows the backend's sentence as
  the confirmation description. Changing its wording changes what operators are told.
- **`syncBatchId: null` is load-bearing.** It is how the screen distinguishes "not measured" from
  "nothing unresolved" and must not be omitted from the JSON when null.

`sort` is confined client-side to the same allow-list the service enforces, so a 422 for a bad sort
key should never be reachable from the screen.

**Known contract defect, application-wide:** a missing required query parameter (for example
`GET /finance/mapping-rules` with no `sourceSystemId`) returns **500, not 400**, because
`GlobalExceptionHandler` has no handler for `MissingServletRequestParameterException`. Same for an
unsupported method and an unmatched path. Recorded as HANDOFF limitation 62; not fixed here because
it affects every controller in the application.

---

## 8. Administrative endpoints — source system onboarding (FIN-140 slices 140-A through 140-D, IMPLEMENTED)

Registering which external system supplies a temple's financial data, and checking whether its
configuration is coherent. These read no temple financial figure and publish none.

`FinanceOnboardingController` · `SourceSystemAdminServiceImpl` · base path `/api/v1/finance`.

### 8.1 Endpoints

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | `/finance/source-systems` | Register a source system. Always created with `syncEnabled = false` | `ADMIN_ONLY` |
| GET | `/finance/source-systems/{id}` | Administrative detail | `ADMIN_ONLY` |
| PUT | `/finance/source-systems/{id}` | Update metadata. Version-checked | `ADMIN_ONLY` |
| GET | `/finance/source-systems/{id}/readiness` | Configuration readiness report | `ADMIN_ONLY` |

`ADMIN_ONLY` = `SUPER_ADMIN`, and the reads are administrator-only too. Stricter than §7's four-role
read because these responses carry the connector bean, the source database name and whether a
credential is configured — the operational topology of an integration, which §7's own summary type
excludes. See FIN-D-076.

`GET /finance/source-systems` (the list) belongs to §7 and is unchanged: four roles, and it returns
none of those three fields.

### 8.2 What no endpoint here does

**None activates a source system.** Readiness reports `activationAllowed`; nothing in slice 140-A
sets `sync_enabled`. Registration always creates the row switched off and no request field can
override that.

**None probes anything.** There is no "test connection". The runtime serving these endpoints holds
no connector and no credential provider, and a test asserts it never will (ADR-001), so a
connectivity check has nowhere to execute. `readiness` states this in its own payload rather than
leaving a reader to infer that a clean verdict means a working integration —
`connectivityVerified` is `false` on every response, with `connectivityNote` explaining what was and
was not checked.

**None deletes.** Nothing in the described flow needs it, and a source system with facts behind it
is not a row that should be removable from a form.

### 8.3 The credential alias

Accepted on write, returned by nothing. `SourceSystemDetailResponse` carries
`credentialRefSet: boolean` and **has no field for the value** — absent rather than masked, so no
future serialiser or log line can carry it. Audit detail records `credentialRefSet=true|false`.

Because the current value can never be read back, `PUT` treats it as tri-state: **omitted** keeps
the stored alias, **a value** replaces it, **an empty string** clears it. See FIN-D-075.

### 8.4 Readiness

```json
{
  "status": "BLOCKED",
  "activationAllowed": false,
  "syncEnabled": false,
  "connectivityVerified": false,
  "connectivityNote": "These checks read platform configuration only…",
  "blockingCount": 2, "warningCount": 1,
  "findings": [
    { "code": "SOURCE_OF_TRUTH_MISSING", "severity": "BLOCKED",
      "subject": "REVENUE_AMOUNT", "message": "Revenue is reportable for this source but…" }
  ],
  "evaluatedAt": "2026-09-23T17:40:00"
}
```

`status` is `READY | WARNING | BLOCKED` — the worst severity among the findings, or `READY` when
there are none. There is no `NOT_READY`: it would name the same state as `BLOCKED` (FIN-D-077).

**Recomputed on every call and never stored** (FIN-D-073). A verdict persisted anywhere goes stale
the moment a mapping rule changes, and the one thing this must not do is report a configuration
clean because it was clean earlier. Clients must not cache it.

Each finding's `message` is a complete sentence stating what is wrong **and what it would cause**.
It is displayed verbatim, in the same spirit as `availability_reason`; paraphrasing it to fit a
layout loses the second half, which is the half telling an administrator whether they can ignore it.

### 8.5 Status codes

| Situation | Status | Note |
|---|---|---|
| Registered | 201 | `syncEnabled` always false |
| Temple or source system absent, or outside the caller's scope | **404** | Never 403 — same answer for both, as in §7.4 |
| Temple already has a source system with that code | 409 | Names the existing row; says so explicitly when that row is retired, since `uk_fss_temple_system` ignores `is_deleted` |
| Temple already has **any** source system | **422** | One source per temple while D9 is open (FIN-D-074); the message names the decision |
| Stale `version` | 409 | `OPTIMISTIC_LOCK_CONFLICT` |
| Missing required field, bad length, malformed `systemCode` | 400 | Bean validation |
| Role not permitted | 403 | |

### 8.6 Frontend consumer

`frontend/src/features/finance-onboarding/` consumes §8 at `/finance/source-systems`, behind a
`SUPER_ADMIN` route. Two notes for anyone changing the contract:

- **`connectivityNote` is displayed, not paraphrased**, and is shown on the clean verdict as well as
  the failing ones — the clean verdict is where "READY" would otherwise be misread as "connected".
- **No response type declares `credentialRef`.** Adding one to the backend record would make it
  renderable; the TypeScript mirror deliberately has no field for it either.

### 8.7 Capability declarations (FIN-140-B, IMPLEMENTED)

Declaring what a source system can answer. Configuration metadata only: a declaration says what a
temple records, never how the platform reaches it, and writing one starts no traffic and switches
nothing on.

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/finance/capability-catalogue` | The canonical capabilities and availabilities a declaration may name | `ADMIN_ONLY` |
| GET | `/finance/source-systems/{id}/capabilities` | This source system's declarations, in canonical order | `ADMIN_ONLY` |
| POST | `/finance/source-systems/{id}/capabilities` | Declare one capability | `ADMIN_ONLY` |
| PUT | `/finance/source-systems/{id}/capabilities/{declarationId}` | Revise one declaration | `ADMIN_ONLY` |

**No temple id appears in any path or request body.** The temple is resolved from the source system
server-side, which makes "a declaration cannot be created for an unrelated temple" true by
construction rather than by a check that could be forgotten — the same rule the Source Mapper
states: a request carrying its own answer to "whose data is this" has not been authorized.

**No delete.** Withdrawing a capability is a statement, not an absence, and the vocabulary already
has the words: `NOT_AVAILABLE` when the source does not record something, `NOT_APPLICABLE` when the
question does not arise. Deleting the row would leave a reader unable to tell either from "nobody
has looked yet", which is the distinction the availability model exists to preserve.

**The capability is identity.** `uk_ftc_temple_capability` is `(temple_id, capability)`, so `PUT`
has no `capability` field: changing it would not edit the declaration but silently become a
different one, and collide with whatever occupies that slot.

#### Request — declare

```json
{
  "capability": "PRECIOUS_METAL_WEIGHT",
  "availability": "PARTIALLY_AVAILABLE",
  "availabilityReason": "Recorded in grams, with two financial years missing.",
  "coverageFrom": "2015-04-01",
  "coverageTo": "2026-07-25",
  "knownGaps": ["FY2021-22 absent", "FY2022-23 absent"]
}
```

`availabilityReason` is **required unless `availability` is `AVAILABLE`**. It is rendered to a
reader verbatim where a figure would otherwise appear, so an empty one renders an empty
explanation. `knownGaps` entries that are blank are dropped rather than stored; an empty list is
stored as null, so "no gaps recorded" has one representation rather than two.

`PUT` takes the same fields plus a required `version`, and no `capability`.

#### Response

`CapabilityDeclarationResponse` — id, source system, temple, capability, availability, reason,
coverage window, known gaps, `lastReviewedAt`, `version`, timestamps. Distinct from §4.1's
`FinanceCapabilityResponse`, which serves the dashboard to four roles and carries none of the
administrative fields. Neither carries a credential reference, connector bean or source database
name.

#### Status codes

| Situation | Status | Note |
|---|---|---|
| Declared | 201 | |
| Source system or declaration absent, or out of scope | **404** | Including a declaration reached through the wrong source system |
| This temple already has a declaration for that capability | 409 | Names the existing row; says so explicitly when it is retired, or when another source system holds it (D9) |
| Stale `version` | 409 | `OPTIMISTIC_LOCK_CONFLICT` |
| Non-available capability with no reason; coverage ending before it begins | **422** | Refused on write rather than saved and then faulted by readiness |
| Unknown capability or availability value | 400 | Enum binding |
| Role not permitted | 403 | |

#### Readiness

A declaration changes readiness and nothing else — it never activates a source system, never
triggers a worker job and never opens a connection. `NO_CAPABILITY_DECLARED` clears once anything is
declared; the new `CAPABILITY_NOT_DECLARED` **warning** lists capabilities nobody has declared at
all, because an undeclared capability and one declared `NOT_AVAILABLE` look identical downstream
while being different statements. It is a warning, not a blocker: a half-configured source is a
legitimate state to leave overnight.

### 8.8 Source-of-truth declarations (FIN-140-C, IMPLEMENTED)

Which field in a source carries a metric, and why that field rather than the others considered
(ADR-008). Configuration metadata only: a declaration names a table and a column, never a way in
to them, and writing one starts no traffic and switches nothing on.

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/finance/source-systems/{id}/source-of-truth` | Every version of every metric, superseded ones included | `ADMIN_ONLY` |
| POST | `/finance/source-systems/{id}/source-of-truth` | Declare a metric — always as a **new version** | `ADMIN_ONLY` |

**There is no `PUT` and no `DELETE`.** Every change creates a new version and closes the previous
one; nothing is ever edited or removed. A revenue fact carries the `source_of_truth_version` that
produced it, so an in-place edit would leave that stamp pointing at a row which no longer says
what it said when the figure was published. That is precisely the silent restatement ADR-008
exists to make impossible.

**No temple id appears in any path or request body**, as in §8.7 — the temple is resolved from the
source system server-side.

#### Request

```json
{
  "metric": "REVENUE_AMOUNT",
  "sourceObject": "DailySevaNew",
  "sourceField": "Amount",
  "filterPredicate": "Deleteflag = 0 AND BillCancled = 0",
  "rationale": "The receipt header amount is the authoritative recognised revenue.",
  "effectiveFrom": "2019-04-01",
  "supersedesVersion": 1,
  "approved": false,
  "rejectedAlternatives": [
    {
      "object": "DailySevaNewDetails",
      "field": "TotalAmount",
      "measured": "537753226 for FY2025-26",
      "reason": "41% below the authoritative header total, with exact 1:1 row correspondence."
    }
  ]
}
```

`metric` is checked against **`RevenueField`**, which is what normalization actually reads. A
metric nothing reads would be an inert row — accepted, displayed, and silently without effect.

`version` is **not** a request field: the server assigns one past the highest that has ever
existed for this source and metric, retired rows included. `supersedesVersion` is a different
question — the version the caller was looking at — and it is the lost-update guard.

`effectiveTo` is **not** a request field either. It is derived: superseding sets the previous
version's `effectiveTo` to the new version's `effectiveFrom`, so the two cannot be made
inconsistent. `effectiveFrom` may not be in the future, because a declaration takes effect the
moment it is saved — nothing waits for the date.

`approved` records sign-off on `approved_by` / `approved_at`, the two columns ADR-008 put there
for it. Leaving it false means "in force, awaiting sign-off", which is the state the first
onboarded source's own declarations are deliberately in.

#### Response

`SourceOfTruthDeclarationResponse` — id, source system, metric, version, `inForce`, source object
and field, filter, rejected alternatives, rationale, approval, effective window, timestamps.
`inForce` is computed from `effectiveTo`, never stored, so it cannot disagree with the column the
pipeline reads. `rejectedAlternatives` is returned key by key rather than through a fixed shape:
the seeded declarations carry their own measurement keys (`measuredRows`,
`measuredAmountFy2025_26`), and a fixed record would drop the evidence that gives them force.

No credential reference, connector bean, database name, host or port appears on this type.

#### Status codes

| Situation | Status | Note |
|---|---|---|
| Declared | 201 | |
| Source system absent or out of scope | **404** | |
| Unknown metric; `effectiveFrom` in the future; a new version starting before the one it closes | **422** | |
| `supersedesVersion` is not the version in force (including omitted when one is, or given when none is) | 409 | `OPTIMISTIC_LOCK_CONFLICT` |
| Another administrator committed the same version concurrently | 409 | Caught by `uk_fsotd_source_metric_version`; the whole transaction rolls back |
| Missing required field, over-length value | 400 | Bean validation |
| Role not permitted | 403 | |

#### Concurrency

Two administrators reading version 1 and each declaring "the next one" is the race this table
invites, and it is caught twice. `supersedesVersion` catches it in the application with a sentence
naming what happened. `uk_fsotd_source_metric_version` — `(source_system_id, metric, version)` —
catches it in the database for two transactions interleaved too closely for the first check, and
the service converts the violation into a 409 rather than letting it surface as a server error.
Both writes are in one transaction, so a refused supersession leaves nothing behind.

#### Readiness

`SOURCE_OF_TRUTH_MISSING` clears once a declaration **in force** names each required metric. A
superseded declaration does not satisfy it — the readiness snapshot reads the same
`effective_to IS NULL` filter normalization does, so a verdict that counted a closed row would
pass a source every row of which the pipeline would then reject. Revenue requirements stay
conditional: a source declaring `REVENUE` as `NOT_AVAILABLE` is never asked for these at all.

### 8.9 Activation (FIN-140-D, IMPLEMENTED)

Whether the platform is permitted to contact a source system. Sets
`fin_source_system.sync_enabled` — which the onboarding plan's state model calls *"may the
platform contact the source?"* — and nothing else.

**This endpoint contacts nothing.** It resolves no credential, builds no connector, creates no
`fin_sync_batch`, schedules nothing and sends nothing to the sync worker. It could not: the
runtime serving it holds no connector bean and no credential provider, by test-enforced design
(ADR-001). Beyond that, **nothing in production reads `sync_enabled` yet** — the only finder for
it, `findBySyncEnabledTrueAndDeletedFalse`, has no production caller — so enabling a source today
has no operational effect at all.

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | `/finance/source-systems/{id}/activation` | Enable or disable for future synchronisation | `ADMIN_ONLY` |

#### READY is not ENABLED FOR SYNC is not SYNC RUNNING

| State | Means | Implemented |
|---|---|---|
| `READY` | Registry-side configuration is coherent: capabilities declared, required source-of-truth declarations in force, mapping rules valid | Yes — computed on read, never stored |
| **ENABLED FOR SYNC** | An administrator has granted permission for future synchronisation | **Yes — this slice.** `sync_enabled = 1` |
| SYNC RUNNING | A batch is executing | **No.** Belongs to the connector/worker path |
| SYNC FAILED | A batch failed | **No.** Same |

Neither `READY` nor ENABLED FOR SYNC means the source is reachable, the connector is deployed, the
worker is running, or any data has ever been extracted.

#### Request

```json
{ "enabled": true }
```

```json
{ "enabled": false, "reason": "Source is being migrated to a new server." }
```

The request carries the **desired end state**, not an instruction to toggle. That is what makes it
idempotent, and it is why there is **no `version` field** unlike `PUT /source-systems/{id}`:
requiring one would turn a harmless repeat into a 409, and a concurrent write either wanted the
same outcome or is a later decision that should win.

`reason` is **required when disabling** and optional when enabling. Disabling stops a temple's
financial data flowing once the worker path exists, and an audit line saying only that somebody
switched it off is not something anyone can act on.

#### Response — `SourceSystemActivationResponse`

Never a bare `{"active": true}`, which would be read as "synchronising". It carries
`enabledForSync`, `changed` (false on a no-op, so idempotency is visible), the recomputed
`readinessStatus` with counts, `warnings`, and three fields whose whole purpose is to prevent a
misreading:

| Field | Value | Why |
|---|---|---|
| `syncInfrastructureAvailable` | **always false** | Enabling starts nothing. Since FIN-058 a trigger does read `sync_enabled` — but it is manual, worker-side, and no scheduler and no endpoint here starts a run |
| `connectorDeploymentVerified` | **always false** | The connector registry is a sync-worker bean; this runtime structurally cannot see it |
| `activationNote` | a sentence | States what has and has not happened, in the same discipline as `connectivityNote` |

No credential reference, connector bean, database name, host or port.

#### Preconditions

Enabling requires readiness to carry **no blocking finding**, evaluated by the same validator the
readiness endpoint uses, recomputed inside the activation transaction. Warnings never block —
they are legitimate configuration somebody has reason for, and refusing them would make the
warning level meaningless.

**Disabling is never gated.** A switch that can only be turned on is not a switch, and the moment
an administrator most needs to turn a source off is the moment its configuration has gone wrong,
which is exactly when readiness would refuse them.

#### Idempotency

Asking for the state a source is already in changes nothing, writes **no audit row**, returns
`changed: false` and is not an error. The no-op short-circuits **before** the readiness check, so
re-confirming an enabled source does not fail because its configuration drifted after it was
enabled — that is what the readiness screen is for.

#### Status codes

| Situation | Status | Note |
|---|---|---|
| Enabled, disabled, or no change | 200 | The message distinguishes them |
| Source system absent or out of scope | **404** | |
| Enabling while a readiness check is blocking; disabling with no reason | **422** | The message names the blocking finding codes. Nothing is changed |
| `enabled` missing | 400 | Bean validation |
| Role not permitted | 403 | |

#### Audit

`ENABLE_SYNC` / `DISABLE_SYNC` on `FIN_SOURCE_SYSTEM`, written in the same transaction as the flag
(FIN-D-063), recording actor, role, temple, source, the transition and the reason. A refused
activation writes nothing — a successful-looking audit line for a change that did not happen is
worse than no line at all. No credential alias appears.

#### Approval (FIN-D-086)

Unsigned source-of-truth declarations in force are reported in `warnings`. They do **not** block
activation and are still **not** a readiness finding: making them one would change the verdict on
configuration that predates this API. See FIN-D-089.

### 8.10 Starting a synchronisation run — **no endpoint, deliberately** (FIN-058)

There is no `POST …/sync`, no "Run now", and no endpoint anywhere in this contract that starts a
synchronisation. That is a decision, not a gap.

FIN-058 built the trigger. It is `ManualSyncTrigger`, a bean in the **sync-worker** runtime, and it
stays there because it is the one object that can reach a temple database and write a canonical
revenue figure in a single call — in the registry runtime that would put a temple's finance database
one HTTP request away, which is what ADR-001 exists to prevent (FIN-D-095).

Three routes were considered and none was taken in this slice:

| Route | Why not now |
|---|---|
| An endpoint in this API that calls the worker | No registry-to-worker channel exists. The only channel between the runtimes is the shared database; inventing an HTTP one as a side effect of a trigger slice would be an architectural change smuggled in as plumbing |
| A web layer on the worker | `SyncWorkerBoundaryGuard` fails startup if the worker comes up as a web application, on purpose: a process holding temple credentials and a network path into temple estates should not also be listening on a port |
| A scheduler | FIN-D-094 — the pipeline had never run end to end, and the first execution should not be unattended |

Two live candidates were named: a CLI argument on the worker, and a request row the worker reads
from the shared database. **FIN-059 built the first** — `ManualSyncCommandRunner`, a Spring Boot
`ApplicationRunner` in the sync-worker process, reading `trm.finance.sync.command=run` and
`trm.finance.sync.source-system-id=<id>` from the process environment and calling
`ManualSyncTrigger.runNow(id)` exactly once. It is still not an API: nothing in this contract
changed, no port was opened, and the properties are read from the worker's own process, never from
an HTTP request. See `HANDOFF.md` for exact invocation syntax and exit codes.

**`sync_enabled = true` still causes no traffic by itself.** A run happens only when the CLI command
above is explicitly given to a running worker process.

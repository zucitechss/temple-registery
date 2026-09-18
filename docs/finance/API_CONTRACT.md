# Finance API Contract

**Status:** Contract agreed, not yet implemented (FIN-080 … FIN-084).
**Base path:** `/api/v1/dc/temples/{templeId}/finance`
**Auth:** existing JWT; route guard `RoleConstants.IS_DC_ROLE`; field-level filtering via
the existing DACVM policy engine.

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

No endpoint returns a credential reference, a source database name, a connector bean or any
source-side connection detail, and none returns a temple financial figure. `DELETE` was not
implemented: deactivation covers retirement, and soft delete would add a permission tier and an
audit action no described flow needs.

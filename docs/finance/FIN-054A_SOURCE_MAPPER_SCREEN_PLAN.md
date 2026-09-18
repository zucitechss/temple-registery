# FIN-054A — Source Mapper Screen: Analysis & Implementation Plan

**Status:** ANALYSIS AND PLANNING ONLY. Nothing here is implemented.
**Date:** 2026-09-18
**Backend baseline:** FIN-054 at commit `9d8e222`; pipeline through FIN-061 at `274ec35`
**Note:** every capability claim below was checked against source. Where documentation and code
disagree, §2.6 says so and the code wins.

---

## 1. Executive Summary

A Source Mapper screen is buildable, and it is worth building: `UNMAPPED` is the pipeline's
designed signal that a temple has started taking income nobody has classified, and today the only
way to see it is a SQL client.

Three findings shape everything that follows.

**1. There is no finance API. At all.** The backend has nineteen controller packages and not one
of them touches finance. The entire pipeline (FIN-050…FIN-061) is invoked only from tests. This is
not a gap in the mapping feature — it is the absence of the layer the feature would sit on, and it
is the bulk of the work.

**2. Only semantic mapping exists. Structural mapping is not configuration — it is connector
code**, by deliberate architectural decision (ADR-004). A screen offering to map
`donation_transactions.transaction_amount → amount` would promise something the platform does not
do and, as designed, must not do. §4 explains what the structural layer actually is and where it
lives.

**3. `source_value` is a namespaced string, not a value.** A rule matches `SEVA_CODE:430`, where
`SEVA_CODE` is the *staged field name the rule reads*. The set of fields the engine consults is
derived from the rules themselves. This single fact drives the form design, the validation rules,
and the most serious footgun in the feature (§8.3).

**Recommended first release: read-mostly.** Browse rules, see unmapped values with occurrence
counts, create and edit rules for one source system, with server-side authorization and audit.
Defer bulk operations, approval workflow and retroactive-impact tooling — each needs a decision
nobody has made (§17).

---

## 2. Existing Implementation Findings

### 2.1 The mapping engine

| Concern | Where | State |
|---|---|---|
| Rule storage | `fin_mapping_rule` (V110; `priority` added by V114) | exists |
| Rule entity | `FinMappingRule extends BaseEntity` | exists |
| Rule repository | `FinMappingRuleRepository` — 3 finders | exists, no paging |
| Matching engine | `MappingRuleResolver` — pure, immutable, no Spring | exists |
| Pipeline stage | `RevenueMappingStage` | exists |
| Per-record decision | `fin_stg_revenue_mapping` / `FinStgRevenueMapping` | exists |
| Canonical vocabulary | `fin_revenue_category` / `FinRevenueCategory` | exists |
| Outcome vocabulary | `MappingOutcome` — 5 values | exists |
| Mapping dimensions | `MappingType` — 6 values | enum exists; **only 1 is used** |

### 2.2 What `MappingType` actually supports

The enum declares six dimensions. **Exactly one is implemented.**

| Dimension | Enum value | Engine support | Rules seeded |
|---|---|---|---|
| Revenue category | `REVENUE_CATEGORY` | **yes** — `RevenueMappingStage.MAPPING_TYPE` | 9 |
| Metal type | `METAL_TYPE` | none | 2, and **they cannot fire** (limitation 24) |
| Service | `SERVICE` | none | 0 |
| Payment mode | `PAYMENT_MODE` | none | 0 |
| Status | `STATUS` | none | 0 |
| Financial year | `FINANCIAL_YEAR` | none | 0 |

`MappingRuleResolver` is generic over `MappingType`; `RevenueMappingStage` hard-codes
`REVENUE_CATEGORY` and is its only caller. **A screen that let a user create a `PAYMENT_MODE` rule
would let them create a row nothing reads.** That is worse than not offering it: the user would
reasonably conclude payment mode had been configured.

### 2.3 The namespace convention — the central UI constraint

From `MappingRuleResolver`:

```
source_value := <FIELD_NAMESPACE> ':' <RAW_SOURCE_VALUE>
                e.g.  SEVA_CODE:430     SANNIDHI:KN     BUCKET:DS
```

- The namespace **is the name of the staged field** the rule reads.
- The set of fields consulted is derived from the rules — add a rule in a new namespace and a new
  field starts being read, with no code change.
- Matching is **exact**. Nothing is trimmed, case-folded or normalised. `Cash`, `CASH` and `CSH`
  are three different source values needing three rules. The brief's example — several spellings
  collapsing to one canonical value — is supported, but as three rows, not by fuzzy matching.
- Highest `priority` wins; a tie with two matches is `AMBIGUOUS` and produces **no** canonical
  value.

### 2.4 Scope and keys

- Rules are scoped to **`source_system_id`**, never to temple directly. Temple is reached through
  `fin_source_system.temple_id`. There is no global or default rule set, and no tenant concept
  beyond temple.
- Unique key `uk_fmr_source_type_value (source_system_id, mapping_type, source_value)`, so **one
  source value cannot map to two canonical values within a type** — the database forbids it. The
  `AMBIGUOUS` outcome arises from *different namespaced keys on one record* matching at equal
  priority, not from a duplicate key.

### 2.5 Audit and history — what exists and what does not

| Field | Present? | Source |
|---|---|---|
| `created_at`, `updated_at` | yes | `BaseEntity` |
| `created_by`, `updated_by` | yes | `BaseEntity`, `@CreatedBy` / `@LastModifiedBy` |
| `is_deleted` (soft delete) | yes | `BaseEntity` |
| `is_active` | yes | `fin_mapping_rule` |
| `notes` (free text) | yes | `fin_mapping_rule` |
| **`@Version` / optimistic locking** | **no** | other entities in this codebase have it; this one does not |
| **Effective dates** | **no** | not in schema |
| **Version number** | **no** | not in schema |
| **Change history / previous values** | **no** | only the current row is kept |
| **Reason-for-change** | **no** | `notes` describes the rule, not an edit |

`created_by` / `updated_by` default to `0` in the DDL and every existing row was written by a
migration, so **they currently carry no real user**. They become meaningful only once a
human-facing API writes rules.

A general `audit_data_event` table exists (`AuditDataEvent`: actor id, actor role, action, entity
type, entity id, detail, occurred at), written by `AuditServiceImpl`. **No finance code writes to
it.** It is the right vehicle for mapping-change audit and needs no schema change.

### 2.6 Documentation vs code discrepancies

| Claim | Where | Reality |
|---|---|---|
| Six mapping dimensions | `MappingType` enum | five are inert (§2.2). Limitation 25 records this for `SERVICE`/`PAYMENT_MODE`; the enum itself does not |
| `METAL_TYPE` rules seeded | V111 | seeded and **cannot fire** — already recorded as limitation 24 |
| Unmapped values "surfaced as a warning" | `FinMappingRule` javadoc | true within the batch's error rows; **nothing surfaces it to a human** — no API, no screen |
| Reconciliation statuses | `FINANCE_RECONCILIATION.md` §8 | six documented, three exist — already annotated in that file at FIN-060 |

No *new* discrepancy was found between the mapping code and the mapping documentation. The
documentation is unusually accurate; its gaps are ones it already admits.

---

## 3. Mapping Domain Analysis

| # | Question | Answer |
|---|---|---|
| 1 | Where are configurations stored? | `fin_mapping_rule` |
| 2 | Entities/tables | `FinMappingRule`, `FinStgRevenueMapping`, `FinRevenueCategory`, `FinSourceSystem` |
| 3 | Structural or semantic? | **Semantic only** (§4) |
| 4 | Scope | Per `source_system_id`; temple via the source system. No global rules, no tenant layer |
| 5 | One source value → many canonical? | **No.** `uk_fmr_source_type_value` forbids it |
| 6 | Conflicts | Higher `priority` wins; equal priority with several matches is `AMBIGUOUS` and yields no value |
| 7 | Unmapped | `MappingOutcome.UNMAPPED`, routed to the seeded `UNMAPPED` category, never dropped |
| 8 | Ambiguous | `MappingOutcome.AMBIGUOUS`, no canonical value, error recorded |
| 9 | Versioning | **Not implemented** |
| 10 | Activate / deactivate / archive | `is_active` and `is_deleted` exist. **No API exercises either** |
| 11 | History | **Not preserved.** Only the current row exists |
| 12 | Safe editing? | Mechanically yes. Semantically — see 13 |
| 13 | Effect on processed data | **None until re-run.** Facts already in `fin_revenue_fact` keep their old classification. Re-running a batch re-decides and re-loads (mapping and load are both idempotent). There is no automatic re-processing and no trigger for one |

**Answer 13 is the one that matters for the UI.** Editing a rule changes the future, not the past,
and the screen must say so at the point of edit. A user who corrects a misclassification and sees
"Saved" will reasonably believe the dashboard has been corrected. It has not.

---

## 4. Structural vs Semantic Mapping

### Semantic mapping — supported

Source value → canonical value, as configuration. This is `fin_mapping_rule`, and it works.

### Structural mapping — NOT configuration, deliberately

The brief's example (`donation_transactions.transaction_amount → amount`) is **not supported as
configuration and should not be added by this feature.** ADR-004 draws the line explicitly:

> which rows and which columns is **code**; what a value means is **configuration**

Structural concerns are split across three places, none of them a mapping screen:

| Structural concern | Where it lives | Configurable? |
|---|---|---|
| Which tables and columns to read | The connector implementation (FIN-043 for Kollur) | **No — Java** |
| Which staged field carries the authoritative amount or date | `fin_source_of_truth_decl` | **Yes**, with an approval field |
| Which staged field a rule reads | The rule's own namespace (§2.3) | Yes, implicitly |
| Source record identity | `RawRow.sourceRecordRef`, connector-supplied | No |

**`fin_source_of_truth_decl` is the closest thing to a structural mapping table, and it is a
different screen.** It carries `metric`, `source_object`, `source_field`, `version`, `approved_by`,
`approved_at` and a rejected-alternatives rationale. Editing it changes what a number *means*;
Kollur's business-date declaration is deliberately seeded unapproved (limitation 27). Exposing it
through the same CRUD form as seva-code mappings would put a reviewed, approval-gated financial
declaration behind the same two clicks as a label correction.

**Recommendation:** FIN-054A covers semantic mapping only. A source-of-truth declaration screen is
a separate task with an approval workflow of its own.

---

## 5. Existing Database Analysis

### `fin_mapping_rule` (V110 + V114)

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK auto | |
| `source_system_id` | BIGINT NOT NULL | **no FK declared** |
| `mapping_type` | VARCHAR(40) NOT NULL | enum as string |
| `source_value` | VARCHAR(200) NOT NULL | namespaced (§2.3) |
| `source_label` | VARCHAR(400) NULL | may be non-Latin script |
| `canonical_value` | VARCHAR(100) NOT NULL | **no FK** to `fin_revenue_category` |
| `priority` | INT NOT NULL DEFAULT 100 | added by V114 |
| `is_active` | TINYINT(1) NOT NULL DEFAULT 1 | |
| `notes` | TEXT NULL | |
| `is_deleted`, `created_at`, `updated_at`, `created_by`, `updated_by` | | the last two DEFAULT 0 |

- Unique: `uk_fmr_source_type_value (source_system_id, mapping_type, source_value)`
- Index: `idx_fmr_lookup (source_system_id, mapping_type, is_active)`; V114 extends it for priority
- **No `version` column** → no optimistic locking
- **No effective dates**

`canonical_value` having no foreign key is deliberate: `MappingOutcome.INVALID_CONFIGURATION`
catches a bad value at mapping time with the offending rule named, instead of as an FK violation
on a batch of forty thousand rows.

### Supporting tables (read-only for this feature)

- `fin_source_system` — `temple_id`, `system_code`, `system_name`, `connector_bean`, `is_active`
- `fin_revenue_category` — `category_code`, `category_name`, `description`, `display_order`,
  `is_active`: the canonical dropdown
- `fin_stg_revenue_mapping` — per-record decisions; source of unmapped and ambiguous counts
- `audit_data_event` — existing audit sink

---

## 6. Existing API Analysis

**There are no finance endpoints.** Verified by searching every controller package.

Conventions the new endpoints must follow:

- Envelope `ApiResponse<T>`: `success`, `message`, `data`, `errorCode`, `errors`, `timestamp`,
  `requestId`
- `PaginatedResponse<T>` for lists
- Authorization via `@PreAuthorize` on the **service implementation** using `RoleConstants`
  expressions — not on the controller alone
- Frontend base path `/api/v1` through `baseQueryWithReauth`

Repository gaps for a UI: `FinMappingRuleRepository` has three finders, none paged, none filtered,
no search. `FinStgRevenueMappingRepository.summariseBySourceValue` returns source value plus
occurrence count, worst first — **exactly the unmapped-values list the screen wants** — but is
**scoped to a single `syncBatchId`**, not across batches or by source system.

---

## 7. Backend Gaps

| # | Gap | Severity |
|---|---|---|
| B1 | No finance controller, service interface or DTOs | **blocking** |
| B2 | No paged/filtered rule query | blocking |
| B3 | No cross-batch unmapped-value query (per-batch only) | blocking for the unmapped view |
| B4 | No authorization on any finance operation | **blocking, security** |
| B5 | No audit write on mapping change | **blocking, financial control** |
| B6 | No optimistic locking on `FinMappingRule` | high — concurrent edit silently lost |
| B7 | No namespace validation when a rule is written | high — a malformed rule is silently inert (§8.3) |
| B8 | No canonical-value validation at write time | medium — caught only at mapping time today |
| B9 | No change history | medium — needs a decision (D2) |
| B10 | Five `MappingType` values are inert | medium — the UI must not offer them |
| B11 | No impact preview ("how many records would this rule have matched") | low effort, high value |

---

## 8. Security Analysis

### 8.1 Current state

**The finance pipeline has no authorization because it has no entry point.** Every RBAC guard in
this codebase sits on an HTTP-facing service implementation. Adding the first finance endpoint adds
the first finance attack surface. This feature is where finance authorization gets invented, so it
must be got right rather than retrofitted.

### 8.2 Threats and controls

| Threat | Control |
|---|---|
| Unauthorized mapping change | `@PreAuthorize` on the **service impl**, not the controller. Frontend guards are UX only |
| Cross-temple access | Resolve `sourceSystemId → temple_id` **server-side** and check it against the caller's scope. Never trust a client-supplied `templeId` |
| Arbitrary SQL | No endpoint accepts SQL, a table name, a column name or a raw order-by. Sorting uses an allow-list of column names mapped to `Sort` |
| Mass change | No bulk endpoint in phase 1. When added: a hard cap, a distinct permission, one audit row per rule |
| Duplicate mappings | `uk_fmr_source_type_value` plus a 409 naming the existing rule |
| Conflicting mappings | Server-side ambiguity pre-check: warn when a new rule ties on priority with an existing one in the same namespace |
| Historical corruption | **Nothing in this feature writes, deletes or re-processes a fact.** A rule edit changes future runs only (§3.13) |
| Missing audit | Write `AuditDataEvent` in the same transaction as the rule change; if the audit write fails, the change fails |
| Privilege escalation | Mapping permissions are not inherited from any temple-scoped role a temple user can self-assign |
| Enumeration | 404, not 403, for a source system outside the caller's scope |

### 8.3 The namespace footgun — the biggest feature-specific risk

A rule whose `source_value` has **no** `:` separator, or a namespace no connector emits, is accepted
by the database, shows no error in the UI, and **silently never matches anything**. The engine
derives its readable fields from the rules, so a typo in a namespace does not fail — it invents a
field nobody writes.

Consequence: a user "fixes" an unmapped seva code, sees the rule listed as Active, and the next run
reports it unmapped again. Nothing anywhere explains why.

**Mitigations, all required in phase 1:**

1. Server-side format validation — reject a `source_value` with no separator or an empty half.
2. Offer the namespace as a **select**, populated from namespaces already in use for that source
   system; free text only as a deliberate "advanced" action carrying a warning.
3. Create-from-unmapped-value (§9.C) pre-fills the namespace from the observed value, making the
   correct path also the easiest one.

---

## 9. Proposed Screen Structure

Location: `frontend/src/features/finance/` (new), following the `features/declaration` layout —
`financeApi.ts`, `financeTypes.ts`, `financePermissions.ts`, `pages/`, `components/`, `__tests__/`.

### A. Overview bar

Source-system selector (required — everything is scoped to it), showing the temple name resolved
server-side. `KpiCard`s: active rules, inactive rules, unmapped values in the latest batch,
ambiguous decisions in the latest batch.

**Honest labelling:** unmapped and ambiguous counts come from the most recent batch (B3). The card
must name that batch and its time, or it implies a live figure it is not.

### B. Rule list

`DataTable`. Columns: mapping type, namespace, source value, source label, canonical value,
priority, status (`StatusBadge`), updated by, updated at, actions. Namespace and source value are
shown as **two columns**, split from the single stored string, because two fields is the concept
the user needs. Filters: mapping type, status, canonical value, free-text search. Server-side
paging.

### C. Unmapped values view

Driven by `summariseBySourceValue`, extended per B3. Source value, occurrence count, worst first,
with a **Create rule** action that pre-fills namespace and value. This is the screen's main
purpose: it converts a pipeline signal into one click.

### D. Create / edit drawer

`sheet.tsx` plus `form.tsx`. Fields: mapping type (**only `REVENUE_CATEGORY` enabled**; the others
disabled with "not yet read by the pipeline"), namespace (select), source value, source label,
canonical value (select from `fin_revenue_category`), priority, active, notes.

Must display, at the point of edit: **"This changes how future runs classify this value. Figures
already published keep their current classification until the batch is re-run."**

### E. History

**Not available.** The schema keeps only the current row (§2.5). Show `created_by` / `updated_by` /
`updated_at` and state plainly that prior values are not retained. Do not build a history tab
against data that does not exist — see D2.

---

## 10. User Flows

1. **See what needs attention** — pick source system → Unmapped tab → sorted by occurrence.
2. **Fix an unmapped value** — Create rule → namespace and value pre-filled → pick canonical value
   → save → warning that a re-run is needed before published figures change.
3. **Correct a misclassification** — find rule → edit canonical value → save → same warning.
4. **Retire a rule** — deactivate (`is_active = false`); it stops matching future runs and stays
   visible. Delete is soft (`is_deleted`) and needs a stronger permission.
5. **Investigate an ambiguity** — ambiguous view lists the source value and the tied rules;
   resolution is to change a `priority`.

Steps requiring new backend work: **all of them.** Nothing in this list is reachable today.

---

## 11. Permission Matrix (proposed — requires approval, D1)

Anchored to existing `RoleConstants`. No new role is introduced.

| Action | SUPER_ADMIN | DISTRICT_COLLECTOR | DC_STAFF | AUDITOR | TEMPLE_AUTHORITY | VIEWER |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| View rules | yes | yes | yes | yes | no | no |
| View unmapped / ambiguous | yes | yes | yes | yes | no | no |
| Create rule | yes | yes | no | no | no | no |
| Edit rule | yes | yes | no | no | no | no |
| Activate / deactivate | yes | yes | no | no | no | no |
| Soft-delete | yes | no | no | no | no | no |

Writes map to `CAN_ACT_DC` (`SUPER_ADMIN`, `DISTRICT_COLLECTOR`), the existing expression for
governance actions. `AUDITOR` is read-only per the documented permission matrix, whose single
carve-out (observations) does not apply here. **`TEMPLE_AUTHORITY` is excluded deliberately:** a
mapping rule decides which revenue category a temple's own income lands in, and letting the
regulated party reclassify its own revenue is a control failure regardless of convenience. That is
a judgement, not a requirement found written down — see D1.

---

## 12. API Proposal

All new. Base `/api/v1/finance`. Authorization on the service impl. Responses use `ApiResponse<T>`.

| # | Method | URL | Purpose | Authorization |
|---|---|---|---|---|
| A1 | GET | `/finance/source-systems` | Selector; temple resolved server-side | read |
| A2 | GET | `/finance/mapping-rules` | Paged, filtered, sorted list | read |
| A3 | GET | `/finance/mapping-rules/{id}` | One rule | read |
| A4 | POST | `/finance/mapping-rules` | Create | `CAN_ACT_DC` |
| A5 | PUT | `/finance/mapping-rules/{id}` | Update | `CAN_ACT_DC` |
| A6 | PATCH | `/finance/mapping-rules/{id}/status` | Activate / deactivate | `CAN_ACT_DC` |
| A7 | DELETE | `/finance/mapping-rules/{id}` | Soft delete | `ADMIN_ONLY` |
| A8 | GET | `/finance/mapping-rules/unmapped` | Unmapped values with counts | read |
| A9 | GET | `/finance/mapping-rules/ambiguous` | Ambiguous values with tied rules | read |
| A10 | GET | `/finance/canonical-values` | Category dropdown | read |
| A11 | GET | `/finance/mapping-rules/namespaces` | Namespaces in use, for the select | read |

**A2** required: `sourceSystemId`. Optional: `mappingType`, `active`, `canonicalValue`, `q`,
`page`, `size`, `sort` (**allow-listed column names only**).

**A4 / A5** request: `sourceSystemId`, `mappingType`, `namespace`, `sourceValue`, `sourceLabel`,
`canonicalValue`, `priority`, `active`, `notes`. The server composes
`source_value = namespace + ':' + sourceValue`. Validation: namespace non-empty and free of the
separator; canonical value must exist and be active; priority within range; duplicate → **409**
naming the existing rule; ambiguity risk → **200 with a warning**, not a rejection, because a
deliberate tie is legitimate configuration.

**A4–A7** write an `AuditDataEvent` in the same transaction. **A5** should carry an expected version
once B6 is addressed (D3).

**Idempotency:** A4 is not idempotent and is protected by the unique constraint. A5, A6 and A7 are
naturally idempotent. **No endpoint triggers re-processing** — deliberate, and the subject of D4.

---

## 13. Database Changes

**None are strictly required for a read-only screen.** For the full feature, two are worth
considering, and both need approval before anything is written.

| Change | Why | Compatibility |
|---|---|---|
| `fin_mapping_rule.version INT NOT NULL DEFAULT 0` plus `@Version` (B6) | Two users editing one rule currently produce a silent last-write-wins on a financial control. Other entities here already use `@Version` | Additive, backward compatible; default 0 covers existing rows. MySQL and H2 fine. TiDB unverified, as with every migration in this project |
| `fin_mapping_rule_history` (B9 / D2) | Preserve previous values, actor and reason per change | Additive. Alternative: rely on `audit_data_event` with a JSON detail, needing **no** migration |

**Recommendation: take the `version` column; do not build a history table yet.**
`audit_data_event` already records actor, action, entity and a detail blob, and a purpose-built
history table before anyone has stated a retention or reporting requirement is speculative.

**No migration is created by this task.**

---

## 14. Testing Strategy

### Backend — behavioural, not smoke

- Namespace validation rejects a separator-less `source_value` (guards §8.3)
- Duplicate → 409 naming the existing rule
- A canonical value that does not exist is rejected at write, not at mapping time
- **Cross-temple isolation:** a caller scoped to temple A gets 404 for temple B's source system
- **Authorization at the service layer**, proven by invoking the service as each role — not by
  asserting an annotation exists
- Concurrent edit: two writers, one loses (once B6 lands)
- An audit row is written; and a rule change is **rolled back** when the audit write fails
- The unmapped summary aggregates correctly across batches (B3)
- A `sort` value outside the allow-list is rejected rather than passed through
- **Regression: FIN-053…FIN-061 unchanged** — specifically `RevenueMappingStageTest` (22),
  the resolver tests, and the finance regression (386 at FIN-061)
- A rule edit does **not** alter any existing `fin_revenue_fact` row — asserted directly

### Frontend

- `RoleRoute` blocks disallowed roles; permission denial renders the existing 403 route
- Write controls absent for read-only roles — rendering assertion **plus** the API never called
- Unmapped list ordered by occurrence descending
- Create-from-unmapped pre-fills namespace and value
- The "future runs only" warning appears on every edit path — a correctness assertion, not cosmetics
- Disabled mapping types cannot be submitted
- Loading, empty and error states; an API failure surfaces the backend `errorCode`
- Light and dark theme via existing tokens; responsive layout; accessibility (labels, focus,
  keyboard)

Vitest with existing patterns; `features/declaration/__tests__` is the model, including its
property tests. No test that only asserts a component renders.

---

## 15. Phased Implementation Plan

| Phase | Work | Migration | Complexity | Risk |
|---|---|---|---|---|
| **1. Backend foundation** | Service interface and impl, DTOs, `@PreAuthorize`, temple-scope resolution, paged/filtered queries, namespace and canonical validation, audit writes | No | **High** | First finance authorization surface; scope resolution must be right |
| **2. Read APIs** | A1, A2, A3, A8, A9, A10, A11 plus controller | No | Medium | B3 needs a new cross-batch query |
| **3. Write APIs** | A4, A5, A6, A7; duplicate and ambiguity handling; audit | Yes (`version`, if D3 approved) | Medium | Concurrent edit; retroactive-impact messaging |
| **4. Frontend read** | Feature module, route, guard, RTK Query slice, overview, rule list, unmapped view | No | Medium | Reuse `DataTable`, `KpiCard`, `StatusBadge`, `EmptyState`, `Skeleton` |
| **5. Frontend write** | Create/edit drawer, namespace select, validation, confirmations, warning copy | No | Medium | The §8.3 footgun is defeated here or not at all |
| **6. Testing** | Per §14, including the full finance regression | No | Medium | |
| **7. Documentation** | Decisions, permission matrix, limitations, handoff | No | Low | |

**Dependencies:** 2 depends on 1; 3 on 1 (and D3); 4 on 2; 5 on 3; 6 throughout; 7 last.

**Acceptance criteria.** An authorized user can see the unmapped values from the latest batch for a
source system, create a correctly namespaced rule in one click from one of them, and see it in the
rule list — with an audit row written, an unauthorized user refused **by the server**, no existing
fact altered, and the finance regression still green.

---

## 16. Risks and Limitations

1. **No finance API exists** — phase 1 is most of the work and is not mapping-specific.
2. **The namespace footgun** (§8.3) — a malformed rule is silently inert.
3. **Editing a rule does not correct published figures** (§3.13). A UI that does not say so will
   mislead. No re-processing trigger exists, and adding one is D4.
4. **Five of six mapping types are inert** — the UI must disable them.
5. **No history** — "who changed this from what" is unanswerable today.
6. **No optimistic locking** — silent last-write-wins on a financial control.
7. **Unmapped counts are per batch**, not live.
8. **`created_by` / `updated_by` are 0** on every existing row.
9. **No real source data exists.** The screen will be built and demonstrated against synthetic rows
   until Q4 is answered and FIN-043 lands; it cannot be validated against a real temple's
   vocabulary before then.
10. **TiDB unverified** — unchanged from every prior task.

---

## 17. Open Decisions Requiring Approval

| # | Decision | Recommendation |
|---|---|---|
| **D1** | The permission matrix (§11), especially excluding `TEMPLE_AUTHORITY` from writes | Exclude. The regulated party should not reclassify its own revenue |
| **D2** | Change history: dedicated table, `audit_data_event` only, or none | `audit_data_event` first; a table once a retention requirement is stated |
| **D3** | Add `version` for optimistic locking | Yes, before any write API ships |
| **D4** | Should the screen offer "re-run this batch"? | **Not in this feature.** It is a pipeline trigger with cost and blast radius, and no trigger API exists |
| **D5** | Approval / maker-checker for mapping changes | **Not now.** No documented requirement; FIN-D-060 refused an override for the same reason. Revisit with D1 |
| **D6** | Expose `fin_source_of_truth_decl` editing | **No.** Separate task, needs its own approval flow (§4) |
| **D7** | Enable the inert `MappingType` values | No. Implement the engine first |

---

## 18. Unsupported Assumptions — explicitly labelled

Everything here is **proposed, not verified against a stated requirement**:

- The permission matrix (§11) — inferred from `RoleConstants`, not from a finance requirement.
- The URL scheme `/api/v1/finance/...` — consistent with existing conventions, but no finance API
  contract fixes it. `API_CONTRACT.md` specifies *reporting* endpoints, not administrative ones.
- That DC-level users are the intended operators — plausible, unstated.
- That unmapped values are best surfaced per source system rather than per temple — follows the
  data model, not a user request.
- That one screen should serve all mapping types once they work.
- That "Source Mapper" means semantic mapping. **If the requester intends structural
  source-to-staging mapping, this plan does not deliver it, and §4 explains why that would be an
  architectural change rather than a screen.**

---

## 19. Verification

- **No source code modified.** Analysis only.
- **No migration created.**
- **Nothing committed.**
- Every capability claim traced to a file: `FinMappingRule`, `MappingRuleResolver`,
  `RevenueMappingStage`, `FinMappingRuleRepository`, `FinStgRevenueMappingRepository`,
  `FinRevenueCategory`, `BaseEntity`, `RoleConstants`, `AuditDataEvent`, `ApiResponse`,
  V110 / V111 / V114, and the frontend `features/`, `routes/` and `components/` trees.
- FIN-053…FIN-061 behaviour untouched, because nothing was changed.
- Consistent with ADR-001 (no runtime source access — this screen reads central tables only),
  ADR-004 (code versus configuration — §4) and ADR-007 (absence is not zero — unmapped is
  surfaced, never defaulted).

**FIN-054A is not implemented. This document is analysis and planning only.**

---

## 20. What was built — plan versus delivery (FIN-054A-BE, FIN-054B)

This document was written as analysis. Both halves are now implemented. Where delivery differs from
the plan, the reason is recorded rather than the plan quietly amended.

| Plan | Delivered | Why it differs |
|---|---|---|
| 11 endpoints (A1…A11) | **9** | `DELETE` dropped — deactivation covers retirement and soft delete adds a permission tier no flow needed. A9 `/ambiguous` merged into `/unresolved?outcome=` |
| §2.5 "audit via `AuditService`" | `AuditDataEvent` written directly | `AuditService` is `@Async` and swallows failures, so it cannot roll a change back (FIN-D-063) |
| B3 "cross-batch unmapped query" | **newest batch only, batch named** | Cross-batch counts would double-count re-staged records. The plan's premise was wrong (FIN-D-065) |
| §8.3 namespace: "reject a bad namespace" | **format rejected, unknown field warned** | No registry of a source's fields exists; rejecting would make a source unconfigurable before its first extraction (FIN-D-062) |
| D3 optimistic locking | Implemented (V117), **plus an explicit stale-version check** | Hibernate's own check does not catch the race this feature has: two administrators minutes apart (FIN-D-064) |
| §9.E history tab | **Not built** | As the plan recommended. No history exists to show |
| D4 re-run trigger, D6 source-of-truth editing | **Not built** | Deliberately out of scope; unchanged |

### Decisions D1–D7, resolved

| # | Resolution |
|---|---|
| D1 permission matrix | Adopted as proposed. `TEMPLE_AUTHORITY` and `VIEWER` excluded from read and write |
| D2 history | `audit_data_events` only; no history table, no history tab |
| D3 optimistic locking | Yes — V117 |
| D4 re-run trigger | **No.** Not implemented, and the screen says plainly that it cannot correct history |
| D5 maker-checker | **No.** No documented requirement |
| D6 source-of-truth editing | **No.** Separate task, needs its own approval flow |
| D7 inert mapping types | **No.** Writes confined to `REVENUE_CATEGORY`; existing rules of other types stay visible and deactivatable |

### §18 assumption, tested

The plan flagged: *"if the requester intends structural source-to-staging mapping, this plan does
not deliver it."* That remains true and is now enforced in code — no request in
`financeRequests.ts` is named for a table, column, schema or connector, and a test asserts it.

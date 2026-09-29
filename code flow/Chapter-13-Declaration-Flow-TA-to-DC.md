# CHAPTER 13 — Declaration Flow: TA to DC

I'm reading "dv" as **DC (District Collector)**. Everything below is traced from the code: `DeclarationServiceImpl`, `GovernanceWorkflowServiceImpl`, `WorkflowEngineImpl`, `TransitionRuleRegistry`, and the frontend files `declarationApi.ts`, `governanceApi.ts` and `dcHooks.ts`.

---

## 1. First, the simple picture

Think of a declaration as a **paper file** that travels between two desks:

- **TA (Temple Authority):** fills the file in and sends it.
- **DC (District Collector):** reviews it. The DC can ask questions, send someone to inspect the temple, and finally approve or reject.

The backend tracks where the file is using **three separate "status" labels**. This is the most important thing to understand:

| # | Where it's stored | Name | Think of it as |
|---|---|---|---|
| ① | `asset_declarations.status` | **Declaration status** (`DeclarationStatus` enum) | The label **on the file itself**. DC lists and the TA screens read this. |
| ② | `workflow_instances.status` + `sub_status` | **Workflow status** (`WorkflowStatus` enum) | The entry in the **official register**. This is the one the rules are checked against. |
| ③ | `asset_declarations.physical_verification_status` | **Site-visit status** (`PhysicalVerificationStatus` enum) | A **sticky note** about the inspection |

Every action updates ① and ② **in the same transaction**. Either both are saved, or neither is. The code calls this the "dual write".

---

## 2. The status dictionary

| Stage | ① Declaration status | ② Workflow status (sub-status) | ③ Site visit | Whose turn next |
|---|---|---|---|---|
| TA is still filling it in | `DRAFT` | `DRAFT` | `NOT_INITIATED` | TA |
| TA sent it | `SUBMITTED` | `SUBMITTED` | — | DC |
| DC opened it | `UNDER_REVIEW` | `UNDER_REVIEW` | — | DC |
| DC asked a question | `CLARIFICATION_REQUIRED` | `CLARIFICATION_REQUESTED` ⚠ different name | — | TA |
| TA answered | `CLARIFICATION_RESPONDED` | `CLARIFICATION_RESPONDED` | — | DC |
| DC ordered an inspection | `SITE_VISIT_SCHEDULED` | `UNDER_REVIEW` (`SITE_VISIT_SCHEDULED`) | `ORDERED_FOR_PHYSICAL_VERIFICATION` | DC |
| Inspection done | `SITE_VISIT_COMPLETED` | `UNDER_REVIEW` (`SITE_VISIT_COMPLETED`) | unchanged | DC |
| Inspection passed | `VERIFIED` | `UNDER_REVIEW` (`PHYSICALLY_VERIFIED`) | `PHYSICALLY_VERIFIED` | DC |
| Inspection failed | *unchanged* | `UNDER_REVIEW` (`VERIFICATION_FAILED`) | `VERIFICATION_FAILED` | DC (can only reject now) |
| **Approved** | `APPROVED` + acknowledgement number | `APPROVED` (or `RE_APPROVED`) | — | nobody |
| **Rejected** | `REJECTED` | `REJECTED` | — | TA (may fix and resend) |
| TA took it back | `WITHDRAWN` | `WITHDRAWN` | — | nobody |

**Why the names differ.** "Next turn" is saved in `workflow_instances.current_actor_role` by `resolveNextActor()`. The two enums were written separately. `canonicalizeWorkflowStatusForEntity()` translates between them:
- `CLARIFICATION_REQUESTED` → `CLARIFICATION_REQUIRED`
- `RESUBMITTED` → `SUBMITTED`
- `RE_APPROVED` → `APPROVED`

Also note that the site-visit stages **don't change the workflow status**. The register still says `UNDER_REVIEW`, and only the sub-status changes.

---

## 3. The whole journey on one page

```text
      TA                                                         DC
      ──                                                         ──
 ① Create draft ──► DRAFT
      │ (edit freely)
 ② Submit ────────► SUBMITTED ─────────────────────────► ③ DC opens it ─► UNDER_REVIEW
                        ▲                                        │
                        │                  ┌─────────────────────┼─────────────────────┐
                        │                  ▼                     ▼                     ▼
                        │       ④ Ask question          ⑤ Order site visit      ⑥ Approve / Reject
                        │   CLARIFICATION_REQUIRED     SITE_VISIT_SCHEDULED
                        │          │ (max 3 times)            │
 ④b TA answers ◄───────────────────┘                     SITE_VISIT_COMPLETED
   CLARIFICATION_RESPONDED ──► back to DC                     │
                                                   VERIFIED  or  VERIFICATION_FAILED
                                                        │            │ (approve now blocked)
                                                        ▼            ▼
                                                    APPROVED      REJECTED ──► ⑦ TA edits & resubmits ──► SUBMITTED again
                                              (ACK number + PDF)
```

---

## 4. The "engine package": what every governed action writes

Steps ②, ③, ④, ④b, ⑤, ⑥ and ⑦ all call **`WorkflowEngineImpl.execute()`**. I explain that call once here, so I can just say "+ engine package" in each phase below.

`execute()` first **checks**:
- Is there a rule for `(current status, action)` in `TransitionRuleRegistry`?
- Is the role right?
- Is it the right district, or the right temple?
- Is a comment present when one is required?
- Do all the policies allow it?

If every check passes, it **writes**:

| Table | Row | Meaning |
|---|---|---|
| `workflow_instances` | **UPDATE** `status`, `sub_status`, `current_actor_role`, `status_updated_at` (+ `submitted_at` on submit); `version` +1 | the register entry moves forward |
| `workflow_transitions` | **INSERT** (from, to, action, actor, comment, time) | permanent history line, never edited |
| `governance_action_history` | **INSERT** (via `GovernanceAuditService.logWorkflowTransition`) | audit trail |
| `notification_outbox` | **INSERT** status `PENDING` | a "letter to send" |
| `workflow_idempotency_records` | **INSERT** (only if the call had an idempotency key) | "I already did this", so a double-click does nothing twice |

Then, **after the transaction commits**, one event (`GovernanceDomainEvent`) wakes up the background workers:

| Table | Written by | Meaning |
|---|---|---|
| `in_app_notifications` | `NotificationRouter` → `NotificationDispatchServiceImpl` | the bell icon message |
| `email_outbox` → `email_delivery_logs` | `EmailDeliveryService` (polls every 10 s) | the email and its delivery result |
| `notification_outbox` | `NotificationRouter.dispatchPending` (every 5 s) | row marked `DISPATCHED` |
| `temple_timeline_events` | `GovernanceDomainEventTimelineListener` | temple's activity timeline |
| `temple_search_summary` | `TempleSearchSummaryServiceImpl.refresh` | the fast search/list card (when the service called `scheduleRefresh`) |

Who gets notified is decided by rows in the **`notification_rules`** table (event + action → TA / DC / ADMIN, channel, email template).

---

## 5. Phase by phase

### Phase ① TA creates the draft

- **Screen → API:** `POST /api/v1/temples/{templeId}/declarations`, with a body holding the financial year, income and expenditure, and 9 lists of assets.
- **Code:** `DeclarationController.create` → **`DeclarationServiceImpl.create`**

**Step by step:**
1. `ownershipGuard.assertOwnsTemple(templeId)`: is this TA really linked to this temple? If not, the request is refused.
2. **One declaration per year** check: look up the latest declaration for this temple and financial year. If it is `DRAFT`, `SUBMITTED`, `UNDER_REVIEW`, `CLARIFICATION_REQUIRED`, `SITE_VISIT_SCHEDULED` or `APPROVED`, throw `DeclarationAlreadyExistsException`.
3. Build the header: `status = DRAFT`, `version_number = 1` (or previous + 1), and `district_id` copied from the temple. Save it.
4. `replaceAssetItems`: insert every asset row into its own table.
5. `applySummaryFields`: compute totals (gold grams, building value, …) onto the header, then save again.
6. `workflowEngineAdaptor.ensureInitiated` → `WorkflowEngine.initiate`: **the register entry is born**.
7. Write two audit entries.

**Tables written:**

| Table | What |
|---|---|
| `asset_declarations` | 1 new row, `status = DRAFT`, `physical_verification_status = NOT_INITIATED`, `clarification_round = 0` |
| `decl_immov_agri_land`, `decl_immov_building`, `decl_immov_leased`, `decl_immov_other` | immovable assets (land, buildings, leases, other) |
| `decl_mov_precious_metal`, `decl_mov_artifact`, `decl_mov_vehicle`, `decl_mov_equipment`, `decl_mov_financial` | movable assets (gold/silver, idols, vehicles, equipment, bank/FD) |
| `workflow_instances` | 1 new row: `entity_type = DECLARATION`, `entity_id = <declaration id>`, `status = DRAFT` |
| `workflow_transitions` | 1 row, action `SYSTEM_INITIATE` |
| `audit_data_events` | "CREATE AssetDeclaration" |
| `governance_action_history` | "CREATE_DRAFT" |

**Editing the draft:** `PUT /api/v1/declarations/{id}` → `DeclarationServiceImpl.update`.
- It is allowed **only while the status is `DRAFT` or `REJECTED`**. Otherwise you get `DeclarationImmutableException`.
- It updates the header, **deletes and re-inserts** all 9 item tables, and writes the two audit rows ("UPDATE_DRAFT" / "UPDATE_REJECTED").
- The status doesn't change.

---

### Phase ② TA submits

- **API:** `POST /api/v1/governance/declarations/{id}/submit`
- **Code:** `GovernanceWorkflowController` → **`GovernanceWorkflowServiceImpl.submitDeclaration`** (`@PreAuthorize(CAN_SUBMIT)`, `@Transactional`)

The old `POST /declarations/{id}/submit` path in `DeclarationServiceImpl.submit` deliberately throws `UnsupportedOperationException`. The governance URL is the only way to submit.

**Step by step:**
1. Load the declaration; check that the TA owns the temple.
2. `WorkflowEngineAdaptor.adaptSubmit` picks the right action from the register status:
   - `DRAFT` → **`SUBMIT`** (the normal case)
   - `REJECTED` → `EDIT_APPROVED` and then `RESUBMIT` (see Phase ⑦)
   - `CLARIFICATION_REQUESTED` → `RESPOND_CLARIFICATION`
3. The engine runs, including the policy **`DeclarationUniqueSubmissionPolicy`** (one active submission per temple per year).
4. If the status moved: `VersionService.snapshot` saves a frozen JSON copy of the declaration.
5. The entity status becomes `SUBMITTED`, and `submitted_by = TA user id`. Save. The code comment explains why this matters: *"DC listing queries filter by entity.status != 'DRAFT' so we must set status to SUBMITTED here or DC will not see it."*

**Status change:**
```text
① DRAFT → SUBMITTED    ② DRAFT → SUBMITTED (current_actor_role = DC)
```

**Tables:** `asset_declarations` (status, submitted_by) + **engine package** + `entity_versions` (snapshot #1).

**The DC gets a notification**, through `notification_rules` → `in_app_notifications` + email.

---

### Phase ③ DC sees it and opens it

- **The list:** `GET /api/v1/dc/declarations` → `DeclarationRepository.findAllByDistrictIdExcludingDraft`. This returns only **the DC's own district** and only rows where **`status != 'DRAFT'`** (① the entity status). That's why the dual write in step ② matters.
- **Opening it:** when the DC selects a declaration on the **temple profile page**, the frontend quietly calls `POST /api/v1/governance/declarations/{id}/under-review` (`dcHooks.confirmMarkUnderReview`, with the comment "silent background transition").
  - **Code:** `GovernanceWorkflowServiceImpl.markUnderReview` (`@PreAuthorize(CAN_ACT_DC)`: DISTRICT_COLLECTOR or SUPER_ADMIN only).
  - `jurisdictionGuard.assertDistrictScope`: if the temple is in another district, the DC gets **404**, as if the declaration doesn't exist.
  - Engine action **`BEGIN_REVIEW`**.

**Status change:**
```text
① SUBMITTED → UNDER_REVIEW    ② SUBMITTED → UNDER_REVIEW
```

**Tables:** `asset_declarations` + **engine package** (+ search summary refresh).

---

### Phase ④ DC asks a question (clarification)

- **API:** `POST /api/v1/governance/declarations/{id}/clarify` with body `{ message, sectionName }`
- **Code:** `GovernanceWorkflowServiceImpl.requestClarification`

**Step by step:**
1. District check (404 if the declaration is in another district).
2. **Limit:** if `clarification_round >= 3`, throw `ClarificationLimitExceededException` (422). A DC can ask **at most 3 times**.
3. `ClarificationEngine.requestClarification` creates a **question thread** and runs engine action **`REQUEST_CLARIFICATION`**. A comment is required, which is why `message` must be present.
4. Entity: `status = CLARIFICATION_REQUIRED`, `clarification_round + 1`.
5. It also writes an **old-style copy** of the question (the code marks this "TO BE REMOVED in Phase B").
6. From **round 2 onwards**, every SUPER_ADMIN gets a `CLARIFICATION_ESCALATION` notification.

**Status change:**
```text
① UNDER_REVIEW → CLARIFICATION_REQUIRED   ② UNDER_REVIEW → CLARIFICATION_REQUESTED (turn = TA)
```

**Tables:**

| Table | What |
|---|---|
| `clarification_threads` + `clarification_messages` | the new-style question thread |
| `declaration_clarifications` | old-style copy, direction `DC_TO_TEMPLE` |
| `asset_declarations` | status, `clarification_round` |
| engine package | + notification to the TA |

### Phase ④b TA answers

- **API:** `POST /api/v1/declarations/{id}/clarification-respond` with body `{ message }`
- **Code:** **`DeclarationServiceImpl.respondToClarification`**. Note that this lives in the declaration service, not the governance service.

**Step by step:**
1. Ownership check.
2. Save the answer in `declaration_clarifications` (direction `TEMPLE_TO_DC`).
3. Engine action **`RESPOND_CLARIFICATION`** (the actor role is hard-coded as `"TA"`).
4. Entity: `status = CLARIFICATION_RESPONDED`.
5. `SnapshotService.capture` saves a version copy. This is a **different** snapshot table from Phase ②.
6. Three audit writes.

**Status change:**
```text
① CLARIFICATION_REQUIRED → CLARIFICATION_RESPONDED   ② CLARIFICATION_REQUESTED → CLARIFICATION_RESPONDED (turn = DC)
```

**Tables:** `declaration_clarifications`, `asset_declarations`, `asset_declaration_versions`, `governance_action_history` (×2: `declarationAuditLogService` and `governanceAuditService`), `audit_data_events` + engine package.

From here the DC can approve or reject straight away (the rules allow it from `CLARIFICATION_RESPONDED`), ask again (round 2 or 3), or re-open the review.

---

### Phase ⑤ Site visit (optional physical inspection)

The DC can send an officer to the temple to check that the assets really exist. It happens in up to three steps, all under `/api/v1/governance/declarations/{id}/…`:

| Step | API | Service method | Engine action → sub-status | ① entity status | ③ site-visit status |
|---|---|---|---|---|---|
| Order the visit | `/schedule-site-visit` | `scheduleSiteVisit` | `SCHEDULE_SITE_VISIT` → `SITE_VISIT_SCHEDULED` | `SITE_VISIT_SCHEDULED` | `ORDERED_FOR_PHYSICAL_VERIFICATION` (+ ordered_at, ordered_by) |
| Visit done | `/complete-site-visit` | `completeSiteVisit` | `COMPLETE_SITE_VISIT` → `SITE_VISIT_COMPLETED` | `SITE_VISIT_COMPLETED` | (+ completed_at) |
| Passed | `/verify` | `verifyDeclaration` | `VERIFY_SITE_VISIT` → `PHYSICALLY_VERIFIED` | `VERIFIED` | `PHYSICALLY_VERIFIED` |
| **or** Failed | `/fail-site-visit` | `failSiteVisit` | `FAIL_SITE_VISIT` → `VERIFICATION_FAILED` | *unchanged* | `VERIFICATION_FAILED` |

**Important rule:** these four rules exist **only from workflow status `UNDER_REVIEW`**. If the register says `SUBMITTED` or `CLARIFICATION_RESPONDED`, the engine replies "No transition rule…" (409). The DC must "open" the declaration (Phase ③) first.

The workflow status stays `UNDER_REVIEW` throughout. Only `sub_status` changes.

**Tables per step:** `asset_declarations` (status + `physical_verification_*` columns) + engine package + `entity_versions` snapshot (not on fail).

`/flag-physical` (the older button) does the same as "order the visit" and also writes an `audit_data_events` row, "PHYSICAL_VERIFICATION_REQUESTED".

There is also a separate, older site-visit API: `/physical-verification/order` and `/update`. It writes `physical_verification_history` and `governance_action_history`, but it **does not go through the workflow engine**. It only changes ③.

---

### Phase ⑥ DC approves 🎉

- **API:** `POST /api/v1/governance/declarations/{id}/approve` with body `{ remarks }` and an optional `Idempotency-Key` header
- **Code:** **`GovernanceWorkflowServiceImpl.approveDeclaration`**

**Step by step:**
1. Load the declaration and the temple (with the district); district check → 404.
2. If the site visit **failed** (③ = `VERIFICATION_FAILED`), throw → 409 "physical verification has FAILED".
3. If it's **already approved** and has an acknowledgement number, return the **same** number. A double-click never creates two numbers.
4. Engine action **`APPROVE`**. The policy `SiteVisitBlocksApprovalPolicy` blocks again if the sub-status is `VERIFICATION_FAILED`.
5. **`AcknowledgementService.generate(districtId, financialYear)`** creates the receipt number, e.g. `ACK-BLR-2025-26-000042` (the highest existing number with that prefix + 1).
6. `generateAcknowledgementDocument` builds a **PDF** with iText and saves it to disk under `uploads/declarations/acknowledgements/ACK_DECLARATION_<id>.pdf`. This is a **file, not a table**.
7. The entity gets:
   - `status = APPROVED`
   - `acknowledgement_number`, `acknowledgement_doc_file_path`, `acknowledged_at`
   - `reviewed_at`, `reviewed_by`, `review_comment`
   
   Then it is saved.
8. `assertEntityStatusConsistency`: the ① and ② statuses must now match. If they don't, the code throws and **everything rolls back**.
9. `VersionService.snapshot` freezes the approved copy.
10. The search summary refresh is scheduled for after commit.

**Status change:**
```text
① UNDER_REVIEW / VERIFIED / CLARIFICATION_RESPONDED / … → APPROVED
② UNDER_REVIEW / CLARIFICATION_RESPONDED / SUBMITTED → APPROVED   (RESUBMITTED → RE_APPROVED)
```

**Tables:** `asset_declarations` + engine package + `entity_versions` + file on disk. After commit: the TA is notified and `temple_search_summary` is refreshed.

**The TA downloads the receipt:** `GET /api/v1/declarations/{id}/acknowledgement/download`.

### Phase ⑥b DC rejects

- **API:** `POST /api/v1/governance/declarations/{id}/reject` with body `{ remarks }` (required, because the engine demands a comment for `REJECT`)
- **Code:** `rejectDeclaration`: district check → engine `REJECT` → entity `status = REJECTED`, `reviewed_*`, `review_comment` → consistency check → snapshot.

```text
① → REJECTED   ② → REJECTED (turn = TA)
```

### Phase ⑦ TA fixes a rejected declaration and resends

1. `PUT /api/v1/declarations/{id}`: editing is allowed again, because `REJECTED` is one of the editable statuses.
2. `POST /governance/declarations/{id}/submit`: `adaptSubmit` sees `REJECTED` and runs **two** engine actions in a row:
   ```text
   ② REJECTED ─EDIT_APPROVED─► UPDATED_AFTER_APPROVAL ─RESUBMIT─► RESUBMITTED
   ① REJECTED → SUBMITTED
   ```
   This writes 2 rows in `workflow_transitions` and 2 in `notification_outbox`.
3. The DC reviews again. Approving from `RESUBMITTED` gives ② **`RE_APPROVED`**, which the consistency check translates to ① `APPROVED`.

### Side path: TA withdraws

- **API:** `POST /governance/declarations/{id}/withdraw` → `withdrawDeclaration`
- The service allows it when ① is `SUBMITTED` or `CLARIFICATION_REQUIRED`. Then it runs engine `WITHDRAW` and sets ① to `WITHDRAWN`.

---

## 6. Every table involved, in one place

| Table | Who writes it | When |
|---|---|---|
| `asset_declarations` | `DeclarationServiceImpl`, `GovernanceWorkflowServiceImpl` | every phase: **the file** |
| 9 × `decl_immov_*` / `decl_mov_*` | `DeclarationServiceImpl.replaceAssetItems` | create and edit (delete + re-insert) |
| `workflow_instances` | `WorkflowEngineImpl` | born at create, updated on every action: **the register** |
| `workflow_transitions` | `WorkflowEngineImpl` | +1 row on every action (history) |
| `workflow_idempotency_records` | `WorkflowEngineImpl` | actions with an idempotency key |
| `governance_action_history` | `GovernanceAuditService`, `DeclarationAuditLogService` | every action + create/edit |
| `audit_data_events` | `AuditService` | create, edit, clarification response, flag-physical |
| `entity_versions` | `VersionService.snapshot` | submit, site-visit steps, approve, reject |
| `asset_declaration_versions` | `SnapshotService.capture` | TA's clarification response (read by `/versions` and `/diff`) |
| `clarification_threads`, `clarification_messages` | `ClarificationEngineImpl` | DC asks a question |
| `declaration_clarifications` | both services | DC question + TA answer (old-style copy) |
| `physical_verification_history` | `orderPhysicalVerification` / `updatePhysicalVerification` | old site-visit API only |
| `notification_outbox` | engine (same transaction) → router marks it `DISPATCHED` | every action |
| `in_app_notifications`, `email_outbox`, `email_delivery_logs` | notification pipeline | after commit |
| `temple_timeline_events` | timeline listener | after commit |
| `temple_search_summary` | summary service | after commit |
| *(file)* `uploads/declarations/acknowledgements/…pdf` | `approveDeclaration` | approval |

---

## 7. A worked example: rows written for declaration #7

| Moment | `asset_declarations.status` | `workflow_instances.status / sub_status` | New `workflow_transitions` row | Other notable rows |
|---|---|---|---|---|
| TA creates | DRAFT | DRAFT / – | SYSTEM_INITIATE | 9 item tables, audit ×2 |
| TA submits | SUBMITTED | SUBMITTED / – | SUBMIT | outbox, entity_versions v1, DC notified |
| DC opens | UNDER_REVIEW | UNDER_REVIEW / – | BEGIN_REVIEW | outbox |
| DC asks (round 1) | CLARIFICATION_REQUIRED | CLARIFICATION_REQUESTED / – | REQUEST_CLARIFICATION | thread + message, legacy clarification, TA notified |
| TA answers | CLARIFICATION_RESPONDED | CLARIFICATION_RESPONDED / – | RESPOND_CLARIFICATION | asset_declaration_versions, legacy clarification |
| DC opens again | UNDER_REVIEW | UNDER_REVIEW / – | BEGIN_REVIEW | |
| DC orders visit | SITE_VISIT_SCHEDULED | UNDER_REVIEW / SITE_VISIT_SCHEDULED | SCHEDULE_SITE_VISIT | pv status = ORDERED…, entity_versions |
| Visit done | SITE_VISIT_COMPLETED | UNDER_REVIEW / SITE_VISIT_COMPLETED | COMPLETE_SITE_VISIT | entity_versions |
| Passed | VERIFIED | UNDER_REVIEW / PHYSICALLY_VERIFIED | VERIFY_SITE_VISIT | pv status = PHYSICALLY_VERIFIED |
| DC approves | **APPROVED** + `ACK-…-000042` | **APPROVED** / (sub-status kept) | APPROVE | PDF file, entity_versions, TA notified |

By the end, declaration #7 has **10 rows in `workflow_transitions`**, which make up its complete, permanent history.

---

## 8. Things I noticed while tracing (not changed, as agreed)

New ones from this trace:
1. **Withdrawing during a clarification fails.** The service allows withdraw when ① is `CLARIFICATION_REQUIRED`, but the engine only has a `WITHDRAW` rule from `SUBMITTED`. The TA gets a 409 "No transition rule".
2. **Site visit needs `UNDER_REVIEW`.** The frontend only marks it under review silently on the **temple profile page**. The declaration detail page (`DcDeclarationDetailPage`) doesn't call it, so "Schedule site visit" there on a freshly `SUBMITTED` declaration will likely fail with 409.
3. **A pending site visit doesn't block approval.** A correction to my earlier chapters: `SiteVisitBlocksApprovalPolicy` blocks **only `VERIFICATION_FAILED`**. A DC can approve while a visit is scheduled but not yet completed.
4. **Consistency is only checked on approve and reject.** Site-visit steps set ① to `SITE_VISIT_*` / `VERIFIED` while ② stays `UNDER_REVIEW`. This is intentional, but the two columns legitimately disagree during that stage, so use the table in §2 when debugging.
5. **Answering via "submit" leaves ① stale.** If the TA answers a clarification through the **submit** endpoint instead of `/clarification-respond`, ② moves to `CLARIFICATION_RESPONDED` but ① stays `CLARIFICATION_REQUIRED`. `submitDeclaration` only updates ① from `DRAFT`/`REJECTED`. The frontend uses `/clarification-respond`, so this is latent.

Already reported in Chapter 12 and still relevant here:
- duplicate acknowledgement numbers are possible (#14)
- duplicate emails, and no notification for repeat clarification rounds (#13)
- `annualRent` stores the monthly value (#17)
- DC_STAFF can approve through the v2 `/action` URL, and that URL skips the ack and the ① update (#6, #7)

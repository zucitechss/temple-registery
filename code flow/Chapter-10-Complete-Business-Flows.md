# CHAPTER 10 — Complete Business Flows

This chapter connects everything from Chapters 1–9. I traced four real flows from the frontend call to the database and out to notifications. I also checked **which API the frontend actually calls** for each module, which settles the "three workflow APIs" question from Chapter 5 and **corrects two of my earlier findings** (§10.7).

## 10.0 The mental model: three layers for every governed action

```text
                    ┌────────────────────────────────────────────────────────────┐
  Frontend  ──────► │ ① ORCHESTRATOR  (GovernanceWorkflowServiceImpl,             │
  (v1 URLs)         │    TempleProfileWorkflowServiceImpl, DeclarationServiceImpl) │
                    │    • @PreAuthorize + district/ownership guards              │
                    │    • business rules (3 clarification rounds, failed visit)  │
                    │    • side-effects: ack number + PDF, snapshots, summaries   │
                    └───────────────┬───────────────────────────┬────────────────┘
                                    │ "move the state"          │ "update the record"
                                    ▼                           ▼
                    ┌───────────────────────────┐   ┌──────────────────────────────┐
                    │ ② WORKFLOW ENGINE          │   │ ③ DOMAIN ENTITY               │
                    │ WorkflowEngineImpl         │   │ asset_declarations.status     │
                    │ workflow_instances.status  │   │ trusts / temple_profile_*     │
                    │ + workflow_transitions     │   │ (the "legacy" status column)  │
                    │ + notification_outbox      │   └──────────────────────────────┘
                    └───────────────────────────┘
                     "authoritative" (per comments)      kept in sync by ① ("dual write")
```

**The engine owns the state machine. The orchestrator owns everything else.** Both statuses must agree, because:
- The DC lists read the **entity** status (`…ExcludingDraft` queries filter `asset_declarations.status != 'DRAFT'`). The comment in `submitDeclaration` says: *"CRITICAL: DC listing queries filter by entity.status != 'DRAFT' so we must set status to SUBMITTED here or DC will not see it."*
- Badges and dashboards read the **engine** status (`workflow_instances`).

## 10.1 The state machine (`TransitionRuleRegistry`)

Each rule is `(entityType, fromStatus, action) → toStatus`, plus the role required. `"*"` means the rule applies to all entity types. The universal flow:

```text
                        SUBMIT (TA)                  BEGIN_REVIEW (DC)
          DRAFT ───────────────────────► SUBMITTED ─────────────────────► UNDER_REVIEW
            ▲                              │  │  │                          │  │  │
   (initiate)│                  WITHDRAW(TA)│  │  │ REQUEST_CLARIFICATION   │  │  │
            │                              ▼  │  │ / SEND_BACK (DC)         │  │  │
            │                        WITHDRAWN│  ▼                          │  │  │
            │                                 │ CLARIFICATION_REQUESTED ◄────┘  │  │
            │                                 │   │ RESPOND_CLARIFICATION (TA)  │  │
            │                                 │   ▼                             │  │
            │                                 │ CLARIFICATION_RESPONDED ──BEGIN_REVIEW──┘
            │                                 │   │                             │
            │            APPROVE (DC) ◄───────┴───┴─────────────────────────────┘ ── REJECT (DC) ──► REJECTED
            │                 │                                                                        │
            │                 ▼                                                                        │
            │             APPROVED ──EDIT_APPROVED (TA)──► UPDATED_AFTER_APPROVAL ◄──EDIT_APPROVED(TA)──┘
            │                 │                                   │ RESUBMIT (TA)
            │   AUTO_SUPERSEDE│(SYSTEM)                           ▼
            │                 ▼                              RESUBMITTED ──APPROVE / RE_APPROVE (DC)──► RE_APPROVED
            │            SUPERSEDED                               │ REJECT_EDIT (DC) ──► RE_APPROVED (reverts)
            │                                                     │ REJECT (DC) ──► REJECTED
   SYSTEM:  SUBMITTED/UNDER_REVIEW/CLARIFICATION_RESPONDED/RESUBMITTED ──FLAG_OVERDUE──► OVERDUE
```

On top of that, there are **sub-statuses**: a second, finer label that changes while the main status stays the same.

| Entity | Action (DC) | From → To | Sub-status |
|---|---|---|---|
| DECLARATION | `SCHEDULE_SITE_VISIT` | UNDER_REVIEW → UNDER_REVIEW | `SITE_VISIT_SCHEDULED` |
| DECLARATION | `COMPLETE_SITE_VISIT` / `VERIFY_SITE_VISIT` / `FAIL_SITE_VISIT` | UNDER_REVIEW → UNDER_REVIEW | `SITE_VISIT_COMPLETED` / `PHYSICALLY_VERIFIED` / `VERIFICATION_FAILED` |
| TEMPLE_PROFILE | `VERIFY_TEMPLE_PROFILE` | SUBMITTED / UNDER_REVIEW → APPROVED | — |
| TEMPLE_PROFILE | `FLAG_TEMPLE_PROFILE` | SUBMITTED / UNDER_REVIEW / APPROVED → CLARIFICATION_REQUESTED | `FLAGGED` |
| any | `WARN_DEADLINE_APPROACHING` (SYSTEM) | unchanged | `DEADLINE_WARNING_SENT` |

The site-visit rules exist **only from `UNDER_REVIEW`**. A DC must "begin review" (`/under-review`) before scheduling a visit. Otherwise the engine throws `WorkflowException("No transition rule…")`, which the client receives as 409.

**The same stage has two names.** The engine's `WorkflowStatus` and the declaration's `DeclarationStatus` are separate enums:

| Engine (`workflow_instances.status` / `sub_status`) | Declaration (`asset_declarations.status`) |
|---|---|
| `SUBMITTED` | `SUBMITTED` |
| `UNDER_REVIEW` | `UNDER_REVIEW` |
| `CLARIFICATION_REQUESTED` | **`CLARIFICATION_REQUIRED`** |
| `CLARIFICATION_RESPONDED` | `CLARIFICATION_RESPONDED` |
| `UNDER_REVIEW` + sub `SITE_VISIT_SCHEDULED` | **`SITE_VISIT_SCHEDULED`** |
| `UNDER_REVIEW` + sub `SITE_VISIT_COMPLETED` | `SITE_VISIT_COMPLETED` |
| `APPROVED` | `APPROVED` |

When you debug "the DC sees X but the TA sees Y", compare **both** columns.

## 10.2 `WorkflowEngineImpl.execute`: the 13 steps

Every governed transition passes through this one method. It is `@Transactional(isolation = READ_COMMITTED)` and **joins** the orchestrator's transaction.

| # | Step (real code) | Fails with |
|---|---|---|
| 1 | **Idempotency.** If `idempotencyKey` was seen with `SUCCESS`, return the cached result (`workflow_idempotency_records`) | — |
| 2 | Load `WorkflowInstance` | `EntityNotFoundException` → 404 |
| 3 | `ruleRegistry.find(entityType, fromStatus, action)` | `WorkflowException("No transition rule…")` → 409 |
| 4 | **Role check**, `roleMatches(rule.requiredRole, ctx)`. `"DC"` accepts `ctx.isDc() \|\| isSuperAdmin()`, and **`isDc()` includes `DC_STAFF`** (see §10.6) | `WorkflowException` → 409 |
| 5 | **Jurisdiction.** DC's district must equal the instance's district | 409, *with both district ids in the message* (Chapter 9) |
| 6 | **Ownership.** TA must own the instance's temple | 409 |
| 6b | **Comment required** for `REJECT`, `REQUEST_CLARIFICATION`, `SEND_BACK`, `RESPOND_CLARIFICATION` | 409 |
| 7 | **Policies**: every `WorkflowPolicy` bean matching entity + action | `WorkflowException("Policy denied: …")` → 409 |
| 8 | **Version check** against `expectedVersion` | `jakarta…OptimisticLockException` → likely **500** (Chapter 9) |
| 9 | Apply: set `status`, `subStatus`, `statusUpdatedAt`, `currentActorRole` (whose turn next), `submittedAt`; bump `versionNumber` on `EDIT_APPROVED`; `save` (`@Version` +1) | 409 on concurrent update |
| 10 | Append an immutable `WorkflowTransition` row + `governanceAuditService.logWorkflowTransition` | — |
| 11 | **`writeToOutbox(event)`**: a `notification_outbox` row in the **same transaction**. Failures are caught and **not** rethrown | — |
| 12 | Save the idempotency record with the serialized result | — |
| 13 | `eventPublisher.publishEvent(domainEvent)`: listeners with `AFTER_COMMIT` run after the whole transaction commits | — |

**The policies that actually run** are the `@Component` classes in `service/workflow/policy/`:

| Policy | Entity / action | Rule |
|---|---|---|
| `SiteVisitBlocksApprovalPolicy` | DECLARATION / APPROVE | block approval while the site-visit sub-status is unresolved or failed |
| `DeclarationUniqueSubmissionPolicy` | DECLARATION / SUBMIT | one active declaration per temple per financial year (checked against instance metadata; allows if no year metadata) |
| `ClarificationCommentRequiredPolicy` | * / REQUEST_CLARIFICATION | (always `allow()`: enforcement moved to step 6b) |
| ~~`DeclarationApprovalPolicy`~~ | DECLARATION / APPROVE | **not a bean** (`@Deprecated`, no `@Component`), never runs |

Spring discovers these automatically. `WorkflowEngineImpl` asks for `List<WorkflowPolicy> policies` in its constructor, and Spring injects **every** bean implementing that interface. To add a rule, you add a class; there is no registration step.

---

## 10.3 FLOW A: an asset declaration, from draft to acknowledgement

Frontend calls, verified in [declarationApi.ts](../frontend/src/features/declaration/declarationApi.ts) and [governanceApi.ts](../frontend/src/features/governance/governanceApi.ts):

```text
 TA                                   Backend                                               Tables written
 ──                                   ───────                                               ──────────────
 ① POST /api/v1/temples/42/declarations
    DeclarationController.create → DeclarationServiceImpl.create   (Ch.6)
       ownership · one-active-per-FY · INSERT header · replace 9 item tables
       · workflowEngineAdaptor.ensureInitiated → WorkflowEngine.initiate          asset_declarations (DRAFT), decl_*,
                                                                                  workflow_instances (DRAFT), workflow_transitions
 ② POST /api/v1/governance/declarations/{id}/submit
    GovernanceWorkflowServiceImpl.submitDeclaration   @PreAuthorize(CAN_SUBMIT)
       ownershipGuard
       adaptSubmit: status DRAFT → action SUBMIT (CLARIFICATION_REQUESTED → RESPOND_CLARIFICATION,
                    UPDATED_AFTER_APPROVAL → RESUBMIT, REJECTED → EDIT_APPROVED + RESUBMIT)
          └─ WorkflowEngine.execute: rule ✓ · role TA ✓ · DeclarationUniqueSubmissionPolicy ✓
             → SUBMITTED · transition · outbox · event                             workflow_*, notification_outbox
       versionService.snapshot (if transitioned)                                   entity_versions
       declaration.status = SUBMITTED  (dual write)                                asset_declarations
 ─────────────────────────────────────── DC side ───────────────────────────────────────────────
 ③ DC opens list: /api/v1/dc/declarations → findAllByDistrictIdExcludingDraft (entity status!)
 ④ POST /governance/declarations/{id}/under-review → markUnderReview → BEGIN_REVIEW → UNDER_REVIEW
 ⑤ POST /governance/declarations/{id}/clarify  {message, sectionName}
    requestClarification   @PreAuthorize(CAN_ACT_DC)
       assertDistrictScope (404 if other district)
       clarificationRound >= 3 → ClarificationLimitExceededException (422, TRM-DECL-009)
       clarificationEngine.requestClarification → thread + engine REQUEST_CLARIFICATION      clarification_threads/messages
       declaration.status = CLARIFICATION_REQUIRED; round++                                  asset_declarations
       legacy DeclarationClarification row ("TO BE REMOVED in Phase B")                     declaration_clarifications
       round >= 2 → notificationPublisher.publish(each SUPER_ADMIN, "CLARIFICATION_ESCALATION")
 ⑥ TA: POST /api/v1/declarations/{id}/clarification-respond  {message}
    DeclarationServiceImpl.respondToClarification   (@PreAuthorize TEMPLE_AUTHORITY_ONLY on controller)
       legacy clarification row · engine RESPOND_CLARIFICATION (actorRole hard-coded "TA")
       declaration.status = CLARIFICATION_RESPONDED
       snapshotService.capture (asset_declaration_versions) + 3 audit writers
 ⑦ Site visit (optional): /schedule-site-visit → /complete-site-visit → /verify | /fail-site-visit
       each: assertDistrictScope · engine sub-status transition · entity status + physical_verification_*
       · versionService.snapshot
 ⑧ POST /governance/declarations/{id}/approve  (+ optional Idempotency-Key)
    approveDeclaration   (Ch.6 §6.4, step by step)
       scope · VERIFICATION_FAILED blocks · already-approved → same ack (idempotent)
       adaptApprove → engine APPROVE (SiteVisitBlocksApprovalPolicy)
       ackNumber = AcknowledgementService.generate  → "ACK-BLR-2025-26-000042"
       PDF → uploads/…  · entity APPROVED + ack fields · assertEntityStatusConsistency
       versionService.snapshot · summaryService.scheduleRefresh
 ─────────────────────────────────────── after COMMIT ──────────────────────────────────────────
 ⑨ NotificationRouter.onGovernanceDomainEvent (@Async) ──┐
    NotificationRouter.dispatchPending (every 5 s)  ─────┴──► route() → rules → TA in-app + email + SSE
    GovernanceDomainEventTimelineListener → temple_timeline_events
    TempleSearchSummaryService.refresh (@Async) → temple_search_summary
 ⑩ TA downloads GET /api/v1/declarations/{id}/acknowledgement/download → PDF
```

**Two snapshot systems are both active:**
- `VersionService.snapshot` → `entity_versions` (generic, the governance path)
- `SnapshotService.capture` → `asset_declaration_versions` (declaration-specific, the clarification-response path)

The diff and versions endpoints (`/declarations/{id}/diff`, `/versions`) read the latter. So which versions appear depends on which path created them.

## 10.4 FLOW B: the notification pipeline

```text
WorkflowEngine.execute (inside the business TX)
   ├─ writeToOutbox(event)  → notification_outbox (PENDING)     ← durable path
   └─ publishEvent(event)                                        ← fast path
                    │ COMMIT
      ┌─────────────┴─────────────────────────────┐
      ▼ FAST (after commit, @Async)                ▼ DURABLE (@Scheduled every 5 s, one TX for ≤50 rows)
NotificationRouter.onGovernanceDomainEvent      NotificationRouter.dispatchPending
      └──────────────┬────────────────────────────┘      (marks DISPATCHED / FAILED; retryFailed every 60 s, max 3)
                     ▼
            route(event)
              ruleRepo.findMatchingRules(eventType, entityType, action)     ← notification_rules table (admin-editable)
              for each rule → recipients by recipientType:
                  "TA"    → recipientResolver.getTempleAuthorityIds(templeId)
                  "DC"    → getDistrictCollectorIds(districtId)
                  "ADMIN" → getSuperAdminIds()
              for each recipient:
                  dedupKey = eventType|entityType|entityId|action|recipientId|channel
                  deduplicationGuard.isDuplicate(dedupKey)   → inAppRepo.existsByIdempotencyKey(dedupKey)
                  NotificationDispatchServiceImpl.dispatch(event, rule, recipient)  @Transactional(REQUIRES_NEW)
                      builds rich title/body (temple name, actor label, reason) + redirectUrl
                      IN_APP/BOTH → preference check → NotificationServiceImpl.createInAppNotification
                                     (own key: recipient|ENTITY_ACTION|entityId|instanceId; skip if exists)
                                   → sseService.push + pushBadgeCount
                      EMAIL/BOTH  → preference check → EmailDeliveryService.enqueue → email_outbox (PENDING)
                                                   │
                                                   ▼ @Scheduled every 10 s: processQueue (≤50, priority first)
                                              Thymeleaf template (EmailTemplateResolver: key → email/<key>.html)
                                              → JavaMailSender (only if spring.mail.enabled)
                                              → email_delivery_logs; FAILED → retry every 5 min (back-off) → DEAD_LETTER
```

**Why both paths exist.** The code's own comment: *"The outbox provides durability — this is the speed path."* SSE should feel instant, but a server crash between commit and the fast path mustn't lose notifications.

**What the two-path design breaks.** Both paths route the **same** event.
1. **The router-level dedup never matches.** The router checks `existsByIdempotencyKey("eventType|entityType|entityId|action|recipient|channel")`, but in-app rows are stored with `"recipient|ENTITY_ACTION|entityId|instanceId"`. Those strings can never be equal, so the guard always says "not a duplicate".
2. **In-app is saved by the second key**, so the in-app *row* isn't duplicated. But `dispatch` still calls `sseService.push(...)` after the silent skip, so each **SSE toast fires twice**.
3. **Emails have no dedup at all.** `EmailDeliveryService.enqueue` always inserts, so every email-channel rule likely sends **two emails per transition**: one per path.
4. **Repeat events get swallowed.** The in-app key has no round or transition id. The 2nd and 3rd clarification requests on the same declaration produce the same key (`recipient|DECLARATION_REQUEST_CLARIFICATION|declId|instanceId`), so the TA **gets no in-app notification** for rounds 2–3. The same happens for repeated resubmits, approvals after edits, and so on. The email still goes out (twice).

**SSE is in-memory.** `SseNotificationService` keeps `Map<Long userId, List<SseEmitter>>` in a `ConcurrentHashMap`, with a 30-minute emitter timeout. That's fine on one server. With two instances, a user connected to instance A misses pushes generated on B. The code has an honest `ponytail:`-style note elsewhere about single-node assumptions; this is another one.

**`DC` recipients.** `getDistrictCollectorIds(districtId)` only; there's no DC_STAFF recipient type. Whether DC office staff should be notified is a product question.

## 10.5 FLOW C: temple profile edit → DC approval

```text
TA  POST /api/v1/temples/{id}/profile/staging     → TempleProfileStagingServiceImpl.createOrUpdateDraft
                                                     (accessGuard ✓, ownership ✓) → temple_profile_staging (DRAFT)
TA  POST /api/v1/temples/{id}/profile/submit      → submitForReview
       accessGuard · ownership · assertNotSuspended
       staging = DRAFT, or UPDATED_AFTER_APPROVAL, or a new draft copied from the temple
       workflowEngine.initiate(TEMPLE_PROFILE, staging.id)        (idempotent)
       adaptSubmit → SUBMIT or RESUBMIT      (SA passes null district so the adaptor uses role "TA")
       staging.status = PENDING_REVIEW   ("projection only — never authority")
DC  POST /api/v1/dc/profiles/{stagingId}/approve  → TempleProfileWorkflowServiceImpl.approveProfile
       @PreAuthorize(CAN_APPROVE) (SA, DC — not DC_STAFF, despite the controller's IS_DC_ROLE)
       status from the ENGINE; assertReviewable; assertDistrictScope
       temple_profile_current: archive old → temple_profile_history, then UPSERT in place
           (comment explains why: delete+insert would break the unique temple_id within one flush)
       promoteToTemple(temple, staging); temple.verificationStatus = VERIFIED
       staging.status = APPROVED
       previous APPROVED staging → engine executeSystem(AUTO_SUPERSEDE) → SUPERSEDED
       engine APPROVE / RE_APPROVE with expectedVersion
```

**A subtle one.** To find the *previous* approved version, it saves the current staging as `APPROVED` first, then calls `findFirstByTempleIdAndStatus(templeId, APPROVED)` and skips it if the ids match. With two `APPROVED` rows, "first" isn't ordered, so it may return the *current* row. The previous one is then never superseded, and two versions stay `APPROVED`. It's worth a test with a temple approved twice.

DC verification of the temple itself (`/api/v1/dc/temples/{id}/verify|flag|unflag` → `DcTempleVerificationServiceImpl` → adaptor `VERIFY_/FLAG_/UNFLAG_TEMPLE_PROFILE`) follows the same orchestrator → engine pattern, but still uses the deprecated `NotificationHelper`.

## 10.6 FLOW D: overdue detection is inactive

```text
OverdueWorkflowScheduler.flagOverdueInstances   cron 0 30 20 * * * UTC (02:00 IST)   @Transactional
   instanceRepo.findOverdueInstances(PENDING_STATUSES, now)
        … WHERE wi.deadlineAt IS NOT NULL AND wi.deadlineAt < :now …
   for each → workflowEngine.executeSystem(FLAG_OVERDUE) + propagateOverdueToDomainEntity (is_overdue = true)
OverdueWorkflowScheduler.warnDeadlineApproaching   cron 0 30 3 * * * UTC   → WARN_DEADLINE_APPROACHING
```

1. **`deadline_at` is never populated.** I searched all Java code and every migration: nothing sets `WorkflowInstance.deadlineAt`. `V1` only creates the column, and `WorkflowEngine.initiate` doesn't set it. The declaration's `due_date` lives on `asset_declarations` and is never copied over. So `findOverdueInstances` returns **nothing, ever**:
   - no `OVERDUE` status
   - no `is_overdue = true`
   - no deadline warnings
   - "overdue" counts on dashboards stay at 0 (apart from any seeded rows)

   The old bulk `DeclarationRepository.markOverdue` is only called from the dead `OverdueScheduler`.
2. **Latent transaction trap, if deadlines ever get set.** The loop runs in one `@Transactional`, and each `executeSystem` **joins** it. If one instance throws, the engine's proxy marks the shared transaction **rollback-only**. The `catch` swallows the exception, but the final commit fails with `UnexpectedRollbackException`, so **every** instance flagged that night is undone, while the log says `flagged=N`. The fix pattern is a `REQUIRES_NEW` per item. The same shape exists in `warnDeadlineApproaching`.

## 10.7 Which API is authoritative? (settled from frontend usage)

| Module / action | What the frontend calls | Backend path | Complete? |
|---|---|---|---|
| Declaration create/update | `/api/v1/temples/{id}/declarations`, `PUT /api/v1/declarations/{id}` | `DeclarationServiceImpl` | ✅ |
| Declaration submit / withdraw / approve / reject / send-back / clarify / under-review / site visit / physical verification | **`/api/v1/governance/declarations/{id}/…`** | `GovernanceWorkflowServiceImpl` → adaptor → engine | ✅ canonical |
| Declaration clarification response | `/api/v1/declarations/{id}/clarification-respond` | `DeclarationServiceImpl.respondToClarification` → engine | ✅ |
| Trust submit / approve / send-back / reject | **`/api/v1/governance/trusts/{id}/…`** | `GovernanceWorkflowServiceImpl` | ✅ canonical |
| Temple profile submit | `/api/v1/temples/{id}/profile/submit` | `TempleProfileStagingServiceImpl` | ✅ |
| Temple profile approve / reject | `/api/v1/dc/profiles/{stagingId}/approve|reject` | `TempleProfileWorkflowServiceImpl` | ✅ |
| Temple verify / flag / unflag | `/api/v1/dc/temples/{id}/verify|flag|unflag` | `DcTempleVerificationServiceImpl` | ✅ |
| DC workflow dashboard + pending badge | `/api/v2/workflow/dashboard`, `/count/pending` | `WorkflowController` → engine queries | ✅ read-only |
| **v2 generic action** `POST /api/v2/workflow/{id}/action` | only `WorkflowActionPanel`, inside **`WorkflowGovernancePanel`, which no page renders** | engine only | ⚠️ **incomplete** (below) |
| **v2 clarifications, history** | only `ClarificationInbox` / `WorkflowTimeline`, also only inside that unrendered panel | `ClarificationEngine`, history | ⚠️ unused by UI |

So **v1 governance is the real path. v2's write endpoints are live on the server but unused by the UI.** That's dangerous in two ways, because the endpoints are still reachable by anyone with a token and a REST client:

1. **v2 `/action` skips everything the orchestrator does.** Calling `{"action":"APPROVE"}` on a declaration's instance moves `workflow_instances` to `APPROVED` but:
   - no acknowledgement number, no PDF
   - `asset_declarations.status` stays `UNDER_REVIEW`
   - no snapshot, no search-summary refresh
   - no 3-round clarification limit (v2 `/clarification` bypasses it too)
   - no `assertEntityStatusConsistency`

   After that, the two statuses **disagree permanently**. And because the engine now says `APPROVED`, a later v1 `/approve` fails with "No transition rule … APPROVED -[APPROVE]->".
2. **`DC_STAFF` can approve and reject through v2.** The endpoint's `@PreAuthorize` includes `DC_STAFF`. `ActionContextResolver` keeps the role as `"DC_STAFF"`, and `ActionContext.isDc()` returns true for it, so the engine's `"DC"` rules pass. Everywhere else, approving requires `CAN_ACT_DC`/`CAN_APPROVE` (SA + DC only). This is a **privilege escalation** reachable with a DC_STAFF token and `curl`.

**Corrections to earlier chapters.** Two findings I rated 🟠 as user-facing are **not reachable from the current UI**, because only the unrendered panel calls them:
- Chapter 5: the ambiguous `GET /api/v2/workflow/{id}/history` mapping (used only by `WorkflowTimeline`).
- Chapter 8: entity serialization on the v2 clarification endpoints (used only by `ClarificationInbox`).

Both remain real server bugs for direct API callers, and would surface the day someone renders `WorkflowGovernancePanel`. I'd now rate them 🟡.

## 10.8 The five questions, for the workflow engine

```text
Who calls me?   WorkflowEngineAdaptor (from Governance, Trust, Declaration, Staging, DC services)
                · DeclarationServiceImpl.respondToClarification · TempleProfileWorkflowServiceImpl
                · ClarificationEngineImpl · OverdueWorkflowScheduler · WorkflowController (v2)
      ↓
[WorkflowEngineImpl]
      ↓
Who do I call?  TransitionRuleRegistry · List<WorkflowPolicy> · WorkflowInstance/Transition/Outbox/Idempotency repos
                · GovernanceAuditService · VersionService · ApplicationEventPublisher

INPUT   instanceId · WorkflowActionRequest{action, expectedVersion, idempotencyKey, comment} · ActionContext{actor, role, district, temples}
  ↓     idempotency → rule → role → district → ownership → comment → policies → version → apply → audit → outbox → event
OUTPUT  WorkflowTransitionResult{new status, version, availableActions}
        + rows in workflow_instances / workflow_transitions / governance_action_history / notification_outbox
        + GovernanceDomainEvent after commit
NOT MY JOB  entity status, ack numbers, PDFs, snapshots of domain data, clarification round limits  (orchestrator's job)
```

## 10.9 Findings from this chapter

| Severity | Finding | Where |
|---|---|---|
| 🟠 | **DC_STAFF can approve/reject via v2** (`isDc()` includes DC_STAFF; the endpoint allows DC_STAFF) | `WorkflowController.executeAction`, `ActionContext.isDc`, `WorkflowEngineImpl` step 4 |
| 🟠 | **v2 `/action` bypasses orchestration.** An engine-only APPROVE leaves the entity status stale, issues no ack, and blocks the v1 path afterwards | `WorkflowController` vs `GovernanceWorkflowServiceImpl` |
| 🟠 | **Overdue detection never runs**: `deadline_at` is never set | `WorkflowEngine.initiate`, `OverdueWorkflowScheduler` |
| 🟠 | **Duplicate emails** (both routing paths, no email dedup) and **double SSE toasts** (likely) | `NotificationRouter`, `EmailDeliveryService.enqueue`, `NotificationDispatchServiceImpl` |
| 🟠 | **Repeat notifications suppressed**: clarification rounds 2–3, repeat resubmits/approvals give no in-app notification (key lacks round/transition) | `NotificationServiceImpl.createInAppNotification` |
| 🟡 | Router dedup key never matches the stored in-app key (dead guard) | `NotificationRouter.buildDedupKey` |
| 🟡 | Scheduler loop shares one transaction; one failure rolls back the whole batch (latent) | `OverdueWorkflowScheduler` |
| 🟡 | Previous approved profile may not be superseded (unordered `findFirst` after saving the new APPROVED) | `TempleProfileWorkflowServiceImpl.approveProfile` |
| 🟡 | Two snapshot systems, two clarification stores; which versions/diffs appear depends on the path | `VersionService` vs `SnapshotService`; `clarification_threads` vs `declaration_clarifications` |
| ⚪ | SSE emitters in memory (single-node only); site-visit actions require `UNDER_REVIEW` first; respond path hard-codes role `"TA"` | — |
| ↘ | **Downgraded** to 🟡 (UI doesn't reach them): Ch.5 ambiguous v2 history mapping; Ch.8 v2 clarification entity serialization | §10.7 |

---

**Next: Chapter 11 — Testing.** It covers:
- the 105 test files by kind: unit tests (Mockito), controller slice tests (MockMvc + `spring-security-test`), security tests, integration tests (Testcontainers MySQL vs H2), and property-based tests (jqwik)
- how Surefire's include/exclude rules decide what actually runs: `**/integration/**IT.java` is **excluded**
- a few representative tests walked through Arrange → Act → Assert, with the production code each exercises
- which of the findings above the current tests could or could not catch

Say **"continue"** when ready.

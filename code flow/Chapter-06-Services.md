# CHAPTER 6 — Services

In the office analogy, services are the **managers**: they decide *whether* something is allowed and *what* changes. Controllers only receive and hand over. Repositories only store and fetch.

## 6.0 What a service method does here: the recipe

Almost every write method in `service/impl/**` follows the same seven steps. [TrustServiceImpl.create](../backend/src/main/java/com/templeregistry/service/impl/trust/TrustServiceImpl.java#L89-L127) is the clearest example:

```java
@Override
@PreAuthorize(RoleConstants.CAN_SUBMIT)                 // ① WHO may call (SA, TA) — checked by the proxy
@Transactional                                          //    one all-or-nothing DB transaction
public TrustResponse create(Long templeId, CreateTrustRequest rq) {

    Temple temple = templeRepository.findById(templeId)                      // ② LOAD what the rules need
            .orElseThrow(() -> new EntityNotFoundException("Temple", templeId));
    ScopeHelper.Claims claims = currentClaims();

    ownershipGuard.assertOwnsTemple(templeId);                               // ③ GUARD the data scope
    jurisdictionGuard.assertDistrictScope(temple, claims);

    trustValidationService.validateTrustRequest(rq, null);                   // ④ BUSINESS RULES
    if (trustRepository.existsByTempleIdAndDeletedFalse(templeId))
        throw new DuplicateResourceException("A trust is already registered for this temple.");

    Trust trust = Trust.builder().templeId(templeId)                         // ⑤ MUTATE
            .trustPANNumber(rq.getPanNumber().trim().toUpperCase())          //    (AesEncryptionConverter encrypts on INSERT)
            ... .status(TrustStatus.ACTIVE).build();
    Trust saved = trustRepository.save(trust);                               //    INSERT INTO trusts
    temple.setTrustRegistered(true);
    templeRepository.save(temple);                                           //    UPDATE temples (… AND version = ?)

    workflowEngineAdaptor.ensureInitiated(WorkflowEntityType.TRUST,          // ⑥ SIDE-EFFECTS
        saved.getId(), templeId, temple.getDistrictId(), currentUserId(), claims.role());  // → workflow_instances row
    summaryService.scheduleRefresh(templeId);                                //    → rebuild search row AFTER commit

    return toResponse(saved);                                                // ⑦ MAP entity → response DTO
}
```

| Step | Why it's in the service and not the controller |
|---|---|
| ① Authorize | The service is the final gate. Any future caller (another controller, a scheduler, a test) gets the same protection |
| ② Load | Rules need data the client didn't send, such as the temple's district |
| ③ Guard | "Your temple / your district" can only be checked *after* loading |
| ④ Rules | e.g. "one trust per temple", PAN format, dates (`TrustValidationService`) |
| ⑤ Mutate | Build or modify entities. Hibernate turns this into SQL at commit |
| ⑥ Side-effects | Workflow instance, audit log, notifications, search-summary refresh, snapshots |
| ⑦ Map | Never return the entity (Chapter 8) |

When you read any service method, go looking for these seven steps. If one is **missing**, that's usually where a bug lives. For example, step ③ is missing from the temple photo upload/delete methods (Chapter 5), and `accessGuard.assertCanEdit()` is missing from `TrustServiceImpl.create` and `DeclarationServiceImpl.create`/`update`.

Also notice the `assertDistrictScope(temple, …)` call here uses a temple loaded with plain `findById`, not `findWithGeoById`. It still works, because we're inside `@Transactional` and the lazy `hobli → taluk → district` chain loads on demand, but that costs 3 extra `SELECT`s.

## 6.1 Why every service is an interface plus an `impl` class

```text
service/trust/TrustService.java               ← interface: the list of operations (the "menu")
service/impl/trust/TrustServiceImpl.java      ← @Service class: the actual code (the "kitchen")
```

| Reason | What it means in practice |
|---|---|
| Controllers depend on the **menu**, not the kitchen | `TrustController` has `private final TrustService trustService;`. It never mentions `TrustServiceImpl` |
| Easy mocking in tests | Controller tests mock `TrustService` (Chapter 11) |
| Proxies | Spring wraps the impl in a proxy for `@Transactional` / `@PreAuthorize`. Interfaces make that trivial |

Every interface here has **exactly one implementation**. So when you hit an interface in your IDE, jump straight to "Go to Implementation". Several helper services skip the interface entirely and are plain `@Service`/`@Component` classes: `WorkflowEngineAdaptor`, `VersionService`, `NotificationRouter`, `EmailDeliveryService`, `TrustDataRepairService`, `GovernanceEditGuard`.

**Classes in `service/` that are *not* Spring beans** (no annotation, so never created):
- `OverdueScheduler` and `DeclarationWorkflowServiceImpl` ("kept for reference only"): both dead code.
- `DeclarationApprovalPolicy`: a policy class that is not registered, so the engine never runs it (Chapter 10).
- Value objects such as `ActionContext`, `WorkflowActionRequest`, `TransitionRule`, `ClarificationRequest`, `EmailRequest`: data carriers, correctly not beans.

## 6.2 Transactions inside services

### The defaults you'll see 326 times
| Annotation | Meaning | Typical use |
|---|---|---|
| `@Transactional` | **REQUIRED** propagation: join the current transaction if there is one, otherwise start a new one. Commit at the end; **roll back on any `RuntimeException`** | All writes |
| `@Transactional(readOnly = true)` | Same, but Hibernate skips dirty checking and flushing | All reads (`getById`, `list*`) |
| *(none)* | Each repository call is its own tiny transaction | `TempleServiceImpl.search` (read-only, fine) |

### Where the transaction starts and ends (a picture)
```text
TrustController.create
   │
   ▼  ─────────── proxy of TrustServiceImpl: BEGIN (connection borrowed from Hikari) ───────────
   │  templeRepository.findById          SELECT temples …
   │  trustRepository.existsBy…          SELECT … trusts
   │  trustRepository.save               INSERT trusts         (IDENTITY → runs immediately)
   │  templeRepository.save              (queued; managed entity)
   │  workflowEngineAdaptor.ensureInitiated   ── joins SAME tx (REQUIRED) ──►  INSERT workflow_instances
   │  summaryService.scheduleRefresh     registers an "afterCommit" callback (nothing runs yet)
   │  return toResponse(saved)
   ▼  ─────────── flush: UPDATE temples SET trust_registered=1 … WHERE id=? AND version=?  → COMMIT ───────
   │  afterCommit → TempleSearchSummaryService.refresh(templeId)  (@Async, new thread, new tx)
   ▼
back to controller → ApiResponse → JSON
```

If *anything* inside throws a `RuntimeException` (a guard, a validation, an optimistic-lock conflict at flush), **everything in the box rolls back**: the trust row, the temple flag, the workflow instance. The `afterCommit` callback never fires.

### The special cases (all real)

| Where | Setting | What it does / why |
|---|---|---|
| `AuditServiceImpl` (3 methods) | `@Async` + `@Transactional(propagation = REQUIRES_NEW)` | Audit rows are written on **another thread in their own transaction**. They are **kept even if the business transaction rolls back**, and a failing audit never breaks the business action. Trade-off: the audit may say "CREATE" for something that was then rolled back |
| `AccessControlAuditServiceImpl` | `REQUIRES_NEW` | Same idea for policy changes |
| `NotificationServiceImpl`, `NotificationDispatchServiceImpl` | `REQUIRES_NEW` (one also `@Async`) | Notification rows are independent of the caller |
| `NotificationEventPublisherImpl` (DC module) | `REQUIRED` (explicit) | The **opposite** choice: the `notification_events` row is written *inside* the business transaction, so it exists **only if** the action commits (the Javadoc from Chapter 1) |
| `WorkflowEngineImpl.execute` | `isolation = READ_COMMITTED` | Explicit isolation for the state machine (Chapter 10) |
| `AcknowledgementServiceImpl.generate` | `isolation = SERIALIZABLE` | **Effectively ignored**, see below |
| `TempleSearchSummaryServiceImpl.scheduleRefresh` | `TransactionSynchronization.afterCommit` | Deferred work that runs **only after a successful commit** |

### What does *not* roll back
A DB rollback undoes only **database rows in that transaction**. In this codebase, these survive a rollback:
- **Files written to disk.** `GovernanceWorkflowServiceImpl.approveDeclaration` calls `generateAcknowledgementDocument(...)` (writes a PDF under `uploads/`) *before* the final save and the consistency check. If a later step throws, the PDF stays on disk with no DB row pointing to it. The same applies to `FileStorageService` uploads in photo, document and minutes flows.
- **`REQUIRES_NEW` / `@Async` work**: audit rows, some notifications.
- **In-memory state**: the Caffeine reset-request counter (`AuthServiceImpl`), SSE pushes already sent.
- **Emails already handed to SMTP.** The email *outbox* design (Chapter 10) exists to avoid exactly this.

And one case where rollback does something you *don't* want: the failed-login counter (Chapter 3, Defect #1).

### Why `SERIALIZABLE` on `AcknowledgementServiceImpl.generate` doesn't help
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public String generate(Long districtId, String financialYear) {
    ... List<String> existing = declarationRepository.findAcknowledgementNumbersByPrefix(prefix);
    long nextSeq = max(existing) + 1;                    // "read max, add one"
    return prefix + String.format("%06d", nextSeq);      // ACK-BLR-2025-26-000042
}
```
1. It's called from `approveDeclaration`, which already runs in a `@Transactional` (REQUIRED) transaction. A method that **joins** an existing transaction can't change its isolation level. Spring **silently ignores** the setting (unless `validateExistingTransaction` is enabled, which I found no sign of).
2. The approval transaction therefore runs at the default level (READ COMMITTED on TiDB). Two DCs in the same district approving **different** declarations at the same moment can both read max = 41 and both produce `...-000042`.
3. `asset_declarations.acknowledgement_number` has **no unique constraint** in `V1`, so nothing stops the duplicate.

For context, there's a second, sequence-table-based generator, `util/AcknowledgementNumberGenerator` (format `TRM/ACK/{FY}/…`, throws `AcknowledgementNumberConflictException`). It is injected into `DeclarationServiceImpl` but **never called**.

### The self-invocation trap in overloads (safe today, fragile)
`GovernanceWorkflowServiceImpl` has pairs like this:
```java
@Override @Transactional @PreAuthorize(RoleConstants.CAN_ACT_DC)
public WorkflowActionResponse approveDeclaration(Long id, WorkflowApproveRequest rq, Claims c, String idemKey) { … }

public WorkflowActionResponse approveDeclaration(Long id, WorkflowApproveRequest rq, Claims c) {   // no annotations
    return approveDeclaration(id, rq, c, null);          // this.approveDeclaration → bypasses the proxy
}
```
If anything called the **3-argument** version through the bean, the inner call would run with **no transaction and no `@PreAuthorize`**. Today `GovernanceWorkflowController` calls the annotated 4-argument versions, so it's fine. The interface also declares these as `default` methods, which delegate through the proxy, so calls via `GovernanceWorkflowService` stay safe too. The trap would only open if someone called the impl's own overload directly. The same pattern exists for `rejectDeclaration`, `requestClarification` and `flagPhysicalVerification`.

`TempleSearchSummaryServiceImpl` shows the *correct* way to call yourself:
```java
applicationContext.getBean(TempleSearchSummaryService.class).refresh(templeId);   // goes through the proxy → @Async + @Transactional apply
```
Its comment explains why it doesn't use `this.refresh(...)` (the proxy would be bypassed) and doesn't self-inject with `@Lazy` either.

## 6.3 The shared helper services: who does the side-effects

Most business services lean on the same toolbox:

| Helper | Job | Called from (examples) | Transaction behaviour |
|---|---|---|---|
| `OwnershipGuard` / `JurisdictionGuard` / `AccessGuard` | Data-scope checks (Chapter 3) | nearly every write | none (pure checks) |
| `AuditService` (`AuditServiceImpl`) | `audit_data_events`, `audit_auth_events`, `audit_export_events` | Temple, Trust, Declaration, Admin, Auth, Export… | `@Async` + `REQUIRES_NEW` |
| `GovernanceAuditService` | `governance_action_history` (who did what on which governed entity) | Declaration, Trust, DC services, `WorkflowEngineImpl` | joins caller |
| `WorkflowEngineAdaptor` | Bridge from old module services to `WorkflowEngine`: `ensureInitiated`, `adaptApprove`, `adaptSendBack`… | Trust, Declaration, Staging, Governance, DC compliance | joins caller |
| `WorkflowEngine` | The state machine itself (Chapter 10) | adaptor, `WorkflowController`, `OverdueWorkflowScheduler`, several services directly | `READ_COMMITTED` |
| `GovernanceStatusResolver` | Reads the "true" status from `WorkflowInstance` for responses | Declaration, Trust, Staging, DC profile | read |
| `GovernanceEditGuard` | "May the TA edit this governed entity now?" plus side-effects of editing after approval | Declaration, Trust | joins caller |
| `VersionService` / `SnapshotService` | JSON snapshots into `entity_versions` / `asset_declaration_versions` | Governance approve, Staging | joins caller |
| `TempleSearchSummaryService` | Keep `temple_search_summary` fresh | Temple, Trust, Governance, Admin, Registration, DC | after commit, `@Async` |
| `NotificationEventPublisher` (**two different types**) | `service.notification.NotificationEventPublisher` publishes Spring events; `service.dc.NotificationEventPublisher` inserts `notification_events` rows synchronously | Trust uses **both**; Declaration, TA dashboard, Governance use one | see 6.2 |
| `NotificationHelper` (deprecated shim) | Old notification entry point, now forwards to events | `DcTempleVerificationServiceImpl`, `TempleProfileWorkflowServiceImpl`, `DcComplianceServiceImpl`, `TrustServiceImpl` | — |
| `FileStorageService` → `LocalFileStorageServiceImpl` | Save/read files under `app.storage.base-dir` | Temple photos, Documents, Notices, Governance (ack PDF), DC profile | **not transactional** (disk) |
| `TrustValidationService`, `FinancialYearValidationService` | Pure validation rules | Trust, repair job, `@ValidFinancialYear` | none |
| `PaginationUtil.clampSize` | Page size: default 10, **max 100** | every paginated service | none |

**Answer to the question from Chapter 5:** `TempleServiceImpl.search` calls `paginationUtil.clampSize(filter.getSize())`, so `?size=100000` becomes 100 even though the DTO's `@Max(100)` isn't validated. The `sort` parameter, however, is **ignored**: the code always uses `Sort.by(ASC, "name")`. A negative `page` reaches `PageRequest.of(...)`, which throws `IllegalArgumentException`, most likely a 500.

## 6.4 A guided tour of the heavy services

### `TempleServiceImpl` (548 lines): temple read/write, photos
- **`search`** reads from the **read-model** `temple_search_summary` (not `temples`). It uses a JPA `Specification` built by `buildSpec(filter, scopedDistrictId)`. `jurisdictionGuard.enforceDistrictId` forces DC/DC_STAFF to their own district even on this public endpoint. Each result's `photoUrl` is rewritten to the public `/profile-photo/serve` URL.
- **`create`** has `@PreAuthorize(CAN_SUBMIT)` in the service, but the controller says `ADMIN_ONLY`. The stricter controller wins today.
  - It runs `existsByRegistrationNumber` → `DuplicateResourceException` (409).
  - Then it maps via `templeMapper.fromCreateRequest` (MapStruct) and saves.
- **`getById`** follows the recipe: load → `assertSameDistrict` → `assertOwnsTemple` → map + `enrichTempleResponse`.
- **Photos** (`uploadTemplePhotos`, `deleteTemplePhoto`, `serve*`) use `TemplePhotoRepository` + `FileStorageService` (and, since V103, bytes in the DB). Only `isAuthenticated()` + the TA ownership check apply (Chapter 5 finding).

### `TempleProfileStagingServiceImpl` (480 lines): TA edits the temple profile
- The TA never edits `temples` directly. Edits go into a **staging row** (`temple_profile_staging`, status `DRAFT`).
- `createOrUpdateDraft` → `ownershipGuard` + **`accessGuard`** (one of only 4 users of it) → save the draft.
- `submitForReview` → `WorkflowEngine` / `WorkflowEngineAdaptor` (`TEMPLE_PROFILE` instance) + `VersionService` snapshot + `ClarificationEngine`.
- The DC approves in the DC module (`DcProfileController` → `TempleProfileWorkflowServiceImpl`), which copies staging → `temples` / `temple_profile_current` / `temple_profile_history`.

### `TrustServiceImpl` (631 lines): trust, board members, meetings, financials
- `create` / `update` / `getById` follow the recipe shown in 6.0.
- Board-member Aadhaar handling uses `HmacUtil` for `aadhaar_hash` (duplicate detection), the AES converter for `aadhaar_encrypted`, and `aadhaar_last4` for the mask.
- It has the widest fan-out of helpers (≈15): **both** `NotificationEventPublisher` types, `NotificationHelper`, `NotificationRecipientResolver`, `WorkflowEngineAdaptor`, `GovernanceStatusResolver`, `GovernanceEditGuard`, `DocumentService` (meeting minutes), and more. This is the module most mid-migration between the old and new notification/workflow styles.

### `DeclarationServiceImpl` (892 lines, 14 repositories): the asset declaration itself
**`create(templeId, rq)`**, read directly from the code:
```text
ownershipGuard.assertOwnsTemple(templeId)
findTopByTempleIdAndFinancialYearOrderByVersionNumberDesc(templeId, FY)
   └─ if latest is DRAFT/SUBMITTED/UNDER_REVIEW/CLARIFICATION_REQUIRED/SITE_VISIT_SCHEDULED/APPROVED
        → DeclarationAlreadyExistsException (409)       ← "one active declaration per temple per FY"
   └─ else (none, or REJECTED …) → versionNumber = latest + 1 (or 1)
save(AssetDeclaration{DRAFT, templeId, districtId = temple.getDistrictId(), FY, …})   INSERT
replaceAssetItems(id, rq)      → 9 × deleteByDeclarationId + INSERT each item        (Chapter 4 §4.6)
applySummaryFields(saved, rq)  → totals (gold_grams, vehicles_count, …) onto the parent row
save(saved)                    → UPDATE asset_declarations
workflowEngineAdaptor.ensureInitiated(DECLARATION, …)          INSERT workflow_instances
auditService.logDataEvent(...)            (@Async, REQUIRES_NEW)
governanceAuditService.logAction(...)     (same tx)
return buildCompleteResponse(saved)       (DeclarationAssetMapper)
```
The "one active per FY" rule is a **check-then-insert** with no DB unique constraint behind it. Two simultaneous creates could both pass the check. `DeclarationUniqueSubmissionPolicy` (a `WorkflowPolicy`) and `ActiveDeclarationUniquenessPropertyTest` guard the *submit* step, which we'll verify in Chapter 10.

- **`update`** is allowed only in `DRAFT` or `REJECTED`, otherwise `DeclarationImmutableException` (409). It replaces all items again.
- **`submit` deliberately throws `UnsupportedOperationException`**: *"Use POST /api/v1/governance/declarations/{id}/submit … to prevent dual-path workflow mutation."* This is the clearest sign in the code that **`GovernanceWorkflowServiceImpl` owns declaration state changes**.
- `respondToClarification` writes to the older `declaration_clarifications` table. The newer `ClarificationEngine` uses `clarification_threads`, so declarations currently have **two** clarification stores.

### `GovernanceWorkflowServiceImpl` (941 lines, the largest): trust + declaration workflow
Every public method is `@Transactional` + `@PreAuthorize` (`CAN_SUBMIT` for submit/withdraw, `CAN_ACT_DC` for DC actions). Here is `approveDeclaration`, the most important one:

```text
loadDeclaration → loadTempleWithGeo → jurisdictionGuard.assertDistrictScope      (404 if other district)
if physicalVerificationStatus == VERIFICATION_FAILED → IllegalStatusTransitionException (409)
if already APPROVED with an ack number → return the SAME ack (idempotent retry)    ← nice touch
workflowEngineAdaptor.adaptApprove(DECLARATION, id, …, idempotencyKey)             ← state machine FIRST
ackNumber = acknowledgementService.generate(districtId, FY)                        ← race, see 6.2
generateAcknowledgementDocument(declaration, ackNumber)                            ← PDF on disk (not rolled back)
declaration.setStatus(APPROVED), setAcknowledgementNumber, reviewedBy/At, comment
declarationRepository.save(declaration)
assertEntityStatusConsistency(DECLARATION, id, "APPROVED")                         ← "dual-write" check
versionService.snapshot(DECLARATION, id, …)                                        ← immutable JSON copy
summaryService.scheduleRefresh(templeId)                                           ← after commit
return WorkflowActionResponse{ newStatus: APPROVED, acknowledgementNumber }
```

The key idea is the **"dual write"**. The status lives in **two places**: `workflow_instances.status` (written by the engine) and `asset_declarations.status` (written here). `assertEntityStatusConsistency` throws if they disagree, which rolls back everything, including the engine's transition, since it's the same transaction. The comment `adaptApprove() must happen BEFORE we mutate the entity status` tells you the engine is the authority.

### `DcTempleProfileServiceImpl` (789 lines, **24 repositories**): the DC's full temple view
This is the aggregator behind `GET /api/v1/dc/temples/{id}` (`TempleFullProfileResponse`). It pulls temple, trust, board members, financials, declarations and all 9 item tables, employees, contractors, documents, workflow status and history into one response, applying `TempleVisibilityPolicy` (what governance metadata this caller may see) and `JurisdictionGuard`. It has 24 repositories because it's a **read-side composer**, not because it owns 24 kinds of data.

### `AdminServiceImpl` (361 lines): user management
- `createUser` builds a `User` with a `TemporaryPasswordGenerator` password, BCrypt, and `mustChangePassword = true`. It emails credentials via `EmailService`.
- It optionally creates or links a temple, then `TempleSearchSummaryService`.
- It writes **plaintext** `aadhaarNumber` (Chapter 4 finding).
- `deactivate` / `activate` toggle `is_active`. As Chapter 3 showed, login ignores that flag.

## 6.5 Who calls whom (the service dependency graph)

```text
                         ┌──────────────── Controllers ────────────────┐
                         ▼                                              ▼
   TempleService   TempleProfileStagingService   TrustService   DeclarationService   GovernanceWorkflowService   Dc*Service …
        │                 │   │   │                  │   │           │   │   │                │   │   │   │
        │                 │   │   └── ClarificationEngine ◄───────────────────────────────────┘   │   │   │
        │                 │   └────── VersionService ◄─────────────────────────────────────────────┘   │   │
        │                 └────────── WorkflowEngineAdaptor ──► WorkflowEngine ◄───────────────────────┘   │
        │                                  ▲        ▲              │   │  (policies, TransitionRuleRegistry)│
        │            TrustService ─────────┘        │              │   └──► GovernanceAuditService          │
        │            DeclarationService ────────────┘              └──────► ApplicationEventPublisher ──► NotificationRouter,
        │                                                                          TimelineListener (after commit)
        ├──► TempleSearchSummaryService ◄── Trust, Governance, Staging, Admin, Registration, DcTempleVerification …
        ├──► AuditService (@Async)      ◄── almost everyone
        ├──► FileStorageService          ◄── Temple, Document, Notice, Governance, DcTempleProfile, DeclarationService
        └──► guards (Ownership / Jurisdiction / Access)
```

**Are there circular dependencies?** I checked every constructor-injected field in `service/**`. **No cycles**:
- `ClarificationEngineImpl → WorkflowEngine`, but `WorkflowEngineImpl` does **not** depend on `ClarificationEngine`.
- `WorkflowEnvelopeAssembler` depends on both, and nothing depends back on it.

This matters because Spring Boot 3 **refuses to start** on constructor cycles by default (`spring.main.allow-circular-references=false`), so adding one would fail loudly at boot (Chapter 2 §2.1 table). `TempleSearchSummaryServiceImpl` uses `ApplicationContext.getBean(...)` specifically to avoid introducing a self-cycle.

## 6.6 The five questions, for the two central services

```text
Who calls me?   DeclarationController (create/update/get/list/diff/versions/ack) · TaDashboardServiceImpl
      ↓
[DeclarationServiceImpl]
      ↓
Who do I call?  14 repositories (declaration + 9 item tables + clarifications + temple …)
                · Ownership/Jurisdiction guards · WorkflowEngineAdaptor/WorkflowEngine
                · SnapshotService · DeclarationAssetMapper · AuditService · GovernanceAuditService
                · GovernanceEditGuard · GovernanceStatusResolver · FileStorageService

INPUT   templeId / id · CreateDeclarationRequest (header + 9 item lists) · page/size
  ↓     ownership · one-active-per-FY · DRAFT/REJECTED-only edits · replace items · totals · workflow init · audit
OUTPUT  CompleteDeclarationResponse · PaginatedResponse<DeclarationResponse> · Resource (ack PDF)
        — never changes status (that's GovernanceWorkflowService)
```
```text
Who calls me?   GovernanceWorkflowController (v1) · DeclarationController.submit (deprecated alias)
      ↓
[GovernanceWorkflowServiceImpl]
      ↓
Who do I call?  WorkflowEngineAdaptor/WorkflowEngine · AcknowledgementService · VersionService
                · ClarificationEngine · TempleSearchSummaryService · Notification publisher/resolver
                · GovernanceAuditService · AuditService · guards · FileStorageService · 7 repositories

INPUT   entity id · approve/reject/send-back/clarify/site-visit DTOs · Claims · Idempotency-Key
  ↓     scope check · business blocks (failed site visit) · engine transition FIRST · entity dual-write
        · ack number + PDF · consistency assert · snapshot · summary refresh
OUTPUT  WorkflowActionResponse{declarationId, newStatus, acknowledgementNumber, message} · void
```

## 6.7 Findings from this chapter

| Severity | Finding | Where |
|---|---|---|
| 🟠 | **Duplicate acknowledgement numbers possible.** "max + 1" generation; `SERIALIZABLE` is ignored because the method joins the approval transaction; no unique constraint on `acknowledgement_number` | `AcknowledgementServiceImpl.generate` ← `GovernanceWorkflowServiceImpl:314` |
| 🟡 | Acknowledgement PDF written to disk before the final checks. A rollback leaves an orphan file | `approveDeclaration` |
| 🟡 | "One active declaration per FY" is check-then-insert with no DB constraint (the create path) | `DeclarationServiceImpl.create` |
| 🟡 | `accessGuard.assertCanEdit()` missing from TA write paths such as `TrustServiceImpl.create`/`update` and `DeclarationServiceImpl.create`/`update` (only 4 call sites) | multiple |
| 🟡 | Two clarification stores for declarations (`declaration_clarifications` vs `clarification_threads`); two `NotificationEventPublisher` types; deprecated `NotificationHelper` still used by 4 services | — |
| ⚪ | Latent self-invocation trap in un-annotated overloads (safe today) | `GovernanceWorkflowServiceImpl` |
| ⚪ | `search` ignores `sort`; negative `page` probably gives a 500. `AcknowledgementNumberGenerator` injected but unused. `TempleServiceImpl.create` service rule (`CAN_SUBMIT`) looser than the controller's (`ADMIN_ONLY`) | — |

---

**Next: Chapter 7 — Repositories.** It covers:
- the three query styles:
  - derived method names, parsed word by word
  - JPQL `@Query`: 71 of them, including 3 native
  - JPA `Specification`s: the dynamic temple search via `TempleSearchSummary`, and `NoticeSpecification`
- `Page`/`Pageable` end to end
- `@Modifying` bulk updates
- `@EntityGraph`
- the repositories the workflow engine and dashboards rely on, with the SQL each one produces

Say **"continue"** when ready.

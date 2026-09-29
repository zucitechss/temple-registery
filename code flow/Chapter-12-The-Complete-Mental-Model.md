# CHAPTER 12 — The Complete Mental Model

This final chapter adds no new code reading. It pulls Chapters 0–11 into one picture you can keep in your head, a walk-through you could give in an interview, the cheat sheet, a reading order, and one ranked list of every finding.

## 12.1 The backend as a government office

| Office role | In this backend | Real classes |
|---|---|---|
| **Gate & ID check** at the building entrance | Servlet filters | `JwtAuthenticationFilter` (reads the badge: the JWT), `RequestIdFilter` (stamps a visitor number), `SecurityConfig` rules (which corridors are public) |
| **Visitor badge** | the `Claims` record in the `SecurityContext` | `ScopeHelper.Claims(userId, role, districtId, templeId, username, accessType)` |
| **Reception desks**, one per department | Controllers | `TempleController`, `TrustController`, `GovernanceWorkflowController`, the 10 `Dc*Controller`s, … |
| **Forms** handed across the desk | Request / response DTOs | `CreateDeclarationRequest`, `TrustResponse`, `ApiResponse<T>` (the envelope every reply goes in) |
| **Department managers** who decide | Services | `DeclarationServiceImpl`, `TrustServiceImpl`, `TempleServiceImpl`, … |
| **Senior officer** who signs off on approvals | Orchestrators | `GovernanceWorkflowServiceImpl`, `TempleProfileWorkflowServiceImpl` |
| **The rulebook** of which stamp can follow which | State machine | `TransitionRuleRegistry` + `WorkflowPolicy` beans |
| **The official register** of every stamp ever given | Workflow engine | `WorkflowEngineImpl` → `workflow_instances`, `workflow_transitions` |
| **Jurisdiction clerks** ("is this your district / your temple?") | Guards | `JurisdictionGuard`, `OwnershipGuard`, `AccessGuard` |
| **Filing clerks** | Repositories | `DeclarationRepository`, `WorkflowInstanceRepository`, … |
| **Filing cabinets** | Entities + tables | `AssetDeclaration` ↔ `asset_declarations`, … (soft-deleted, never shredded) |
| **The safe** for ID numbers | Encryption converter | `AesEncryptionConverter` (+ `HmacUtil` for look-ups, `aadhaar_last4` for display) |
| **Translators** between form wording and file wording | Mappers | MapStruct `GeoMapper`/`TempleMapper`, hand-written `DeclarationAssetMapper`, `toResponse` methods |
| **Complaint desk** | Exception handling | `GlobalExceptionHandler` |
| **Post room**, with outgoing trays and couriers | Notifications | `notification_outbox` → `NotificationRouter` → in-app / `email_outbox` → `EmailDeliveryService` / SSE |
| **Night shift** | Schedulers & async workers | `OverdueWorkflowScheduler`, `EmailDeliveryService`, `NotificationRouter` polling, `AuditServiceImpl` (`@Async`) |
| **Archive & photocopier** | Snapshots / history | `VersionService` (`entity_versions`), `SnapshotService` (`asset_declaration_versions`), `temple_profile_history` |
| **The quick-lookup index card box** | Read models | `temple_search_summary` (rebuilt after each change) |

The office's single most important rule: **the senior officer (orchestrator) fills in the file *and* the register (engine) in the same sitting (transaction)**. If either write fails, both are torn up (rollback).

## 12.2 The big diagrams, with real class names

### Every request
```text
                              Browser (React @ localhost:5173 / Vercel)
                                │  Authorization: Bearer <JWT>  (or access_token cookie; ?token= only for /stream)
                                ▼
        ┌──────────────────────────── Tomcat :8080 ─────────────────────────────┐
        │  Spring Security FilterChainProxy (order -100)                        │
        │     CorsFilter  →  JwtAuthenticationFilter ─► ScopeHelper.parseFull   │
        │        (password-change gate → 403)      SecurityContextHolder ◄──┘   │
        │     AuthorizationFilter: PUBLIC_PATHS / GET /api/v1/temples permitAll │
        │                          else authenticated() or 401 (empty body)     │
        │  RequestIdFilter (@Order 1) → MDC requestId                           │
        │  DispatcherServlet                                                    │
        └───────────────┬───────────────────────────────────────────────────────┘
                        ▼
              XController  (class/method @PreAuthorize; @Valid DTO → 400)
                        ▼
      ┌──── proxy: @PreAuthorize(RoleConstants.X) → @Transactional BEGIN ────┐
      │ XServiceImpl                                                          │
      │   load → Ownership/Jurisdiction/AccessGuard → rules → mutate          │
      │   → [WorkflowEngineAdaptor → WorkflowEngineImpl] → side-effects       │
      │   → toResponse / Mapper                                               │
      └───────────── COMMIT (or ROLLBACK on RuntimeException) ────────────────┘
                        ▼                     ▲
              XRepository ─► Hibernate ─► Hikari (max 8) ─► TiDB Cloud
                        ▼
      ResponseEntity<ApiResponse<DTO>> ─► Jackson ─► JSON  { success, message, data, timestamp, requestId }
      (any exception escaping → GlobalExceptionHandler → ApiResponse.error + status)
```

### Login
```text
POST /api/v1/auth/login {username,password}
  → AuthController.login → AuthServiceImpl.login  @Transactional
       UserRepository.findByUsername ─ none → 404 INVALID_CREDENTIALS
       lockedUntil > now → 423
       BCrypt(12).matches ─ wrong → failedLoginCount++ → save → throw 404   (↩ rolled back!)
       mfaType ≠ NONE → JwtServiceImpl.generateTempToken (5 min) → {mfaRequired, tempToken}
                        → POST /mfa-verify → verifyMfa (⚠ code never checked) → issueTokenPair
       issueTokenPair: RS256 access JWT (2 h; userId, role, districtId, templeId, accessType, mustChangePassword)
                     + random refresh (7 d) → SHA-256 → refresh_tokens
  ← Set-Cookie access_token (Path=/api/v1), refresh_token (Path=/api/v1/auth/refresh)  + tokens in JSON body
```

### Governed approval (declaration)
```text
POST /api/v1/governance/declarations/{id}/approve
  → GovernanceWorkflowController → GovernanceWorkflowServiceImpl.approveDeclaration  @Transactional @PreAuthorize(CAN_ACT_DC)
       loadDeclaration · loadTempleWithGeo · JurisdictionGuard.assertDistrictScope (404)
       failed site visit → 409 · already approved → same ack
       WorkflowEngineAdaptor.adaptApprove ─► WorkflowEngineImpl.execute
            idempotency → rule → role → district → ownership → comment → policies → version
            → workflow_instances=APPROVED · workflow_transitions · governance_action_history
            → notification_outbox · publishEvent
       AcknowledgementServiceImpl.generate → ACK-<DIST>-<FY>-<seq>   · PDF → uploads/
       asset_declarations.status=APPROVED (+ack) · assertEntityStatusConsistency
       VersionService.snapshot → entity_versions · TempleSearchSummaryService.scheduleRefresh
  COMMIT
       ├─ NotificationRouter (@Async after-commit)  ┐→ route → rules → recipients → dispatch
       ├─ NotificationRouter.dispatchPending (5 s)  ┘      in_app_notifications · SSE · email_outbox → SMTP (10 s)
       ├─ GovernanceDomainEventTimelineListener → temple_timeline_events
       └─ TempleSearchSummaryServiceImpl.refresh (@Async) → temple_search_summary
```

## 12.3 The end-to-end walk-through (interview style)

> **"Suppose the frontend sends this request…"**
> ```http
> POST /api/v1/governance/declarations/7/approve
> Authorization: Bearer eyJhbGciOiJSUzI1NiJ9…
> Idempotency-Key: 3f1c…
> Content-Type: application/json
>
> { "remarks": "All assets verified against records." }
> ```

**1. Security.** Tomcat hands the request to Spring Security.
- `JwtAuthenticationFilter` takes the Bearer token and calls `ScopeHelper.parseFull`, which verifies the RS256 signature with `keys/jwt-public.pem` and checks expiry.
- It reads the claims: `userId=12, role=DISTRICT_COLLECTOR, districtId=3`. `mustChangePassword` is false, so there's no gate.
- It stores a `UsernamePasswordAuthenticationToken(claims, null, [ROLE_DISTRICT_COLLECTOR])` in the `SecurityContextHolder`, a per-thread box any later code can read.
- `/api/v1/governance/**` isn't public, so `authenticated()` must pass, and it does.

**2. Controller.** `DispatcherServlet` maps the URL to `GovernanceWorkflowController.approveDeclaration`.
- Its `@PreAuthorize(CAN_ACT_DC)`, i.e. `hasAnyRole('SUPER_ADMIN','DISTRICT_COLLECTOR')`, passes.
- Jackson builds a `WorkflowApproveRequest` from the body. `@Valid` checks it (the body is optional).
- `@RequestHeader("Idempotency-Key")` is read.
- It calls `governanceWorkflowService.approveDeclaration(7, request, currentClaims(), key)`, the annotated 4-argument overload.

**3. Transaction.** The service proxy checks `@PreAuthorize` again, then `@Transactional` borrows a Hikari connection and begins a transaction.

**4. Service rules.**
- `loadDeclaration(7)` runs `SELECT … FROM asset_declarations WHERE id=7 AND is_deleted=false`. If it's missing, `EntityNotFoundException` → 404.
- `loadTempleWithGeo` uses `@EntityGraph` on hobli → taluk → district, so it's one joined `SELECT`.
- `JurisdictionGuard.assertDistrictScope`: the temple's district must be 3, or the client gets a 404 with random delay.
- `physicalVerificationStatus == VERIFICATION_FAILED` → 409.
- Already `APPROVED` with an ack → return that ack (a safe retry).

**5. Workflow engine.** `WorkflowEngineAdaptor.adaptApprove` builds an `ActionContext(role="DC", district=3)` and calls `WorkflowEngineImpl.execute`, which **joins the same transaction**:
- idempotency lookup on `workflow_idempotency_records`
- load `workflow_instances` (entity DECLARATION, id 7)
- `TransitionRuleRegistry.find("DECLARATION", UNDER_REVIEW, APPROVE)` → `APPROVED`
- role DC ✓, district ✓
- `SiteVisitBlocksApprovalPolicy` ✓
- set the status, save (Hibernate adds `WHERE lock_version = ?`)
- insert a `workflow_transitions` row, a `governance_action_history` row, and a `notification_outbox` row (PENDING)
- save the idempotency record, then publish `GovernanceDomainEvent`, which listeners receive **after commit**

**6. Back in the service.**
- `AcknowledgementServiceImpl.generate(3, "2025-26")` reads existing numbers with that prefix and returns max + 1, e.g. `ACK-BLR-2025-26-000042`.
- An iText PDF is written to `uploads/`.
- The entity gets `status=APPROVED`, the ack number, `reviewedBy=12`, `reviewedAt`, and `reviewComment`.
- `declarationRepository.save`.
- `assertEntityStatusConsistency` confirms engine status equals entity status.
- `VersionService.snapshot` inserts a JSON copy into `entity_versions`.
- `scheduleRefresh(templeId)` registers an after-commit callback.
- It returns `WorkflowActionResponse{declarationId:7, newStatus:"APPROVED", acknowledgementNumber:"ACK-BLR-2025-26-000042"}`.

**7. Hibernate at commit.** It flushes the pending `UPDATE`s. If another DC changed the row meanwhile, the version check fails → `OptimisticLockingFailureException` → **rollback** → 409. Otherwise it **commits**.

**8. Response.** The controller wraps the result in `ApiResponse.success("Declaration approved.", result)`. Jackson writes:
```json
{ "success": true, "message": "Declaration approved.",
  "data": { "declarationId": 7, "newStatus": "APPROVED", "acknowledgementNumber": "ACK-BLR-2025-26-000042", "message": "…" },
  "timestamp": "2026-09-28T10:15:02Z", "requestId": "b7e2…" }
```
with HTTP 200.

**9. After the response.**
- `NotificationRouter` looks up `notification_rules` for this event, resolves the temple authority, writes an in-app notification, pushes SSE, and enqueues an email.
- The 5-second outbox poller does the same again (the duplicate issue).
- The timeline listener and the search-summary refresh run.
- The TA later downloads the PDF from `GET /api/v1/declarations/7/acknowledgement/download`.

## 12.4 BACKEND CHEAT SHEET

### Architecture
```text
Frontend ─► Filters (JwtAuthenticationFilter, RequestIdFilter) ─► SecurityConfig URL rules
        ─► Controller (@PreAuthorize, @Valid) ─► Service proxy (@PreAuthorize, @Transactional)
        ─► Service (guards, rules) ─► [WorkflowEngineAdaptor ─► WorkflowEngine] ─► Repository
        ─► Hibernate/JPA ─► Hikari ─► TiDB (MySQL protocol)
Errors  ─► GlobalExceptionHandler ─► ApiResponse.error
After commit ─► events ─► NotificationRouter / timeline / search summary
```

### Stack
Spring Boot 3.4.4 · Java 21 · JPA/Hibernate · TiDB Cloud · Flyway (+ `ddl-auto: update`) · Spring Security + JJWT RS256 · MapStruct + Lombok · Thymeleaf mail · Caffeine · iText / OpenCSV · springdoc · JUnit 5 / Mockito / jqwik / Testcontainers

### Important packages (`com.templeregistry.*`)
| Package | Holds |
|---|---|
| `config` | Security, CORS, Async pools, Caffeine, Flyway strategy, JPA auditing, OpenAPI, TOTP |
| `security` | JWT filter, `ScopeHelper`/`Claims`, guards, `RoleConstants`, DACVM aspect |
| `controller.<module>` | 39 REST controllers |
| `dto.request.<module>` / `dto.response.<module>` | 189 DTOs |
| `service.<module>` / `service.impl.<module>` | interfaces / implementations |
| `service.workflow` | `WorkflowEngine(Impl)`, `TransitionRuleRegistry`, `WorkflowPolicy` + `policy/`, `WorkflowEngineAdaptor`, `VersionService`, schedulers |
| `service.governance` | `GovernanceWorkflowService`, `GovernanceStatusResolver`, `GovernanceEditGuard`, `TempleVisibilityPolicy` |
| `service.notification` | `NotificationRouter`, `NotificationDispatchServiceImpl`, `EmailDeliveryService`, `SseNotificationService` |
| `service.clarification` | `ClarificationEngine(Impl)` |
| `repository.<module>` | 62 Spring Data interfaces |
| `entity.<module>` | 67 entities + enums + converters; `entity.base.BaseEntity` |
| `mapper.<module>` | MapStruct + `DeclarationAssetMapper` |
| `event` | `GovernanceDomainEvent` (current) + legacy module events |
| `exception` | 19 custom exceptions + `GlobalExceptionHandler` |
| `util` | AES converter, HMAC, ack generators, PDF, pagination, request-id filter |

### Important classes
| Class | One-line responsibility |
|---|---|
| `TempleRegistryApplication` | entry point; `@EnableAsync`, `@EnableScheduling` |
| `SecurityConfig` | public paths, stateless, 401 entry point, BCrypt(12), JWT filter placement |
| `JwtAuthenticationFilter` / `ScopeHelper` | token → `Claims` → `SecurityContext`; password-change gate |
| `AuthServiceImpl` / `JwtServiceImpl` | login, MFA branch, token pair, refresh rotation, reset |
| `RoleConstants` | role names + `@PreAuthorize` expressions |
| `JurisdictionGuard` / `OwnershipGuard` / `AccessGuard` | district / temple / VIEW-only checks |
| `GovernanceWorkflowServiceImpl` | **canonical** trust + declaration workflow orchestration |
| `WorkflowEngineImpl` + `TransitionRuleRegistry` | the state machine and its audit/outbox |
| `WorkflowEngineAdaptor` | module services → engine bridge (`ensureInitiated`, `adapt*`) |
| `DeclarationServiceImpl` | declaration data (header + 9 item tables), never status changes except clarification response |
| `TrustServiceImpl` | trust, board members (encrypted Aadhaar), meetings, financials |
| `TempleServiceImpl` / `TempleProfileStagingServiceImpl` | temple read/search/photos / TA profile drafts |
| `TempleProfileWorkflowServiceImpl` | DC approval of profile staging → current/history/temple |
| `DcTempleProfileServiceImpl` | DC's full temple view (24 repositories) |
| `TempleSearchSummaryServiceImpl` | keeps `temple_search_summary` fresh after commit |
| `NotificationRouter` / `EmailDeliveryService` | event → rules → in-app/SSE/email; email outbox + retries |
| `GlobalExceptionHandler` | exception → HTTP status + `ApiResponse` |
| `AesEncryptionConverter` / `HmacUtil` | PII at rest / Aadhaar duplicate lookup |

### Important tables
| Table | Purpose |
|---|---|
| `users`, `refresh_tokens` | accounts (role, district, temple, access type, lock/reset fields) / hashed refresh tokens |
| `states` → `cities` → `districts` → `taluks` → `hoblis` | geo hierarchy (real FKs + `@ManyToOne`) |
| `temples`, `temple_photos` | core registry record |
| `temple_profile_staging` / `_current` / `_history` | TA edits → approved profile → past versions |
| `temple_search_summary` | denormalized search read-model |
| `trusts`, `board_members`, `board_meetings`, `trust_financials` | trust module (plain `temple_id` / `trust_id` links) |
| `asset_declarations` + 9 × `decl_immov_*` / `decl_mov_*` | declaration header + item rows |
| `workflow_instances`, `workflow_transitions`, `workflow_idempotency_records` | governance state machine + audit + idempotency |
| `clarification_threads/messages/attachments`, `declaration_clarifications` | new / legacy clarification stores |
| `entity_versions`, `asset_declaration_versions` | JSON snapshots (two systems) |
| `notification_rules`, `notification_outbox`, `in_app_notifications`, `email_outbox`, `email_delivery_logs`, `user_notification_preferences` | notification pipeline |
| `audit_*_events`, `governance_action_history`, `temple_timeline_events` | audit trails |
| `access_control_policies`, `…_field_masks`, `…_audit_log` | DACVM (not enforced) |
| `flyway_schema_history` | migration bookkeeping |

### Important relationships
```text
JPA @ManyToOne:  City→State · District→City · Taluk→District · Hobli→Taluk · Temple→Hobli (read-only, + hobli_id)
                 TemplePhoto→Temple · RefreshToken→User
                 WorkflowTransition / EntityVersion / ClarificationThread → WorkflowInstance
Bidirectional + cascade ALL + orphanRemoval:  ClarificationThread⇄Message⇄Attachment · Notice⇄NoticeAttachment
Plain Long FKs (DB FK exists):  Trust.templeId · BoardMember/Meeting/Financial.trustId · AssetDeclaration.templeId/districtId
                                Decl*.declarationId (ON DELETE CASCADE, never fires) · Employee/Contractor.templeId · User.templeId/districtId
Polymorphic (no FK):  WorkflowInstance(entity_type, entity_id) UNIQUE · Document(owner_type, owner_id)
```

### Important APIs
| Flow | Endpoint | → Service |
|---|---|---|
| Login | `POST /api/v1/auth/login` (+ `/mfa-verify`, `/refresh`, `/logout`) | `AuthServiceImpl` |
| Forgot password | `POST /api/v1/auth/password-reset-req` → `/password-reset` | `AuthServiceImpl` |
| Change password | `PATCH /api/v1/profile/password` | `UserProfileServiceImpl` |
| Temple search (public) | `GET /api/v1/temples` | `TempleServiceImpl.search` (Specification on summary) |
| Profile draft / submit | `POST /api/v1/temples/{id}/profile/staging` · `/profile/submit` | `TempleProfileStagingServiceImpl` |
| Profile approve | `POST /api/v1/dc/profiles/{stagingId}/approve` | `TempleProfileWorkflowServiceImpl` |
| Trust CRUD | `/api/v1/temples/{id}/trusts`, `/api/v1/trusts/**` | `TrustServiceImpl` |
| Trust workflow | `/api/v1/governance/trusts/{id}/submit|approve|send-back|reject` | `GovernanceWorkflowServiceImpl` |
| Declaration CRUD | `/api/v1/temples/{id}/declarations`, `PUT /api/v1/declarations/{id}` | `DeclarationServiceImpl` |
| Declaration workflow | `/api/v1/governance/declarations/{id}/submit|under-review|clarify|schedule-site-visit|…|approve|reject` | `GovernanceWorkflowServiceImpl` |
| Clarification response | `POST /api/v1/declarations/{id}/clarification-respond` | `DeclarationServiceImpl` |
| DC dashboard | `/api/v1/dc/dashboard`, `/api/v2/workflow/dashboard`, `/count/pending` | `DcDashboardServiceImpl`, `WorkflowEngine` |
| Notifications | `/api/v1/notifications/**`, SSE `/api/v1/notifications/stream?token=` | `NotificationServiceImpl`, `SseNotificationService` |
| Export | `POST /api/v1/dc/export/temples|declarations` (200 / 202) → `GET /{jobId}/download` | `DcExportServiceImpl` / `AsyncExportBean` |

### Authentication flow (one line each)
1. Login → BCrypt check → access JWT (2 h) + refresh (7 d, SHA-256 stored) → cookies + JSON.
2. Each request → `JwtAuthenticationFilter` → `ScopeHelper` verifies → `Claims` in `SecurityContext` (no DB lookup).
3. URL rule → `@PreAuthorize(RoleConstants…)` → guards (district / temple / VIEW) → service.
4. Refresh → rotate (old revoked) → new pair with fresh claims. Logout → revoke refresh only.

### Common patterns actually used
- Controller → service interface → `impl` → repository, with constructor injection (`@RequiredArgsConstructor`)
- `ApiResponse<T>` envelope; `PaginatedResponse` from `Page<T>.map(...)`
- `@PreAuthorize` constants on **service** methods as the source of truth
- Load → guard → rule → mutate → side-effects → map (the 7-step service recipe)
- `BaseEntity`: audit columns, soft delete (`@SQLRestriction` + `@SQLDelete`), `@Version` on contested entities
- Plain-id foreign keys instead of JPA associations, for most modules
- Orchestrator + state machine + **dual write**, checked by `assertEntityStatusConsistency`
- Transactional outbox (`notification_outbox`, `email_outbox`) + after-commit listeners
- Idempotency keys on workflow actions and exports
- `afterCommit` synchronization + `@Async` on a separate proxy (`TempleSearchSummaryServiceImpl`, `AsyncExportBean`)
- Read models (`temple_search_summary`) and envelopes (`WorkflowEnvelope<T>`)
- Anti-enumeration 404 + timing jitter for out-of-district access

### Where to start reading (in this order)
1. [TempleRegistryApplication.java](../backend/src/main/java/com/templeregistry/TempleRegistryApplication.java) → [application.yml](../backend/src/main/resources/application.yml) → [application-dev.yml](../backend/src/main/resources/application-dev.yml)
2. [common/ApiResponse.java](../backend/src/main/java/com/templeregistry/common/ApiResponse.java), [entity/base/BaseEntity.java](../backend/src/main/java/com/templeregistry/entity/base/BaseEntity.java)
3. **The simplest vertical slice**, the geo module: [GeoController](../backend/src/main/java/com/templeregistry/controller/geo/GeoController.java) → [GeoServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/geo/GeoServiceImpl.java) → [DistrictRepository](../backend/src/main/java/com/templeregistry/repository/geo/DistrictRepository.java) → [District](../backend/src/main/java/com/templeregistry/entity/geo/District.java) → [GeoMapper](../backend/src/main/java/com/templeregistry/mapper/geo/GeoMapper.java) (and its generated `GeoMapperImpl` in `target/`)
4. Security: [SecurityConfig](../backend/src/main/java/com/templeregistry/config/SecurityConfig.java) → [JwtAuthenticationFilter](../backend/src/main/java/com/templeregistry/security/JwtAuthenticationFilter.java) → [ScopeHelper](../backend/src/main/java/com/templeregistry/security/ScopeHelper.java) → [RoleConstants](../backend/src/main/java/com/templeregistry/security/RoleConstants.java) → [JurisdictionGuard](../backend/src/main/java/com/templeregistry/security/JurisdictionGuard.java) / [OwnershipGuard](../backend/src/main/java/com/templeregistry/security/OwnershipGuard.java) → [AuthController](../backend/src/main/java/com/templeregistry/controller/auth/AuthController.java) → [AuthServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/auth/AuthServiceImpl.java)
5. [GlobalExceptionHandler](../backend/src/main/java/com/templeregistry/exception/GlobalExceptionHandler.java)
6. A real module: [TrustController](../backend/src/main/java/com/templeregistry/controller/trust/TrustController.java) → [TrustServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/trust/TrustServiceImpl.java) (`create`, `toResponse`) → [Trust](../backend/src/main/java/com/templeregistry/entity/trust/Trust.java) / [BoardMember](../backend/src/main/java/com/templeregistry/entity/trust/BoardMember.java) → [AesEncryptionConverter](../backend/src/main/java/com/templeregistry/util/AesEncryptionConverter.java)
7. The governance core: [TransitionRuleRegistry](../backend/src/main/java/com/templeregistry/service/workflow/TransitionRuleRegistry.java) → [WorkflowEngineImpl.execute](../backend/src/main/java/com/templeregistry/service/workflow/impl/WorkflowEngineImpl.java) → [WorkflowEngineAdaptor](../backend/src/main/java/com/templeregistry/service/workflow/WorkflowEngineAdaptor.java) → [GovernanceWorkflowServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/governance/GovernanceWorkflowServiceImpl.java) (`submitDeclaration`, `approveDeclaration`)
8. Declarations: [DeclarationServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/declaration/DeclarationServiceImpl.java) (`create`, `replaceAssetItems`) → [DeclarationAssetMapper](../backend/src/main/java/com/templeregistry/mapper/declaration/DeclarationAssetMapper.java) → [DeclarationRepository](../backend/src/main/java/com/templeregistry/repository/declaration/DeclarationRepository.java)
9. Notifications: [NotificationRouter](../backend/src/main/java/com/templeregistry/service/notification/impl/NotificationRouter.java) → [NotificationDispatchServiceImpl](../backend/src/main/java/com/templeregistry/service/notification/impl/NotificationDispatchServiceImpl.java) → [EmailDeliveryService](../backend/src/main/java/com/templeregistry/service/notification/impl/EmailDeliveryService.java)
10. The DC read side: [DcTempleProfileServiceImpl](../backend/src/main/java/com/templeregistry/service/impl/dc/DcTempleProfileServiceImpl.java), then [db/migration/V1__initial_schema.sql](../backend/src/main/resources/db/migration/V1__initial_schema.sql) to see it all as tables
11. Tests as documentation: [AuthServiceImplTest](../backend/src/test/java/com/templeregistry/service/impl/auth/AuthServiceImplTest.java), [TempleControllerTest](../backend/src/test/java/com/templeregistry/controller/temple/TempleControllerTest.java), [DeclarationHappyPathIT](../backend/src/test/java/com/templeregistry/integration/DeclarationHappyPathIT.java)

**You can skip** (dead or legacy): `OverdueScheduler`, `DeclarationWorkflowServiceImpl`, `DeclarationApprovalPolicy`, `TrustMapper`, both `StatusTransitionValidator*`, `AcknowledgementNumberGenerator`, `AwsConfig`, the `event/{board,declaration,temple,trust,…}` legacy event classes.

### "If I add a feature, where do I change things?"
| Change | Touch |
|---|---|
| New column | `V114__….sql` → entity `@Column` → request/response DTO → mapper/`toResponse` → run the Testcontainers context test |
| New endpoint | controller method → service interface + impl (`@PreAuthorize`, guards, `@Transactional`) → DTOs → exception if needed |
| New approval step | `WorkflowAction` enum → `TransitionRuleRegistry` rule → orchestrator method in `GovernanceWorkflowServiceImpl` (dual write) → v1 governance endpoint → `notification_rules` row + email template |
| New business veto on a transition | a new `@Component implements WorkflowPolicy` (auto-discovered) |
| New notification | a `notification_rules` row (event, entity type, action, recipient type, channel, template key) + `templates/email/<key>.html` |
| New role check | a constant in `RoleConstants` → use on the service → add a reflection test like `PasswordManagementAuthorizationTest` |

## 12.5 Every finding, ranked (Chapters 0–11, with the Chapter 10 corrections applied)

All are unchanged in the code, as you asked. "Likely" means the code path is clear but I didn't execute it.

### 🔴 Critical
| # | Finding | Ch. |
|---|---|---|
| 1 | **Secrets in the repo**: TiDB password as the `application.yml` fallback; **JWT private key** in `resources/keys/`. Anyone with repo access can mint SUPER_ADMIN tokens. Rotate the DB password and key pair | 0, 3 |
| 2 | **MFA never verified**: `verifyMfa` ignores `mfaCode`; `MfaService` is never called | 3 |
| 3 | **Account lockout never persists**: the failed-count write is rolled back by the exception thrown right after it | 3 |
| 4 | **Full Aadhaar in plaintext** on `users.aadhaar_number`, returned by the admin user API | 4, 8 |

### 🟠 High
| # | Finding | Ch. |
|---|---|---|
| 5 | JWT filter accepts TEMP/registration tokens as logged-in principals with role `null`, which passes the guards (likely) | 3 |
| 6 | `DC_STAFF` can approve/reject via `POST /api/v2/workflow/{id}/action` (`isDc()` includes it) | 10 |
| 7 | v2 `/action` bypasses the orchestrator: no ack, entity status diverges, the v1 path is then blocked | 10 |
| 8 | DACVM access policies are not enforced server-side (`@DacvmGuard` unused) | 3 |
| 9 | Any logged-in non-TA role can list any temple's declarations, drafts included (`listByTemple`) | 7 |
| 10 | Temple photo upload/delete open to AUDITOR/VIEWER/other-district DCs | 5 |
| 11 | Deactivated users can log in and refresh | 3 |
| 12 | Overdue detection never runs (`deadline_at` never set) | 10 |
| 13 | Duplicate emails and double SSE toasts per transition; repeat events (clarification rounds 2–3, resubmits) produce no in-app notification (likely) | 10 |
| 14 | Duplicate acknowledgement numbers possible (max + 1, `SERIALIZABLE` ignored, no unique constraint) | 6 |
| 15 | Stale `expectedVersion` → 500 instead of 409 (`jakarta` `OptimisticLockException` from service code) (likely) | 9 |
| 16 | v2 engine returns 409 naming both districts, where the DC module deliberately returns a blank 404 | 9 |
| 17 | `annualRent` stored as the monthly figure; DC full profile shows annual rent 12× too low | 8 |
| 18 | AES decrypt failure returns `null`; a later save can overwrite the ciphertext (likely for board-member Aadhaar) | 4 |

### 🟡 Medium
| # | Finding | Ch. |
|---|---|---|
| 19 | Only one real-DB test runs; the 5 workflow ITs are excluded (no Failsafe); nothing tests `@PreAuthorize` enforcement end to end | 11 |
| 20 | Unknown URLs, type mismatches, 405/415, duplicate keys → 500 | 9 |
| 21 | Validation errors omit field names (the Chapter 8 example format was wrong; corrected in Chapter 9) | 9 |
| 22 | VIEW-only TA check (`assertCanEdit`) called in only 4 places | 3, 6 |
| 23 | Two schema owners (Flyway + `ddl-auto: update`), `validate-on-migrate: false`, auto-repair, squashed V1 | 2, 4 |
| 24 | Soft delete + unique constraints: re-creating a deleted username/email fails with 500 | 4, 9 |
| 25 | Ack PDF written before the final checks (orphans on rollback) | 6 |
| 26 | Search ignores `cityId`, `hasApprovedDeclaration`, `pendingProfileReview`; ignores `sort` | 6, 7 |
| 27 | Previous approved profile may not be superseded (unordered `findFirst`) | 10 |
| 28 | Overdue scheduler batch in one transaction: one failure undoes all (latent) | 10 |
| 29 | Ambiguous `GET /api/v2/workflow/{id}/history` mapping (↘ from 🟠: UI doesn't render its caller) | 5, 10 |
| 30 | v2 clarification endpoints serialize JPA entities: lazy proxy + cycle (↘ from 🟠: same reason) | 8, 10 |
| 31 | `IllegalStateException` → 422 with raw internal messages | 9 |
| 32 | Tokens also returned in the JSON body; `SameSite=None` with CSRF disabled | 3 |
| 33 | `RequestIdFilter` runs after security; MDC `userId`/`role` never cleared | 2 |
| 34 | Declaration FY validated by regex only (future / inconsistent years accepted) | 8 |
| 35 | Upload-limit message says 5 MB; effective default is 1 MB | 9 |

### ⚪ Low / cleanup
- **Dead code:** `OverdueScheduler`, `DeclarationWorkflowServiceImpl` (and the only use of the pessimistic lock), `DeclarationApprovalPolicy`, `TrustMapper`, `AcknowledgementNumberGenerator`, `InvalidStateTransitionException`, both `StatusTransitionValidator*`, `AwsConfig`, the `UserDetailsServiceImpl`/`DaoAuthenticationProvider` login path, the unrendered `WorkflowGovernancePanel` tree on the frontend.
- **Duplicated mechanisms:** two snapshot systems, two clarification stores, two `NotificationEventPublisher` types, two SSE endpoints, three workflow API families.
- **Ignored or misnamed properties:** `app.notification.email-enabled`, `spring.flyway.repair-on-migrate`, and in tests `app.encryption.aes-key` / `cloud.aws.*`.
- **Performance:** `TrustDataRepairService` scans three tables at every boot; per-item DB calls in `toResponse` methods; the single scheduler thread is shared by 5 jobs.
- **Stale comments/tests:** `DeclarationHappyPathIT` Javadoc, the `PasswordManagementAuthorizationTest` claim about "live" enforcement, `OverdueSchedulerIT` targeting the dead class, `AcknowledgementPropertyTest` re-implementing the format instead of calling the service.

**If you want a starting order for fixes later:** #1 (rotate), then #2, #3, #6, #5. Each is a small, contained change, and each deserves a failing test first (§11.7 lists the smallest one for each). I'm glad to do any of them when you say so.

---

That completes the course: **Chapter 0 (project map) through Chapter 12 (mental model, cheat sheet, reading order, ranked findings).**

If it would help, I can turn the whole guide into a single shareable page for your team, with the cheat sheet and the ranked findings up front.

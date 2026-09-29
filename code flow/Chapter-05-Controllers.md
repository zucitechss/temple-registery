# CHAPTER 5 — Controllers

Controllers are the reception desk of the backend. Each one receives an HTTP request, reads what the client sent, passes the real work to a service, and packages the result into a response.

## 5.0 How a URL turns into a Java method call

```text
HTTP request ──► (filters from Ch.2/3) ──► DispatcherServlet
                                              │
                    ① HandlerMapping: "which method owns  POST /api/v1/temples/42/photos ?"
                                              │   → TempleController.uploadTemplePhotos
                    ② Argument resolvers: build each parameter
                                              │   @PathVariable "42" → Long 42
                                              │   @RequestPart "files" → List<MultipartFile>
                                              │   @RequestBody JSON → DTO (Jackson) → @Valid checks
                    ③ Call the method (through its proxy, so @PreAuthorize runs first)
                                              │
                    ④ Return-value handler:
                                              │   ResponseEntity<ApiResponse<…>> → Jackson → JSON
                                              │   ResponseEntity<Resource>       → raw bytes (file)
                                              │   SseEmitter                     → open event stream
                                              ▼
                                        HTTP response
```

All of this is Spring MVC, configured automatically. `DispatcherServlet` builds the lookup table from all 39 `@RestController` classes at startup (Chapter 2 §④h).

## 5.1 Anatomy of a controller

I'll use real lines from [TempleController.java](../backend/src/main/java/com/templeregistry/controller/temple/TempleController.java):

```java
@RestController                                   // ① this class answers HTTP; return values become the body
@RequestMapping("/api/v1/temples")                // ② prefix for every method below
@RequiredArgsConstructor                          // ③ constructor injection (Ch.2)
@Validated                                        // ④ enables validation on simple params (see 5.9)
@Tag(name = "Temples", description = "...")       // ⑤ Swagger grouping only
public class TempleController {

    private final TempleService templeService;                // depends on the INTERFACE
    private final TempleProfileStagingService stagingService;

    @GetMapping("/{id}")                                      // ⑥ GET /api/v1/temples/{id}
    @Operation(summary = "Get temple detail by ID")           //    Swagger text
    @PreAuthorize(RoleConstants.CAN_READ_ALL + " or " + RoleConstants.TEMPLE_AUTHORITY_ONLY)  // ⑦
    public ResponseEntity<ApiResponse<TempleResponse>> getById(@PathVariable Long id) {      // ⑧ ⑨
        return ResponseEntity.ok(                                                            // ⑩
                ApiResponse.success("Temple retrieved.", templeService.getById(id)));        // ⑪
    }
}
```

| # | Element | Plain meaning |
|---|---|---|
| ① | `@RestController` | `@Controller` + `@ResponseBody`. Whatever the method returns is written to the HTTP body, converted to JSON by Jackson. No HTML views |
| ② | `@RequestMapping` on the class | URL prefix. Several controllers use a bare `/api/v1` (e.g. `TrustController`, `EmployeeController`, `ContractorController`) and spell out full paths per method, because their URLs mix `/temples/{id}/…` and `/trusts/{id}/…` |
| ⑥ | `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` | HTTP verb + path suffix. `{id}` is a **path variable**, a placeholder |
| ⑦ | `@PreAuthorize` | The role check from Chapter 3, evaluated before the method body |
| ⑧ | `ResponseEntity<...>` | Lets you choose the **status code and headers**, not just the body |
| ⑨ | `@PathVariable Long id` | Takes `{id}` from the URL and converts `"42"` to `42L`. Non-numeric input like `/temples/abc` fails conversion → 400 or 500, depending on the handler (Chapter 9) |
| ⑩ | `ResponseEntity.ok(...)` | HTTP 200 |
| ⑪ | `ApiResponse.success(msg, data)` | The standard envelope from Chapter 1 |

### Every parameter style used in this codebase

| Style | Example (real) | Where the value comes from |
|---|---|---|
| `@PathVariable` | `getById(@PathVariable Long id)` | URL segment |
| `@RequestParam` | `getHistory(..., @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size)` | `?page=0&size=10` |
| `@RequestParam(required = false)` | `getDiff(@PathVariable Long id, @RequestParam(required = false) Integer compareToVersion)` | Optional query param; `null` if absent |
| **No annotation, object type** | `search(TempleSearchFilterRequest filter)` | Spring creates the object and fills each field from query params with the same name: `?districtId=3&keyword=shiva&grade=A&grade=B&page=0` (this is the implicit `@ModelAttribute`) |
| `@RequestBody` + `@Valid` | `create(@Valid @RequestBody CreateTempleRequest request)` | JSON body → DTO, then validation |
| `@RequestPart` + `MultipartFile` | `uploadTemplePhotos(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files)` with `consumes = MULTIPART_FORM_DATA_VALUE` | A `multipart/form-data` form. `MultipartFile` gives `getBytes()`, `getOriginalFilename()`, `getContentType()` |
| `@RequestHeader` | `exportTemples(..., @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)` | HTTP header |
| `Authentication auth` | `WorkflowController.executeAction(..., Authentication auth)` | Spring injects the object `JwtAuthenticationFilter` put into the `SecurityContext` |
| `@AuthenticationPrincipal ScopeHelper.Claims claims` | `AuthController.myPermissions(...)` | The **principal** inside that Authentication, already cast to `Claims` |
| `HttpServletRequest` / `HttpServletResponse` | `AuthController.login(..., HttpServletResponse httpResponse)` | Raw servlet objects, used here to read and set cookies |

## 5.2 What controllers return

| Return type | Used for | Status codes seen | Example |
|---|---|---|---|
| `ResponseEntity<ApiResponse<T>>` | Almost everything | `ok` = **200**, `status(CREATED)` = **201**, `status(ACCEPTED)` = **202** | Every CRUD endpoint |
| `ResponseEntity<ApiResponse<PaginatedResponse<T>>>` | Lists | 200 | `TempleController.search`, `DeclarationController.list` |
| `ResponseEntity<ApiResponse<Void>>` + `ApiResponse.success(msg)` | Commands with no data | 200 | `DeclarationController.submit`, `ProfileController.changePassword` |
| `ResponseEntity<Void>` + `noContent()` | Commands | **204**, empty body | `WorkflowController.resolve` |
| `ResponseEntity<Resource>` | Files (PDF, CSV, images) | 200 / 400 / 404 | `downloadAcknowledgement`, `serveTemplePhoto`, `DcExportController.download` |
| `SseEmitter` | Real-time push | 200, stays open | `NotificationSseController.stream`, `WorkflowController.subscribeToNotifications` |

Status-code conventions in this codebase:
- **201** for creates: `TempleController.create`, `DeclarationController.create`, the photo uploads, and the geo creates.
- `createOrUpdateDraft` also returns **201**, even when it *updates* an existing draft.

Errors never come from the controller directly (with two exceptions, 5.6e). They come from exceptions thrown in the service, which `GlobalExceptionHandler` converts (Chapter 9).

## 5.3 Four ways a controller learns "who is calling"

| Way | Where you'll see it | Notes |
|---|---|---|
| **A. Doesn't ask.** The service reads `SecurityContextHolder` itself | Most controllers (`TempleController`, `TrustController`, `GeoController`) | Cleanest: the controller stays thin |
| **B. Private `currentClaims()` helper** reading `SecurityContextHolder` | `DeclarationController.respondToClarification`, `DcExportController`, `NotificationSseController` | `(ScopeHelper.Claims) ...getPrincipal()`. Works because these URLs are never anonymous |
| **C. `Authentication auth` param + `ActionContextResolver`** | `WorkflowController` (v2) | Converts the JWT into an `ActionContext` (`actorId`, `actorRole`, `actorDistrictId`, `ownedTempleIds`) for the workflow engine |
| **D. `@AuthenticationPrincipal Claims`** | `AuthController.myPermissions` | `null` for anonymous callers, so it checks and returns 401 itself |

Way **C** does role translation. `ActionContextResolver.resolveRole` (the `controllerActionContextResolver` bean):

```java
.map(a -> a.getAuthority().replace("ROLE_", ""))          // "ROLE_DISTRICT_COLLECTOR" → "DISTRICT_COLLECTOR"
.map(r -> switch (r) {
    case "DISTRICT_COLLECTOR" -> "DC";                     // the workflow engine uses short names
    case "TEMPLE_AUTHORITY"   -> "TA";
    default -> r;
})
.filter(r -> r.equals("TA") || r.equals("DC") || r.equals("DC_STAFF") || r.equals("SUPER_ADMIN") || r.equals("SYSTEM"))
.findFirst().orElse("UNKNOWN");                           // AUDITOR / VIEWER → "UNKNOWN"
```

So the **workflow engine speaks a different role vocabulary (`DC`, `TA`)** than `RoleConstants` (`DISTRICT_COLLECTOR`, `TEMPLE_AUTHORITY`). Keep that in mind when reading `TransitionRuleRegistry` in Chapter 10.

## 5.4 The endpoint map (≈ 230 endpoints, grouped)

| Area | Controller | Base URL | Main service it calls | Who (roughly) |
|---|---|---|---|---|
| Login / tokens / reset | `AuthController` | `/api/v1/auth` | `AuthService`, `UserProfileService` | public |
| Admin creates account | `RegistrationController` | `/api/v1/auth/register/create` | `RegistrationService` | SA |
| Own password | `ProfileController` | `/api/v1/profile/password` | `UserProfileService` | any logged-in user |
| Geo lookups | `GeoController` | `/api/v1/geo` | `GeoService` | public read; SA write |
| **Temple** + profile staging + photos | `TempleController` | `/api/v1/temples` | `TempleService`, `TempleProfileStagingService` | public search; TA/SA drafts |
| **Trust**, board members, meetings, financials | `TrustController` | `/api/v1/temples/{id}/trusts`, `/api/v1/trusts/**` | `TrustService` | TA / DC / SA |
| **Declarations** | `DeclarationController`, `ConversationController` | `/api/v1/temples/{id}/declarations`, `/api/v1/declarations/**` | `DeclarationService`, `ConversationService` | TA / DC |
| Employees / contractors | `EmployeeController`, `ContractorController` | `/api/v1/temples/{id}/employees` … | `EmployeeService`, `ContractorService` | TA |
| Documents | `DocumentController` | `/api/v1/documents` | `DocumentService` | all |
| **Governance v1** (trust + declaration workflow) | `GovernanceWorkflowController` | `/api/v1/governance/**` | `GovernanceWorkflowService` | TA submit; DC approve/reject/clarify/site-visit |
| **Workflow v2** (generic engine) | `WorkflowController`, `WorkflowHistoryController` | `/api/v2/workflow/**` | `WorkflowEngine`, `ClarificationEngine`, `WorkflowHistoryService` | TA / DC |
| Governance v2 reads | `GovernanceV2Controller` | `/api/v2/declarations/{id}`, `/api/v2/trusts/{id}`, … | `WorkflowEnvelopeAssembler` (Ch.10) | — |
| DC module (10 controllers) | `Dc*Controller` | `/api/v1/dc/**` | `DcTempleSearchService`, `DcTempleProfileService`, `DcDashboardService`, `DcExportService`, … | DC / DC_STAFF / SA |
| TA module | `TaDashboardController` | `/api/v1/ta/**` | `TaDashboardService` | TA |
| Admin (4 controllers) | `Admin*`, `AccessControlController`, `SystemConfigController` | `/api/v1/admin/**` | `AdminService`, `PolicyManagementService`, … | SA |
| Auditor / viewer | `AuditorController`, `ViewerDashboardController`, `ObservationController` | `/api/v1/auditor`, `/api/v1/viewer`, `/api/v1/observations` | … | Auditor / viewer |
| Notices | `NoticeController` | `/api/v1/notices` | `NoticeService` | SA/DC write, all read |
| Notifications | `NotificationController`, `NotificationPreferenceController`, `NotificationSseController`, `DcNotificationController` | `/api/v1/notifications`, `/api/v1/dc/notifications` | `NotificationService`, `SseNotificationService` | any logged-in user |
| Export | `ExportController`, `DcExportController` | `/api/v1/export`, `/api/v1/dc/export` | `ExportService`, `DcExportService` | read roles |
| Timeline | `TempleTimelineController` | `/api/v1/timeline/temples/{id}` | `TempleTimelineService` | — |

**Reading tip:** to find the code behind a frontend call, search for the **last literal segment** of the URL, for example `"/submit")` or `"/{templeId}/verify"`. The base path lives on the class, so the full URL never appears as one string.

## 5.5 Where authorization lives, per controller

There are three styles, and the same module can mix them:

| Style | Controllers | Consequence |
|---|---|---|
| **Class-level** `@PreAuthorize` | all 4 admin, 8 of 10 DC controllers, `TaDashboardController`, `AuditorController`, `ViewerDashboardController`, `ObservationController`, `ExportController`, `DocumentController`, notifications | One rule guards every method. Method-level rules can tighten it further |
| **Method-level in the controller** | `TempleController`, `TrustController` (every method), `GovernanceWorkflowController`, `WorkflowController`, `GeoController` (writes) | Visible at the endpoint |
| **Only in the service** | `DeclarationController` (e.g. `create` has none in the controller; `DeclarationServiceImpl.create` has `@PreAuthorize(CAN_SUBMIT)`), `NoticeController`, `ProfileController` | Safe as long as the service has it. To see who can call an endpoint, **always open the service method too** |

> **Rule of thumb in this codebase:** the service's `@PreAuthorize` and guards are the source of truth. Controller rules are an extra, early check.

## 5.6 The endpoint patterns you'll meet, with real code

### a. Search with a query-param object and pagination (`GET /api/v1/temples`)
```java
@GetMapping
public ResponseEntity<ApiResponse<PaginatedResponse<TempleSearchResultResponse>>> search(
        TempleSearchFilterRequest filter) {           // ← no annotation: built from ?query=params
    return ResponseEntity.ok(ApiResponse.success("Temples retrieved.", templeService.search(filter)));
}
```
Request: `GET /api/v1/temples?districtId=3&grade=A&grade=B&keyword=shiva&page=0&size=10&sort=name,asc`

`TempleSearchFilterRequest` has `@Setter` and defaults (`page = 0`, `size = 10`, `sort = "name,asc"`). Spring calls `setDistrictId(3)`, `setGrade(["A","B"])`, and so on.

This URL is **public** (`SecurityConfig` permits exact `GET /api/v1/temples`).

⚠️ The DTO has `@Max(100)` on `size` and `@Size(max = 200)` on `keyword`, but the parameter has **no `@Valid`**. So those constraints are most likely **not applied**: `?size=100000` reaches the service. Whether the service clamps it, we'll see in Chapter 6.

### b. Create → 201 (`POST /api/v1/temples/{templeId}/declarations`)
```java
@PostMapping("/api/v1/temples/{templeId}/declarations")
public ResponseEntity<ApiResponse<CompleteDeclarationResponse>> create(
        @PathVariable Long templeId, @Valid @RequestBody CreateDeclarationRequest rq) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Declaration created.", declarationService.create(templeId, rq)));
}
```
Four steps, all visible in the code:
1. The `templeId` comes from the URL, not the body. That makes "which temple" explicit.
2. `@Valid` checks `CreateDeclarationRequest` (and its nested item lists, Chapter 8).
3. `DeclarationServiceImpl.create` enforces `CAN_SUBMIT` plus ownership.
4. The response is `201`.

### c. File upload (`POST /api/v1/temples/{id}/photos`)
```java
@PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ApiResponse<List<String>>> uploadTemplePhotos(
        @PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Temple photos uploaded.", templeService.uploadTemplePhotos(id, files)));
}
```
- `consumes = multipart/form-data` → only form uploads match this mapping.
- `@RequestPart("files")` → the form field name must be `files`. Send it several times for several files.
- Upload size limits come from Spring Boot's `spring.servlet.multipart.*` defaults (1 MB per file, 10 MB per request), since **I found no override in `application.yml`**. Too big → `MaxUploadSizeExceededException` → `GlobalExceptionHandler` → 400.

**⚠️ Authorization gap (from the service code).** Neither this endpoint nor `DELETE /{templeId}/photos/{photoId}` has a controller `@PreAuthorize`. The service methods have only `@PreAuthorize("isAuthenticated()")` plus `ownershipGuard.assertOwnsTemple(templeId)`, and that guard **only restricts `TEMPLE_AUTHORITY`**. There is no role check and no `JurisdictionGuard`. So an `AUDITOR`, a `VIEWER`, or a DC from **another district** can upload or delete photos on any temple.

### d. File download (`GET /api/v1/declarations/{id}/acknowledgement/download`)
```java
public ResponseEntity<Resource> downloadAcknowledgement(@PathVariable Long id) {
    Resource resource = declarationService.downloadAcknowledgement(id);
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ACK_DECLARATION_" + id + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(resource);
}
```
- `Resource` → Spring streams the file bytes; no JSON.
- `Content-Disposition: attachment` → the browser downloads. `inline` (used by the photo `serve` endpoints) → the browser displays.
- The photo `serve` endpoints add `Cache-Control: max-age=86400, public`. That's fine because they're public URLs.

### e. Async job with an idempotency header (`POST /api/v1/dc/export/temples`)
```java
public ResponseEntity<ApiResponse<ExportJobResponse>> exportTemples(
        @Valid @RequestBody ExportTemplesRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    ExportJobResponse result = dcExportService.exportTemples(request, idempotencyKey, currentClaims());
    return buildExportResponse(result);
}
private ResponseEntity<ApiResponse<ExportJobResponse>> buildExportResponse(ExportJobResponse result) {
    if ("ASYNC_ACCEPTED".equals(result.getStatus()))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(...);   // 202: queued, check inbox later
    return ResponseEntity.ok(...);                                      // 200: file ready now
}
```
- **Idempotency key.** The frontend sends a unique key per click. If the same key arrives twice (a double click or a retry), the service returns the first result instead of starting a second export. It's stored in `idempotency_records` (Chapter 4).
- **200 vs 202.** Small exports (< 500 rows, per the `@Operation` text) run immediately. Large ones go to `AsyncExportBean` on the `exportExecutor` pool (Chapter 2) and return **202 Accepted**.

The matching download does security **in the controller**:
```java
if (!jobId.matches("^[a-zA-Z0-9\\-]+$")) return ResponseEntity.badRequest().build();   // no "../" path traversal
ExportJobRecord record = exportJobRecordRepository.findById(jobId).orElse(null);        // repository in a controller!
if (record == null || expired) return ResponseEntity.notFound().build();
if (!SUPER_ADMIN && !claims.userId().equals(record.getActorUserId())) return ResponseEntity.notFound().build(); // only the owner
```
Good defensive code: the regex blocks path traversal, and the 404 (not 403) doesn't reveal that a job exists. It does break the layering rule, though (5.8).

### f. Workflow v2 command (`POST /api/v2/workflow/{instanceId}/action`)
```java
@PreAuthorize("hasAnyRole('SUPER_ADMIN','DISTRICT_COLLECTOR','DC_STAFF','TEMPLE_AUTHORITY')")
public ResponseEntity<ApiResponse<WorkflowTransitionResult>> executeAction(
        @PathVariable Long instanceId,
        @Valid @RequestBody WorkflowActionHttpRequest body,     // record { action, expectedVersion, idempotencyKey, comment }
        Authentication auth) {
    ActionContext context = actionContextResolver.resolve(auth);          // JWT → who/role/district/temples
    WorkflowActionRequest request = WorkflowActionRequest.builder()       // HTTP DTO → internal command object
        .action(body.action()).expectedVersion(body.expectedVersion())
        .idempotencyKey(body.idempotencyKey()).comment(body.comment()).build();
    WorkflowTransitionResult result = workflowEngine.execute(instanceId, request, context);
    return ResponseEntity.ok(ApiResponse.success("Action executed.", result));
}
```
Request body:
```json
{ "action": "APPROVE", "expectedVersion": 3, "idempotencyKey": "b1f…", "comment": "Verified" }
```
- **One endpoint for every action.** `action` is a `WorkflowAction` enum. An unknown value fails JSON parsing → `HttpMessageNotReadableException` → 400.
- `expectedVersion` → the client says "I'm acting on version 3". The engine rejects the call if the workflow has moved on (optimistic locking, Chapter 4 §4.4).
- The controller converts the **HTTP shape** (`WorkflowActionHttpRequest`, a nested `record`) into the **domain command** (`WorkflowActionRequest`). This keeps the engine independent of HTTP.
- ⚠️ `@Valid` is present, but the record has **no constraint annotations**, so a missing `action` arrives as `null`. It depends on `WorkflowEngineImpl` to reject that (Chapter 10).

### g. Server-Sent Events (`GET /api/v1/notifications/stream`)
```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream() {
    Long userId = currentUserId();
    return sseNotificationService.subscribe(userId);
}
```
**What SSE is:** a normal GET that **stays open**. The server keeps writing `data: {...}` lines whenever a notification arrives.
- Returning an `SseEmitter` tells Spring "don't close this response."
- The browser's `EventSource` can't set an `Authorization` header, which is why `JwtAuthenticationFilter` accepts `?token=` **only for URLs ending in `/stream`** (Chapter 3).
- There are **two** streams: this one and `GET /api/v2/workflow/notifications/stream` (in `WorkflowController`). The Javadoc on `SseNotificationService` mentions a third, `/api/v2/notifications/stream`, which doesn't exist as a mapping. Chapter 10 will show which one the frontend uses.
- The `AsyncRequestTimeoutException` / `AsyncRequestNotUsableException` handlers in `GlobalExceptionHandler` exist for these long-lived connections (Chapter 9).

### h. Deprecated alias (`POST /api/v1/declarations/{id}/submit`)
```java
@Deprecated(forRemoval = true)
@PostMapping("/api/v1/declarations/{id}/submit")
public ResponseEntity<ApiResponse<Void>> submit(@PathVariable Long id) {
    governanceWorkflowService.submitDeclaration(id);         // forwards to the governance service
    return ResponseEntity.ok(ApiResponse.success("Declaration submitted."));
}
```
This keeps old frontend calls working while the logic lives in one place.

## 5.7 Three workflow APIs side by side

For declarations, you can currently trigger state changes through **three** URL families:

| API | Example | Goes to |
|---|---|---|
| Legacy module endpoint (deprecated) | `POST /api/v1/declarations/{id}/submit` | `GovernanceWorkflowService.submitDeclaration` |
| **Governance v1** (per action, per module) | `POST /api/v1/governance/declarations/{id}/approve` | `GovernanceWorkflowService` → (Ch.10 will show if it calls `WorkflowEngine`) |
| **Workflow v2** (generic) | `POST /api/v2/workflow/{instanceId}/action` `{"action":"APPROVE"}` | `WorkflowEngine.execute` |

The DC module has its **own** approve/verify/flag endpoints for profiles and board members: `DcProfileController`, `DcTempleController`, `DcBoardMemberController`, `DcComplianceController`.

Which of these is "the real one" for each module is the core question of Chapter 10. The code comments claim both `GovernanceWorkflowService` ("SINGLE SOURCE OF TRUTH for… TRUST and ASSET DECLARATION") and `WorkflowEngine` ("single entry point for ALL state transitions"), so we need to trace the calls to find out.

## 5.8 Controllers that break the Controller → Service → Repository rule

The intended rule is **controllers never touch repositories**. Five do, and each is worth knowing:

| Controller | Repositories imported | Why it matters |
|---|---|---|
| `AdminController` | 6 | Some admin logic lives in the controller. Check here before assuming "it's all in `AdminServiceImpl`" |
| `DcContextController` | 4 | Builds `GET /api/v1/dc/me` directly from repositories |
| `DcExportController` | 2 | Download ownership check (5.6e) |
| `AccessControlController` | 2 | Policy reads |
| `WorkflowController` | 1 (`WorkflowTransitionRepository`) | History read |

The practical risk: these reads have **no `@Transactional` service boundary**. They rely on `open-in-view` (Chapter 4 §4.7) for lazy loading, and any business rule written there isn't reusable.

## 5.9 Findings from this chapter

| Severity | Finding | Where |
|---|---|---|
| 🟠 | **Ambiguous mapping at runtime (likely 500).** `WorkflowController.getHistory` maps `GET /api/v2/workflow/{instanceId}/history`, and `WorkflowHistoryController.getHistory` maps `GET /api/v2/workflow/{workflowInstanceId}/history`. Only the variable names differ, so startup succeeds, but both match every request equally and Spring normally throws `IllegalStateException: Ambiguous handler methods`. The frontend calls exactly this URL. Worth one `curl` to confirm | `governance/WorkflowController.java:157`, `governance/WorkflowHistoryController.java:28`, [workflowApi.ts:146](../frontend/src/features/governance/workflowApi.ts#L146) |
| 🟠 | **Temple photo upload/delete open to read-only roles and other districts.** Only `isAuthenticated()` plus the TA-only ownership guard | `TempleController` `/{id}/photo(s)`, `DELETE /{templeId}/photos/{photoId}` → `TempleServiceImpl` |
| 🟡 | `@Valid` on `WorkflowActionHttpRequest` (and likely the other nested records) is a no-op: no constraints on the record | `WorkflowController` |
| 🟡 | `TempleSearchFilterRequest` constraints (`@Max(100)` on size, etc.) likely not applied: no `@Valid` on the parameter | `TempleController.search` |
| 🟡 | Three overlapping workflow APIs, two SSE endpoints, deprecated aliases still live | 5.6g, 5.7 |
| ⚪ | Repositories used directly in 5 controllers | 5.8 |
| ⚪ | `createOrUpdateDraft` returns 201 even for updates; `RegistrationController`'s `/api/v1/auth/register/create` sits under the public auth path (protected only by `@PreAuthorize`, so anonymous → 403) | — |

## 5.10 The five questions, for two controllers

```text
Who calls me?   Frontend (RTK Query in features/*/…Api.ts) → DispatcherServlet
      ↓
[TempleController]
      ↓
Who do I call?  TempleService (search, CRUD, photos) · TempleProfileStagingService (draft → submit)

INPUT   query params (search) · JSON DTOs · multipart files · path ids
  ↓     no business logic; picks status code (200/201) and headers (photos: inline + cache)
OUTPUT  ApiResponse<TempleResponse | PaginatedResponse | TempleProfileStagingResponse> · Resource (images)
```
```text
Who calls me?   Frontend governance screens (workflowApi.ts)
      ↓
[WorkflowController]  (v2)
      ↓
Who do I call?  controllerActionContextResolver · WorkflowEngine · ClarificationEngine
                · SseNotificationService · WorkflowTransitionRepository (directly)

INPUT   instanceId · {action, expectedVersion, idempotencyKey, comment} · Authentication
  ↓     JWT → ActionContext (DC/TA vocabulary) · HTTP record → WorkflowActionRequest
OUTPUT  ApiResponse<WorkflowTransitionResult | WorkflowStateResponse | threads | history> · SseEmitter
```

## 5.11 Adding a new endpoint: the checklist for this codebase

1. Put it in the controller of the module that owns the data. Match that controller's `@RequestMapping` style.
2. Use a request DTO in `dto/request/<module>/` with constraint annotations, and write **`@Valid @RequestBody`**. For query-param objects, remember to add `@Valid`.
3. Put **`@PreAuthorize(RoleConstants.X)`** on the **service** method (the source of truth). Optionally repeat it on the controller.
4. In the service, call `ownershipGuard` / `jurisdictionGuard` (use `assertDistrictScope` + `findWithGeoById` in DC code), and `accessGuard.assertCanEdit()` for TA writes.
5. Return `ResponseEntity.ok(ApiResponse.success("…", dto))`, or `status(CREATED)` for creates.
6. Throw the domain exceptions from `exception/` rather than building error responses. `GlobalExceptionHandler` maps them.
7. Keep repositories out of the controller.
8. Before merging, check for a mapping clash: search for the final path segment across `controller/`.

---

**Next: Chapter 6 — Services.** It covers:
- the interface + `impl/` pattern and why it's used
- `@Transactional` boundaries (read-only vs write, `REQUIRES_NEW`, and what rolls back)
- a guided read of the heaviest services: `TempleServiceImpl`, `TrustServiceImpl`, `DeclarationServiceImpl` (892 lines), `TempleProfileStagingServiceImpl`, `DcTempleProfileServiceImpl`
- how they call each other and the guards, audit, notification and search-summary services
- the service-to-service dependency graph, including any circular dependencies

Say **"continue"** when ready.

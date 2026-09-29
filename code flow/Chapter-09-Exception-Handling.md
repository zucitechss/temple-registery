# CHAPTER 9 — Exception Handling

This is the complaint desk: when something goes wrong anywhere, what does the client actually receive, and why? I read all of [GlobalExceptionHandler.java](../backend/src/main/java/com/templeregistry/exception/GlobalExceptionHandler.java), all 19 custom exceptions, and the error paths in the workflow engine, listeners and schedulers.

## 9.0 The journey of an exception

**Throwing, in simple words:** `throw new X(...)` stops the current method immediately. Java then "unwinds": it leaves each calling method in turn, back up the call stack, until something **catches** the exception. If nothing catches it, the caller gets an error.

Here is what happens when a DC from another district asks for a temple:

```text
GET /api/v1/dc/temples/42        (DC of Mysuru; temple 42 is in Bengaluru)
   │
DcTempleController.getFullProfile(42)
   │
   ▼  proxy: @PreAuthorize ✓ · BEGIN TRANSACTION (readOnly)
DcTempleProfileServiceImpl.getFullProfile(42)
   │  temple = templeRepository.findWithGeoById(42)
   │  jurisdictionGuard.assertDistrictScope(temple, claims)
   │        └── throw new DistrictScopeViolationException()     ← stops here
   ▼  proxy: RuntimeException escaped → ROLLBACK (nothing to undo; read-only)
   │  exception keeps unwinding …
DcTempleController.getFullProfile      ← doesn't catch, keeps unwinding
   │
DispatcherServlet → HandlerExceptionResolver → finds @RestControllerAdvice
   │
GlobalExceptionHandler.handleDistrictScopeViolation(ex)
   │  applyTimingDelay()               ← random 0–20 ms sleep
   ▼
HTTP 404  { "success": false, "message": "The requested resource was not found.",
            "timestamp": "...", "requestId": "..." }
```

The key points, which hold everywhere in this codebase:
1. **Services and guards throw; they never build error responses.** A service method returns a DTO or throws.
2. **Every custom exception `extends RuntimeException`** (all 19). That means:
   - **no `throws` clauses** clutter the method signatures (they're "unchecked");
   - **`@Transactional` rolls back automatically** on each of them (Chapter 6).
3. **One class decides the HTTP shape:** `GlobalExceptionHandler`.

## 9.1 How `@RestControllerAdvice` works

```java
@RestControllerAdvice          // "these handlers apply to ALL controllers; return values become JSON"
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)          // "if this type (or a subclass) escapes a controller…"
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)    // "…answer with this"
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }
    ...
    @ExceptionHandler(Exception.class)                        // the safety net — anything not matched above
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) { … 500 … }
}
```

- `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`: a global add-on to every controller.
- **Choosing a handler:** Spring picks the handler whose exception type is the **closest ancestor** of the thrown type. A `DistrictScopeViolationException` goes to its own handler, not to `Exception`. `AuthorizationDeniedException`, which is what `@PreAuthorize` throws in Spring Security 6.3+, is a subclass of `AccessDeniedException`, so it reaches `handleAccessDenied` (403).
- **Scope:** it only catches exceptions that reach Spring MVC. That means controllers, the services they call, argument binding and JSON parsing. It does **not** see filters, background threads or schedulers (9.5).

## 9.2 The complete map: exception → HTTP response

| Exception | HTTP | `errorCode` | Message shown | Main throwers |
|---|---|---|---|---|
| `EntityNotFoundException` (custom) | **404** | from the exception, e.g. `TEMPLE_NOT_FOUND`, `INVALID_CREDENTIALS` | exception message | 31 files. `.orElseThrow(...)` everywhere, **plus bad login** |
| `DistrictScopeViolationException` | **404** + 0–20 ms delay | *(none)* | fixed: "The requested resource was not found." | `JurisdictionGuard.assertDistrictScope` |
| `IllegalStatusTransitionException` | 409 | `ILLEGAL_STATUS_TRANSITION` | message | Governance ("physical verification has FAILED") |
| `InvalidStateTransitionException` | 409 | `INVALID_STATE_TRANSITION` | message | **nobody**: unused |
| `DeclarationImmutableException` | 409 | `DECLARATION_IMMUTABLE` | message | `DeclarationServiceImpl.update` (not DRAFT/REJECTED) |
| `DeclarationAlreadyExistsException` | 409 | `DECLARATION_ALREADY_EXISTS` | message | `DeclarationServiceImpl.create` |
| `ImmutableResourceException` | 409 | `IMMUTABLE_RESOURCE` | message | governance edit guard |
| `DuplicateResourceException` | 409 | `DUPLICATE_RESOURCE` | message | temple registration no., trust per temple, … |
| `WorkflowException` | 409 | `WORKFLOW_TRANSITION_ERROR` | message | `WorkflowEngineImpl`, `ClarificationEngineImpl` |
| `OptimisticLockingFailureException` (Spring) | 409 | `OPTIMISTIC_LOCK_CONFLICT` | "modified by another request…" | Hibernate `@Version` conflict at flush/commit |
| `AcknowledgementNotAvailableException` | 422 | `ACKNOWLEDGEMENT_NOT_AVAILABLE` | message | ack download before approval |
| `ClarificationLimitExceededException` | 422 | `TRM-DECL-009` | message | > 3 clarification rounds |
| `IllegalStateException` (**JDK class**) | **422** | `ILLEGAL_STATE` | **exception message** | 46 `throw` sites: wrong current password, reset link expired, password mismatch… |
| `JurisdictionAccessDeniedException` | 403 | `JURISDICTION_DENIED` | message (names the districts) | `OwnershipGuard`, `JurisdictionGuard.assertSameDistrict` |
| `AccessDeniedException` (Spring) | 403 | `ACCESS_DENIED` | fixed | `@PreAuthorize`, `AccessGuard`, DACVM aspect |
| `SecurityException` (JDK) | 401 | `AUTH_FAILED` | fixed "Please log in again." | `TokenRevocationGuard`, `AuthServiceImpl.refresh` |
| `MfaVerificationException` | 401 | `MFA_VERIFICATION_FAILED` | message | `MfaServiceImpl` (which is never called, Chapter 3) |
| `AccountLockedException` | 423 | `ACCOUNT_LOCKED` | message | `AuthServiceImpl.login` |
| `RateLimitExceededException` | 429 + `Retry-After` | `TRM-RATE-LIMIT` | message | password-reset throttle, DC rate limits |
| `ExportQueueFullException` | 503 + `Retry-After` | `TRM-EXPORT-QUEUE-FULL` | message | export executor full (`AbortPolicy`, Chapter 2) |
| `AcknowledgementNumberConflictException` | 500 | `TRM-ACK-001` | "Please retry." | the **unused** `AcknowledgementNumberGenerator` |
| `FileValidationException` | 400 | `FILE_VALIDATION_ERROR` | message | upload type/size checks |
| `MaxUploadSizeExceededException` (Spring) | 400 | `FILE_TOO_LARGE` | "limit of **5 MB**" | multipart limit |
| `AadhaarVerificationException` | 400 | `AADHAAR_VERIFICATION_FAILED` | message | `AadhaarServiceImpl` |
| `MethodArgumentNotValidException` (Spring) | 400 | `VALIDATION_ERROR` | "Request validation failed." + `errors[]` | `@Valid @RequestBody` failures |
| `HttpMessageNotReadableException` (Spring) | 400 | `INVALID_REQUEST` | "Invalid request body: " + **parser's message** | malformed JSON, bad enum value, bad date |
| `AsyncRequestTimeoutException` / `AsyncRequestNotUsableException` | *(no body)* | — | — | SSE streams timing out or client gone |
| **anything else** → `Exception` | **500** | `INTERNAL_ERROR` | fixed "Please contact support." (full stack trace logged) | see 9.6 |

About the `MaxUploadSizeExceededException` message: it says "5 MB", but I found no `spring.servlet.multipart.max-file-size` in the YAML, so Spring Boot's default of **1 MB per file** applies. A 2 MB photo is rejected with a message claiming the limit is 5 MB.

## 9.3 What a validation error really looks like (a correction to Chapter 8)

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    List<String> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)          // ← only the message text, no field name
            .toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.validationError("Request validation failed.", errors));
}
```
In Chapter 8 I wrote that errors come back as `"panNumber: Invalid PAN format…"` and `vehicles[2].year: …`. **That was wrong**: the handler keeps only `getDefaultMessage()`. The real response is:
```json
{ "success": false, "message": "Request validation failed.", "errorCode": "VALIDATION_ERROR",
  "errors": [ "Invalid PAN format (e.g. ABCDE1234F)", "Year must be after 1950" ],
  "timestamp": "...", "requestId": "..." }
```
Two consequences:
1. **The frontend can't highlight which field failed.** It gets only text. With nested lists it can't tell *which* vehicle has the bad year.
2. `getFieldErrors()` skips **class-level** errors (constraints placed on the DTO class itself). None exist today, but a future one would produce an empty `errors` list.

This is also why DTOs here write explicit `message = "..."` values: those strings are the only thing the user sees.

## 9.4 Four handlers worth studying

**1. The anti-leak 404.**
```java
@ExceptionHandler(DistrictScopeViolationException.class)
public ResponseEntity<ApiResponse<Void>> handleDistrictScopeViolation(DistrictScopeViolationException ex) {
    applyTimingDelay();                                      // Thread.sleep(random 0..20 ms)
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("The requested resource was not found.", null));
}
```
The Javadoc explains that the same body as a real "not found", plus random jitter, stops an attacker from telling "doesn't exist" apart from "exists in another district", whether by message *or* by response time. It's a thoughtful touch. It does block a Tomcat thread for up to 20 ms.

**2. SSE handlers return `void`.** Once a response is an event stream (`text/event-stream`), Jackson can't write JSON into it. So these handlers only log at debug level and write nothing. The comments say exactly this.

**3. The catch-all hides internals.**
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
    log.error("Unexpected error: {}", ex.getMessage(), ex);   // full stack trace in logs (with requestId)
    return ResponseEntity.status(500).body(ApiResponse.error("An unexpected error occurred. Please contact support.", "INTERNAL_ERROR"));
}
```
The user sees a generic message plus the `requestId`. Support searches logs by that `requestId` (MDC, Chapter 2). This is the right pattern.

**4. Two handlers that *do* leak internals.**
- `HttpMessageNotReadableException` returns `"Invalid request body: " + ex.getMostSpecificCause().getMessage()`. For a bad enum, Jackson's message includes Java class names, such as `Cannot deserialize value of type com.templeregistry.entity.workflow.WorkflowAction from String "APROVE"…`. That's harmless but reveals package structure.
- `IllegalStateException` → 422 with **its message**. Because this is a *JDK* type used by libraries too, any `IllegalStateException` from Spring, Hibernate or Jackson internals becomes a 422 carrying an internal message. Our own code also throws some that are really 500-type bugs, such as `"Authenticated principal is not a ScopeHelper.Claims instance."` in `JurisdictionGuard` and `"Non-SUPER_ADMIN principal [role=…] has null districtId — corrupted JWT"`. Those reach the user as 422 with internal detail.

## 9.5 Exceptions outside the controller world

`GlobalExceptionHandler` covers only request threads inside Spring MVC. Everywhere else behaves differently:

| Where the exception happens | Who handles it | What the client or system sees |
|---|---|---|
| **`JwtAuthenticationFilter`** (bad token) | the filter's own `try/catch` | nothing yet: the request continues unauthenticated (Chapter 3) |
| **Filter: password-change gate** | the filter writes JSON by hand | 403 `PASSWORD_CHANGE_REQUIRED`, no `timestamp`/`requestId` |
| **Spring Security: not authenticated** | `ExceptionTranslationFilter` → `HttpStatusEntryPoint` | **401, empty body** (not `ApiResponse`) |
| **`@Async` void methods** (`AuditServiceImpl`, `EmailServiceImpl`, `TempleSearchSummaryServiceImpl.refresh`, …) | Spring's default `SimpleAsyncUncaughtExceptionHandler`. I found **no custom** `AsyncConfigurer` / `AsyncUncaughtExceptionHandler` | logged at ERROR; the caller never knows. A failed audit write or search-summary refresh is **silent** |
| **`@Scheduled` jobs** | Spring's default: log and continue | logged; the next run still happens. `NotificationRouter.retryFailed` also catches per item, increments `retryCount`, and stops after 3 |
| **`@TransactionalEventListener(AFTER_COMMIT)`** (`GovernanceDomainEventTimelineListener`) | its own `try/catch (Exception)` | a warning log. Its comment: *"Timeline failures must NEVER propagate. The workflow transaction already committed."* Correct, because an exception here would otherwise bubble into the already-committed request |
| **`afterCommit` callback** (`TempleSearchSummaryServiceImpl.scheduleRefresh`) | calls the `@Async` refresh, so any failure is on another thread | logged only |
| **Startup** (`EmailStartupValidator` with `fail-fast`, bad keys, …) | Spring Boot | the app doesn't start (Chapter 2 §2.1) |
| **Response serialization** (Jackson fails *after* the controller returned) | goes back into the advice → catch-all | 500. For the clarification endpoints this happens **after commit** (Chapter 8 §8.5) |

## 9.6 Common exceptions that fall into the 500 catch-all

None of these has its own handler, so each becomes **500 `INTERNAL_ERROR`**. I don't see Spring's `ResponseEntityExceptionHandler` being extended, which would otherwise have mapped most of them.

| Exception | Realistic trigger in this app | Should be |
|---|---|---|
| `NoResourceFoundException` (Spring 6.1+) | **any unknown URL**, e.g. a typo `GET /api/v1/templs`. Likely caught by `@ExceptionHandler(Exception.class)` | 404 |
| `MethodArgumentTypeMismatchException` | `GET /api/v1/temples/abc` (`Long id` can't parse) | 400 |
| `MissingServletRequestParameterException` | a required `@RequestParam` is absent | 400 |
| `HttpRequestMethodNotSupportedException` | `PUT` on a GET-only URL | 405 |
| `HttpMediaTypeNotSupportedException` | JSON sent to a multipart endpoint | 415 |
| `MissingServletRequestPartException` | upload without the `files` part | 400 |
| `DataIntegrityViolationException` | duplicate key, e.g. re-creating a **soft-deleted username** (Chapter 4 §4.3) or an FK violation | 409 |
| `jakarta.persistence.EntityNotFoundException` (*JPA's*, not ours) | lazy-loading a soft-deleted parent, e.g. `district.getCity()` after the city was soft-deleted | 404 / data alert |
| **`jakarta.persistence.OptimisticLockException`** | **stale `expectedVersion` in `POST /api/v2/workflow/{id}/action`**, see below | 409 |
| `IllegalArgumentException` | negative `page` → `PageRequest.of(-1, …)`; 35 `throw new IllegalArgumentException / UnsupportedOperationException / RuntimeException` sites in `service/` | 400 / 500 |
| `UnsupportedOperationException` | `DeclarationServiceImpl.submit` if ever reached | 410 / 500 |

**The workflow version check.** In `WorkflowEngineImpl.execute`, Step 8:
```java
if (request.getExpectedVersion() != null && !request.getExpectedVersion().equals(instance.getLockVersion())) {
    throw new OptimisticLockException("Stale version: expected=" + … );   // import jakarta.persistence.OptimisticLockException
}
```
This is the **normal, expected** conflict: two people acting on the same item. The handler catches Spring's `OptimisticLockingFailureException`, a *different class hierarchy*. Spring converts JPA exceptions into its own types only for calls made through `@Repository` proxies (and at commit time). An exception thrown **directly in service code** reaches the advice unchanged. So the most likely result is **500 "An unexpected error occurred"** where the UI expects 409 "refresh and try again". A real version conflict detected by Hibernate at commit *is* translated and gives 409. So the same situation can yield 409 or 500 depending on who notices first.

## 9.7 Status codes that don't match what happened

| What happened | Status the client gets | Expected |
|---|---|---|
| Wrong password / unknown user at login | 404 (`EntityNotFoundException("Invalid credentials.")`) | 401 |
| Workflow instance not found (`ClarificationEngineImpl`) | 409 (`WorkflowException`) | 404 |
| Wrong role for a workflow action (`WorkflowEngineImpl` Step 4) | 409 | 403 |
| DC acts on another district's instance (Step 5) | 409 **with both district ids in the message** | 404 (to match the anti-leak policy used elsewhere) |
| TA acts on a temple they don't own (Step 6) | 409 | 403 |
| Wrong *current* password on change | 422 (`IllegalStateException`) | 400 / 401 |
| Temporary password not yet changed | 403 hand-written JSON | fine, but the shape differs |

Step 5 matters most. The DC module works hard to return an identical 404 for "other district" (9.4, handler 1), while the v2 engine returns 409 with `"DC district 3 does not match instance district 7"`, disclosing exactly what the other design hides.

## 9.8 Exceptions and rollback, side by side

| Exception thrown inside a `@Transactional` method | DB changes in that transaction | Notes |
|---|---|---|
| any custom exception / `IllegalStateException` / `IllegalArgumentException` | **rolled back** | the default rule: `RuntimeException` → rollback |
| thrown **after** commit (Jackson serialization, a sync after-commit listener) | **kept** | the client sees an error for something that succeeded (clarification POSTs) |
| thrown in a `REQUIRES_NEW` inner method (audit, notification dispatch) | inner rolled back; outer continues if the caller catches | `@Async` ones run on another thread, so the outer never even sees it |
| a checked `Exception` | **committed** (by default) | none of the custom exceptions are checked, so this doesn't arise here |
| any, in `login()` | the failed-attempt counter update is rolled back | Chapter 3 Defect #1 |

There are **no** `noRollbackFor` / `rollbackFor` attributes anywhere (checked in Chapter 6), so every case follows the default rule.

## 9.9 Two complete traces

**A. A validation failure**
```text
POST /api/v1/temples/42/trusts  { "trustName": "", "panNumber": "abc", ... }
 → Jackson → CreateTrustRequest
 → @Valid fails (2 errors) → MethodArgumentNotValidException   (controller body never runs, no transaction opened)
 → handleValidation → 400
   { "success": false, "message": "Request validation failed.", "errorCode": "VALIDATION_ERROR",
     "errors": ["Trust name is required", "Invalid PAN format (e.g. ABCDE1234F)"], ... }
```

**B. A business-rule failure after writes**
```text
POST /api/v1/temples/42/trusts  (valid body, but temple 42 already has a trust)
 → proxy: @PreAuthorize(CAN_SUBMIT) ✓ → BEGIN
 → TrustServiceImpl.create: load temple ✓ · ownership ✓ · district scope ✓ · validateTrustRequest ✓
 → trustRepository.existsByTempleIdAndDeletedFalse(42) = true
 → throw new DuplicateResourceException("A trust is already registered for this temple.")
 → proxy: ROLLBACK (nothing written yet)
 → handleDuplicate → 409 { "errorCode": "DUPLICATE_RESOURCE", "message": "A trust is already registered for this temple." }
```

## 9.10 The five questions, for `GlobalExceptionHandler`

```text
Who calls me?   Spring MVC's HandlerExceptionResolver — whenever an exception escapes a controller
                (including from services, argument binding, JSON parsing, response writing)
      ↓
[GlobalExceptionHandler]   (@RestControllerAdvice)
      ↓
Who do I call?  ApiResponse.error / validationError · SLF4J logger · Thread.sleep (scope violation)

INPUT   any Throwable from the MVC layer
  ↓     pick the closest @ExceptionHandler by type · choose status + errorCode · log (warn / error)
OUTPUT  ResponseEntity<ApiResponse<Void>> (success=false, message, errorCode, errors?, timestamp, requestId)
        — or nothing (SSE handlers)
NOT MY JOB  filters, @Async threads, schedulers, event listeners (9.5)
```

## 9.11 Findings from this chapter

| Severity | Finding | Where |
|---|---|---|
| 🟠 | **Stale-version workflow actions likely return 500, not 409**: `jakarta.persistence.OptimisticLockException` thrown from service code isn't translated to Spring's type | `WorkflowEngineImpl.execute` Step 8 |
| 🟠 | **v2 engine leaks district ids with 409** where the DC module deliberately returns an identical 404 | `WorkflowEngineImpl` Steps 5–6 |
| 🟡 | **Unknown URLs and common client errors → 500** (`NoResourceFoundException`, type mismatch, 405/415, missing params, `DataIntegrityViolationException`) | no handlers; no `ResponseEntityExceptionHandler` |
| 🟡 | Validation errors drop field names; nested-list errors can't be located by the UI | `handleValidation` |
| 🟡 | `IllegalStateException` → 422 with raw message, both for user errors and internal/corrupt-state errors (and library ones) | `handleIllegalState` |
| 🟡 | Upload-limit message says 5 MB; the effective default is 1 MB | `handleMaxUploadSize` + no multipart config |
| ⚪ | Failed `@Async` work (audit, search refresh, email) only logs; no custom async error handler | `AsyncConfig` |
| ⚪ | Unused `InvalidStateTransitionException`; `AcknowledgementNumberConflictException` only from unused code; 401 bodies empty; the 403 password-gate body differs in shape | — |

## 9.12 Adding a new error: the recipe for this codebase

1. Create `exception/XException extends RuntimeException`, with a clear message and (if useful) data fields, like `retryAfterSeconds` in `RateLimitExceededException`.
2. Add a handler in `GlobalExceptionHandler` with the **right status**. Use a **stable `errorCode`**: the frontend switches on it, not on the text.
3. Throw it from the **service** (never build `ResponseEntity` errors in controllers).
4. Don't reuse JDK types (`IllegalStateException`, `IllegalArgumentException`, `SecurityException`) for business errors. They're caught broadly, and libraries throw them too.
5. For anything scoped to a district, prefer `DistrictScopeViolationException` (identical 404) over messages that name districts.
6. If the error can happen after commit, or on another thread, remember the advice won't see it (9.5).

---

**Next: Chapter 10 — Complete Business Flows.** This is the chapter the earlier ones were building toward. I'll trace, from click to database to notification:
1. **Asset declaration lifecycle**: create → submit → DC review → clarification → site visit → approve with acknowledgement. It covers `TransitionRuleRegistry`, `WorkflowEngineImpl.execute` step by step, the `WorkflowPolicy` beans, dual-write, idempotency, snapshots, and `GovernanceDomainEvent`.
2. **The notification pipeline**: event → `NotificationRouter` → rules → outbox → in-app, email and SSE.
3. **Temple profile staging → DC approval.**
4. **Overdue scheduling.**

Along the way I'll settle the question of which of the three workflow APIs is authoritative for each module.

Say **"continue"** when ready.

# CHAPTER 3 — Security & Authentication

Security answers two questions on every request:
- **Authentication:** *"Who are you?"* In this app, the answer is a signed JWT.
- **Authorization:** *"Are you allowed to do this?"* In this app, the answer comes from roles, district, temple ownership and access type.

I read every class in `security/`, all of `AuthController`, `AuthServiceImpl`, `JwtServiceImpl`, `UserProfileServiceImpl.changeOwnPassword`, the `User` entity, and the security parts of `GlobalExceptionHandler`. Along the way I found **several real security defects**. I explain each where it appears, and collect them at the end. As you asked, nothing was changed.

## 3.0 The big picture: two phases

```text
PHASE A — LOGIN (once)                          PHASE B — EVERY LATER REQUEST
─────────────────────────                       ──────────────────────────────
POST /api/v1/auth/login                          GET /api/v1/temples/42
{username, password}                             Authorization: Bearer eyJ...   (or access_token cookie)
      │                                                │
AuthController.login                             JwtAuthenticationFilter
      │                                                │  ScopeHelper.parseFull(token)  ← verify RS256 signature + expiry
AuthServiceImpl.login                                  │  mustChangePassword gate
      │  UserRepository.findByUsername                 │  SecurityContextHolder ← Claims + ROLE_xxx
      │  lock check                                    ▼
      │  BCrypt passwordEncoder.matches          AuthorizationFilter (SecurityConfig URL rules)
      │  MFA? → temp token                             ▼
      │                                          Controller  (class-level @PreAuthorize)
issueTokenPair                                         ▼
      │  JwtServiceImpl.generateAccessToken      Service proxy (method-level @PreAuthorize)
      │  random refresh token → SHA-256 → DB           ▼
      ▼                                          Service body: JurisdictionGuard / OwnershipGuard / AccessGuard
Set-Cookie + JSON body with tokens                     ▼
                                                 Repository → DB
```

No server session exists (`SessionCreationPolicy.STATELESS`). **Everything the server knows about you comes from the token on each request.**

## 3.1 The cast of security classes

| Class | Phase | Why it exists | Who calls it | Calls |
|---|---|---|---|---|
| `AuthController` | A | HTTP endpoints for login / MFA / refresh / logout / reset. Sets cookies | Browser | `AuthService`, `UserProfileService`, `PolicyEvaluationService` |
| `AuthServiceImpl` | A | Login rules: lockout, password check, token issuing, refresh rotation, reset | `AuthController` | `UserRepository`, `RefreshTokenRepository`, `PasswordEncoder`, `JwtService`, `TokenRevocationGuard`, `EmailService`, `AuditService` |
| `JwtServiceImpl` | A | **Signs** tokens with the **private** key | `AuthServiceImpl`, `RegistrationServiceImpl` | jjwt |
| `ScopeHelper` | B | **Verifies** tokens with the **public** key and extracts `Claims` | `JwtAuthenticationFilter` | jjwt |
| `ScopeHelper.Claims` (record) | B | The "logged-in user" object: `userId, role, districtId, templeId, username, accessType` | Guards, services, `JpaAuditConfig`, `@AuthenticationPrincipal` | — |
| `JwtAuthenticationFilter` | B | Runs on every request. Turns a token into an `Authentication` | Spring Security filter chain | `ScopeHelper` |
| `SecurityConfig` | both | URL rules, stateless mode, 401 entry point, BCrypt | Spring at startup | — |
| `RoleConstants` | B | Role names + `@PreAuthorize` expressions | 307 `@PreAuthorize` usages | — |
| `JurisdictionGuard` | B | DC / DC_STAFF may only touch **their district** | ~60 service call sites | `SecurityContextHolder` |
| `OwnershipGuard` | B | TA may only touch **their temple** | 51 call sites | `SecurityContextHolder` |
| `AccessGuard` | B | TA with `access_type=VIEW` may not write | **4** call sites | `SecurityContextHolder` |
| `TokenRevocationGuard` | A | Refuse revoked / expired refresh tokens | `AuthServiceImpl.refresh` | `RefreshTokenRepository` |
| `DacvmGuard` + `PolicyEnforcementAspect` | B | Admin-editable DB policies | **Nobody**, see 3.7 | `PolicyEvaluationService` |
| `UserDetailsServiceImpl` | — | Spring's standard "load user by username" | **Not used by login**, see 3.2 | `UserRepository` |

---

## 3.2 Phase A: Login, step by step

### The request
```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "ta_meenakshi", "password": "S3cret!pass" }
```
No token is needed. `/api/v1/auth/**` is in `PUBLIC_PATHS`, so `.permitAll()` applies.

### Controller: `AuthController.login`
```java
@PostMapping("/login")
public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletResponse httpResponse) {
    Object result = authService.login(request);
    if (result instanceof AuthTokenResponse tokens) {
        setAuthCookies(httpResponse, tokens);
        return ResponseEntity.ok(ApiResponse.success("Authentication successful.", tokens));
    }
    return ResponseEntity.ok(ApiResponse.success("MFA challenge issued.", result));
}
```
- `@RequestBody` → Jackson converts the JSON into a `LoginRequest` object.
- `@Valid` → *before* the method body runs, Spring checks the annotations on `LoginRequest`:
  ```java
  @NotBlank @Size(max = 100)      private String username;
  @NotBlank @Size(min = 8, max = 128) private String password;
  ```
  If a check fails, the method never runs. Spring throws `MethodArgumentNotValidException`, which `GlobalExceptionHandler` turns into **400 VALIDATION_ERROR**.
- `HttpServletResponse httpResponse` → Spring hands you the raw response, so the controller can add `Set-Cookie` headers.
- `Object result`: the service returns **one of two types**. Either an `AuthTokenResponse` (logged in) or an `MfaChallengeResponse` (a second step is needed). `instanceof` pattern matching picks the branch.

### Service: `AuthServiceImpl.login`, line by line
```java
@Override
@Transactional
public Object login(LoginRequest request) {
    User user = userRepository.findByUsername(request.getUsername())
            .orElseThrow(() -> new EntityNotFoundException("Invalid credentials.", "INVALID_CREDENTIALS"));
```
- `@Transactional`: the whole method is **one DB transaction**. Remember this; it matters below.
- `findByUsername` is a derived query: `SELECT * FROM users WHERE username = ? AND is_deleted = false`. The `is_deleted` part comes from `@SQLRestriction` on `User`.
- User not found → `EntityNotFoundException` → **HTTP 404** "Invalid credentials." The message is deliberately vague, so an attacker can't tell whether the username exists. The status code is unusual, though: most APIs return 401 here.

```java
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
        throw new AccountLockedException(user.getLockedUntil().toEpochSecond(ZoneOffset.UTC));
    }
```
- Account temporarily locked → **HTTP 423 LOCKED**.

```java
    if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
        int attempts = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {                 // 5
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES)); // 30
        }
        userRepository.save(user);
        throw new EntityNotFoundException("Invalid credentials.", "INVALID_CREDENTIALS");
    }
```
- **What `passwordEncoder.matches` does.** The DB never stores your password, only a **BCrypt hash** like `$2a$12$abc...`. `matches(plain, hash)` re-hashes the typed password with the salt stored inside the hash and compares the results. The `12` from `SecurityConfig` makes this deliberately slow, about 250 ms.
- **🔴 Defect #1 — account lockout never works.** Follow what happens:
  1. `save(user)` queues `UPDATE users SET failed_login_count = …`.
  2. The very next line **throws a `RuntimeException`** (`EntityNotFoundException extends RuntimeException`).
  3. `@Transactional`'s default rule is to **roll back on any RuntimeException**. So the increment is undone.
  4. `failed_login_count` stays at 0 forever, `locked_until` is never set, and **brute-force protection is effectively off**.

  `AuthServiceImplTest` mocks the repository, so it can't catch a rollback. You'd need an integration test to see it. The usual fixes are `@Transactional(noRollbackFor = EntityNotFoundException.class)`, or writing the counter in a `REQUIRES_NEW` transaction.

```java
    user.setFailedLoginCount(0);
    user.setLockedUntil(null);
    userRepository.save(user);

    if (user.getMfaType() != null && user.getMfaType() != MfaType.NONE) {
        String tempToken = jwtService.generateTempToken(user);        // 5-minute JWT, claim type=TEMP
        return MfaChallengeResponse.builder()
                .mfaRequired(true).challengeType(user.getMfaType().name()).tempToken(tempToken).build();
    }

    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);
    return issueTokenPair(user);
}
```
- **🟠 Defect #2 — deactivated users can still log in.** The `User` entity has `isActive`, but `login()` never checks it, and neither does `refresh()`. A user an admin switched off (`is_active = false`, not soft-deleted) can still sign in.

> **Why `DaoAuthenticationProvider` and `UserDetailsServiceImpl` don't matter here.** `SecurityConfig` wires Spring's standard login machinery (`AuthenticationManager` → `DaoAuthenticationProvider` → `UserDetailsServiceImpl`, which *does* check `disabled(!user.isActive())` and `accountLocked`). But I grepped: **nothing calls `authenticationManager.authenticate(...)`.** `AuthServiceImpl` does its own password check instead. So `UserDetailsServiceImpl` only runs in its unit test. When you read the code, treat `AuthServiceImpl.login` as the **only** real login path.

### Issuing tokens: `issueTokenPair`
```java
private AuthTokenResponse issueTokenPair(User user) {
    String accessToken     = jwtService.generateAccessToken(user);     // signed JWT, 2 h
    String rawRefreshToken = jwtService.generateRefreshToken();        // 64 random hex chars (2 UUIDs)
    String tokenHash       = sha256(rawRefreshToken);

    RefreshToken rt = RefreshToken.builder()
            .user(user).tokenHash(tokenHash)
            .expiresAt(LocalDateTime.now().plusDays(refreshTokenExpiryDays))   // 7 days
            .build();
    refreshTokenRepository.save(rt);                                   // INSERT INTO refresh_tokens

    return AuthTokenResponse.builder()
            .accessToken(accessToken).refreshToken(rawRefreshToken)
            .expiresIn(7200).role(user.getRole().name()).userId(user.getId()).build();
}
```
There are **two tokens** because they have different jobs:

| | Access token | Refresh token |
|---|---|---|
| What it is | A **JWT**: a self-contained signed statement | A **random string** with no meaning of its own |
| Lifetime | 2 h (`app.jwt.access-token-expiry-ms`) | 7 days |
| Stored on server? | **No.** The server just checks the signature | **Yes**, only its **SHA-256 hash**, in `refresh_tokens` |
| Sent on | Every API call | Only `POST /auth/refresh` |
| Can it be revoked? | No (valid until it expires) | Yes (`revoked_at`) |

The refresh token is stored as a hash for the same reason passwords are: if the DB leaks, the stored values can't be replayed.

### What's inside the access token (`JwtServiceImpl.generateAccessToken`)
```java
Jwts.builder()
    .subject(user.getUsername())                               // "sub"
    .claim("userId",     user.getId())
    .claim("role",       user.getRole().name())                // e.g. "TEMPLE_AUTHORITY"
    .claim("districtId", user.getDistrictId())
    .claim("templeId",   user.getTempleId())
    .claim("accessType", ...)                                  // "VIEW" | "EDIT"
    .claim("mustChangePassword", user.isMustChangePassword())
    .issuedAt(...).expiration(now + 7_200_000)
    .signWith(privateKey)                                      // RS256
    .compact();
```
**What a JWT is.** Three Base64 parts: `header.payload.signature`. Anyone can *read* the payload. Only the holder of the **private key** can produce a valid signature, and anyone with the **public key** can *check* it. That split is why `JwtServiceImpl` (signs) and `ScopeHelper` (verifies) are separate classes.

**The design consequence.** Role, district and temple are *baked into the token*. If an admin changes a user's role or district, the user keeps the **old** permissions until the token expires (up to 2 h) or they refresh.

### The response
```http
HTTP/1.1 200
Set-Cookie: access_token=eyJ...;  Path=/api/v1;              Max-Age=7200;   HttpOnly; Secure; SameSite=None
Set-Cookie: refresh_token=9f3c...; Path=/api/v1/auth/refresh; Max-Age=604800; HttpOnly; Secure; SameSite=None
(+ 4 more Set-Cookie headers that clear legacy cookie paths)

{ "success": true, "message": "Authentication successful.",
  "data": { "accessToken": "eyJ...", "refreshToken": "9f3c...", "expiresIn": 7200,
            "role": "TEMPLE_AUTHORITY", "userId": 17 },
  "timestamp": "...", "requestId": "..." }
```
What the cookie attributes mean:
- `HttpOnly` → JavaScript can't read the cookie, which protects it from XSS.
- `Secure` → sent over HTTPS only.
- `SameSite=None` → sent on cross-site requests too. It's needed because the frontend is on Vercel and the API is on another domain.
- `Path=/api/v1/auth/refresh` → the refresh cookie is sent **only** to the refresh endpoint.

**⚠️ The tokens are *also* in the JSON body.** The comment says this is *"for cross-domain setups where cookies won't work"*. It weakens the purpose of `HttpOnly`, because the frontend now holds the token in JavaScript anyway. A `sanitize()` method that strips tokens from the body exists in `AuthController`, but it's **never called**.

A side effect of `SameSite=None` plus CSRF being disabled: the cookie is sent automatically on cross-site requests. CORS blocks *reading* responses from other origins, but it does not stop a malicious page from *sending* simple POSTs. How much this matters depends on whether the frontend uses the header or the cookie. Worth discussing with the team.

---

## 3.3 MFA (the second login step)

If `user.mfaType` is not `NONE`, login returns this instead of tokens:
```json
{ "data": { "mfaRequired": true, "challengeType": "TOTP", "tempToken": "eyJ..." } }
```
The frontend then asks for the 6-digit code and calls:
```http
POST /api/v1/auth/mfa-verify
{ "tempToken": "eyJ...", "mfaCode": "123456" }
```
Here is the whole service method:
```java
public AuthTokenResponse verifyMfa(MfaVerifyRequest request) {
    Claims claims = jwtService.validateAndParse(request.getTempToken());   // signature + expiry only
    String username = claims.getSubject();
    User user = userRepository.findByUsername(username).orElseThrow(...);
    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);
    return issueTokenPair(user);
}
```
**🔴 Defect #3 — MFA is not enforced.**
- `request.getMfaCode()` is **never read**.
- `mfaService` is injected into `AuthServiceImpl`, but I grepped the whole codebase and **`mfaService.` is never called anywhere**. `MfaServiceImpl.verifyTotp` exists and is correct; it's just unused.
- `mfaCode` has `@Size(6..8)` but no `@NotBlank`, so it can be omitted entirely.
- `validateAndParse` doesn't check that the claim `type` equals `"TEMP"`. **Any** valid token signed by this server works, as long as its `sub` is a username.

Result: knowing the password is enough. You get a temp token, send it back with any code (or none), and receive full tokens.

---

## 3.4 Phase B: What happens on every later request

Take `GET /api/v1/temples/42` with `Authorization: Bearer eyJ...`.

### Step 1: `JwtAuthenticationFilter.doFilterInternal`

**Find the token** (`extractBearerToken`), in this order:
1. `Authorization: Bearer <token>` header.
2. The `access_token` cookie.
3. `?token=...` query parameter, **only** if the URL ends with `/stream`. This exists for SSE (`EventSource` in the browser can't set headers). The comment rightly warns that URL tokens appear in logs.

**Then:**
```java
if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
    try {
        ScopeHelper.ParsedToken parsed = scopeHelper.parseFull(token);   // ① verify
        ScopeHelper.Claims claims = parsed.claims();
        MDC.put("userId", ...); MDC.put("role", ...);                    // ② log context

        if (parsed.mustChangePassword() && !isPasswordChangePath(request)) {  // ③ gate
            writePasswordChangeRequired(response);                       //    403 PASSWORD_CHANGE_REQUIRED
            return;                                                      //    request stops here
        }

        var authority = new SimpleGrantedAuthority("ROLE_" + claims.role());  // ④
        var auth = new UsernamePasswordAuthenticationToken(claims, null, List.of(authority));
        SecurityContextHolder.getContext().setAuthentication(auth);      // ⑤
    } catch (Exception ex) {
        log.warn("JWT validation failed ...");                           // ⑥ bad/expired token
    }
}
filterChain.doFilter(request, response);                                // ⑦ continue
```

| # | What happens | Beginner explanation |
|---|---|---|
| ① | `Jwts.parser().verifyWith(publicKey)...parseSignedClaims(token)` | Recomputes the signature with the public key. A tampered token fails. jjwt also rejects expired tokens (`exp` claim) automatically |
| ② | MDC | Adds `userId`/`role` to every log line for this request. They are never removed afterwards (the thread-reuse issue from Chapter 2) |
| ③ | **Forced password change** | An admin-created user with a temporary password (`must_change_password = true`) can only reach `/api/v1/profile/password` and `/api/v1/auth/*`. Everything else gets 403 `PASSWORD_CHANGE_REQUIRED`. This is the *real* gate; hiding frontend screens isn't enough, as the code comment says |
| ④ | `"ROLE_" + role` | Spring's convention. `hasRole('DC_STAFF')` actually checks for an authority named `ROLE_DC_STAFF` |
| ⑤ | `UsernamePasswordAuthenticationToken(claims, null, authorities)` | The 3-argument constructor marks it **authenticated**. The **principal** (the "who") is the `Claims` record itself, not a `User` entity, so **no DB query** happens per request |
| ⑥ | Exception swallowed | An invalid or expired token does **not** error here. The request simply continues **unauthenticated**, and step 2 decides |
| ⑦ | `doFilter` | Hand over to the next filter |

**What is `SecurityContextHolder`?**
> A per-thread storage box. Tomcat handles each request on one thread. Whatever the filter puts in the box can be read by any code running on that same thread: controllers, services, guards, `JpaAuditConfig`. That's how `JurisdictionGuard` knows who you are without the controller passing it along.
>
> Because it is **per thread**, an `@Async` method runs on *another* thread and does **not** see it by default. That's why `JpaAuditConfig` falls back to user `0` for background jobs.

**🟠 Defect #4 (likely) — the filter accepts any token type.** `ScopeHelper.parseFull` never checks the `type` claim. Two other kinds of token are signed with the same key:
- the MFA temp token (`type=TEMP`, no `role` claim)
- the registration token (`sub="registration"`)

For the temp token, `claims.role()` is `null`, so the authority becomes `ROLE_null`, and the request counts as **authenticated**. The guards only restrict *DC* and *TA* roles, so a `null` role passes them. For example, `TempleServiceImpl.getById` requires only `isAuthenticated()`, calls `jurisdictionGuard.assertSameDistrict` (no-op for a null role) and `ownershipGuard.assertOwnsTemple` (no-op for a null role). So a temp token would read **any temple's full record**. I haven't run it, but the code path is clear.

### Step 2: URL-level authorization (`SecurityConfig`)
```java
.requestMatchers(PUBLIC_PATHS).permitAll()        // /api/v1/auth/**, /api/v1/geo/**, swagger, actuator health/info, /error
.requestMatchers(GET, "/api/v1/temples").permitAll()                   // public search (exact path)
.requestMatchers(GET, "/api/v1/temples/*/profile-photo/serve").permitAll()
.requestMatchers(GET, "/api/v1/temples/*/photos/*/serve").permitAll()
.anyRequest().authenticated()
```
`GET /api/v1/temples/42` matches none of the public rules, so it needs `authenticated()`.
- If the filter set an Authentication → continue.
- If not → `ExceptionTranslationFilter` calls `HttpStatusEntryPoint(401)` → **HTTP 401 with an empty body.** It is *not* an `ApiResponse` JSON. The frontend must handle a bare 401.

The comment `// exact path only (NOT wildcard, to prevent PII exposure via /{id})` shows why `/api/v1/temples` is public but `/api/v1/temples/42` is not.

### Step 3: Controller-level `@PreAuthorize`
21 controllers put `@PreAuthorize` on the **class**, so it applies to every method:

| Controller(s) | Rule |
|---|---|
| `AdminController`, `AdminTempleController`, `AccessControlController`, `SystemConfigController` | `ADMIN_ONLY` |
| `DcDashboardController`, `DcProfileController`, `DcBoardMemberController` | `IS_DC_ROLE` (SA, DC, DC_STAFF) |
| `DcComplianceController` | `CAN_ACT_DC` (SA, DC) |
| `DcTempleController` | `CAN_READ_TEMPLES` (all six roles) |
| `DcDeclarationController`, `DcEmployeeController`, `DcExportController`, `DcNotificationController`, `ExportController`, `ObservationController`, `AuditorController`, `ViewerDashboardController` | `CAN_READ_ALL` (everyone except TA) |
| `TaDashboardController` | `TEMPLE_AUTHORITY_ONLY` |
| `DocumentController` | `CAN_READ_ALL or TEMPLE_AUTHORITY_ONLY` |
| `NotificationController`, `NotificationPreferenceController` | `isAuthenticated()` |

`TempleController`, `TrustController`, `DeclarationController`, `GeoController`, the governance controllers and others have **no** class-level rule. They rely on method-level rules in the controller or the service.

### Step 4: Service-level `@PreAuthorize` (the main line of defence)
**What `@PreAuthorize` is.** A rule written in SpEL (Spring Expression Language) that the method-security proxy evaluates **before** the method body. Its constants live in `RoleConstants`:
```java
public static final String CAN_ACT_DC = "hasAnyRole('SUPER_ADMIN', 'DISTRICT_COLLECTOR')";
```
`@PreAuthorize(RoleConstants.CAN_ACT_DC)` is just `@PreAuthorize("hasAnyRole('SUPER_ADMIN','DISTRICT_COLLECTOR')")`. It is a Java compile-time constant, so a typo in a role name is caught once, in one place. `RoleConstantsTest` exists for that.

If the check fails → `AccessDeniedException` → `GlobalExceptionHandler.handleAccessDenied` → **403** `{"errorCode":"ACCESS_DENIED","message":"You do not have permission to perform this action."}`.

> **The Chapter 1 question, answered:** `POST /api/v1/geo/districts` with no token. The URL is `permitAll`, so it reaches the controller. `@PreAuthorize(ADMIN_ONLY)` fails for the anonymous user and throws `AccessDeniedException`. Because it's thrown **inside** Spring MVC, `GlobalExceptionHandler` catches it before Spring Security's entry point can. Result: **403 ACCESS_DENIED** (not 401). Secure, but the status code is less precise.

### Step 5: Data-level guards inside the service
Roles alone can't express *"a DC can approve, but only temples in **their** district"*. That's what the guards do. Here is the real code from `TempleServiceImpl.getById`:
```java
@Transactional(readOnly = true)
@PreAuthorize("isAuthenticated()")                          // step 4: any logged-in user
public TempleResponse getById(Long id) {
    Temple temple = findOrThrow(id);                         // SELECT temple → 404 if missing
    jurisdictionGuard.assertSameDistrict(temple.getDistrictId()); // step 5a: DC/DC_STAFF → same district?
    ownershipGuard.assertOwnsTemple(id);                     // step 5b: TA → own temple?
    ...
```
Notice the order: **load first, then check.** The guard needs the temple's district, which only the DB knows.

---

## 3.5 The guards in detail

### `JurisdictionGuard` has three methods with different behaviour

| Method | Who is restricted | On mismatch | Uses |
|---|---|---|---|
| `assertSameDistrict(Long districtId)` | DC, DC_STAFF | `JurisdictionAccessDeniedException` → **403** | 8 (older modules, e.g. `TempleServiceImpl`) |
| `enforceDistrictId(Long requested)` | DC, DC_STAFF | Doesn't throw. **Replaces** the requested district with the JWT one ("JWT claim always wins") | 3 (search/list filters) |
| `assertDistrictScope(Temple, Claims)` | DC, DC_STAFF, **AUDITOR** (SA, TA and VIEWER skip it) | `DistrictScopeViolationException` → **404** | 49 (DC module) |

`assertDistrictScope` is the thorough one:
```java
Hobli hobli = temple.getHobli();                // temple → hobli → taluk → district
if (hobli != null) {
    Taluk taluk = hobli.getTaluk();       if (taluk == null)    throw GEO_INCOMPLETE (404)
    District district = taluk.getDistrict(); if (district == null) throw GEO_INCOMPLETE (404)
    templeDistrictId = district.getId();
} else {
    templeDistrictId = temple.getDistrictId();  // fallback for auto-created temples
}
if (!principalDistrictId.equals(templeDistrictId)) throw new DistrictScopeViolationException(); // 404
```
**Why 404 and not 403?** The comment says *"R7 — mismatch → HTTP 404 (never 403) to prevent district existence leakage."* A 403 would tell a DC from Mysuru *"temple 42 exists, you just can't see it"*. A 404 reveals nothing.

**The inconsistency to be aware of:** AUDITOR is district-scoped by `assertDistrictScope` but **not** by `assertSameDistrict`. So an auditor's access depends on which module's code path is used. Whether auditors should be statewide is a business question; ask the team.

### `OwnershipGuard.assertOwnsTemple(templeId)`
Only for `TEMPLE_AUTHORITY`. It throws 403 unless `templeId == claims.templeId()`. It **fails closed**: if either id is null, it denies. Other roles pass straight through.

### `AccessGuard.assertCanEdit()`
Only for a TA whose token has `accessType = "VIEW"`. It throws `AccessDeniedException` → 403. It's called from only **4 places**, while TA-reachable write methods are far more numerous (trusts, board members, declarations, documents, staging…). A VIEW-only TA may therefore be able to write through paths that don't call it. It's worth auditing which write methods skip `assertCanEdit()`.

### Anonymous callers
All three guards start with `if (claims == null) return;`. The reasoning in the comments: *"anonymous users only reach read endpoints."* That holds only as long as `SecurityConfig` and `@PreAuthorize` keep anonymous callers away from writes. The guards themselves don't enforce it.

---

## 3.6 Refresh, logout, reset, change password

### Refresh: `POST /api/v1/auth/refresh`
```text
refresh_token cookie (or body {"refreshToken": ...})
   → sha256(raw)
   → TokenRevocationGuard.assertNotRevoked(hash)   not found / revoked / expired → SecurityException → 401 AUTH_FAILED
   → storedToken.setRevokedAt(now)                  ROTATION: the old refresh token is now dead
   → issueTokenPair(storedToken.getUser())          new access (with FRESH role/district claims) + new refresh
```
Rotation means each refresh token works **once**. A stolen one is only useful until the real user refreshes first.

Two gaps:
- The user's `isActive` / lock state is **not** re-checked on refresh.
- Reuse of an already-revoked token is logged (`"Revoked refresh token used by user"`) but doesn't revoke the user's *other* tokens. That is a common hardening step, but not required.

### Logout: `POST /api/v1/auth/logout`
- Revokes the refresh token (if the cookie is present) and clears the cookies.
- The **access token stays valid until it expires (≤ 2 h)**. That's inherent to stateless JWT; there is no deny-list.
- If the client uses only the body/header token and no cookie, the server revokes nothing.

### Forgot password: `POST /auth/password-reset-req` → email → `POST /auth/password-reset`
This is the flow your current branch (`feature/forget-password`) touches.

**1. `requestPasswordReset`**
- A Caffeine counter allows **3 requests per email per 15 minutes**, otherwise 429.
  - The limit is applied *before* the user lookup, so being throttled doesn't reveal whether the address exists.
  - The code has an honest `ponytail:` note: the counter is per-instance only.
- It generates a 32-byte `SecureRandom` token and stores only its **SHA-256** hash, with a 30-minute expiry.
- It emails `baseUrl + "/reset-password?token=" + raw`.
- It **always returns success**, so nobody can probe which emails are registered.

**2. `confirmPasswordReset`**
- Looks the user up by the token hash.
- Checks expiry. An expired token is cleared (so it can't be replayed), then 422.
- Sets the new BCrypt hash, clears the token (single use), sets `mustChangePassword = false`, and unlocks the account.
- **Revokes all refresh tokens** and writes an audit entry.
- Password mismatch or an invalid link throws `IllegalStateException` → **422**.

### Change own password: `PATCH /api/v1/profile/password`
In `UserProfileServiceImpl.changeOwnPassword` (`@PreAuthorize("isAuthenticated()")`):
1. Verify the current password (wrong → 422).
2. Check `new == confirm`.
3. Check the new password differs from the old one.
4. Save the hash, clear `mustChangePassword` and any pending reset link.
5. **Revoke all refresh tokens**, and write an audit entry.

One subtlety of the forced-change flow: after a successful change, the user's **current access token still carries `mustChangePassword=true`**, and their refresh tokens were just revoked. So the frontend has to send them back through **login** to get a clean token. Keep this in mind for the `ChangePasswordForm` you're editing.

---

## 3.7 DACVM: the access-control engine that isn't switched on

The code has a full, admin-managed permissions system:
- **Tables:** `access_control_policies`, `access_control_field_masks`, `access_control_audit_log` (entities in `entity/accesscontrol/`)
- **Admin API:** `AccessControlController` → `PolicyManagementServiceImpl`
- **Evaluator:** `PolicyEvaluationServiceImpl.isAllowed(targetKey, subjectType, subjectValue)`. It's `@Cacheable` in the `dacvmPolicies` Caffeine cache. SUPER_ADMIN is always allowed, USER-level DENY is checked first, then ROLE-level.
- **Enforcement:** `@DacvmGuard("some.key")` on a service method → `PolicyEnforcementAspect` (`@Around`) → deny with 403. It fails closed if evaluation errors.

**But I found zero methods annotated with `@DacvmGuard`,** and the only other caller of `PolicyEvaluationService` is `GET /api/v1/auth/me/permissions` (`getEffectivePermissions`). In practice:
- DACVM policies **only affect what the frontend chooses to show or hide.**
- The backend **does not enforce them.** An admin who "denies" a feature in the Access Control screen doesn't block the API.

This is a very important thing to know before trusting that screen.

---

## 3.8 What the client receives when security says no

| Situation | Who decides | HTTP | Body |
|---|---|---|---|
| No / bad / expired token on a protected URL | `HttpStatusEntryPoint` | **401** | *empty* |
| Wrong username or password | `AuthServiceImpl` → `EntityNotFoundException` | **404** | `INVALID_CREDENTIALS` |
| Account locked | `AccountLockedException` | **423** | (can't actually happen, Defect #1) |
| Refresh token missing | `AuthController` | 401 | `UNAUTHORIZED` |
| Refresh token revoked / expired / unknown | `TokenRevocationGuard` → `SecurityException` | **401** | `AUTH_FAILED` |
| Temporary password not yet changed | `JwtAuthenticationFilter` | **403** | `PASSWORD_CHANGE_REQUIRED` (hand-written JSON, no `requestId`) |
| Role not allowed (`@PreAuthorize`) | method security → `AccessDeniedException` | **403** | `ACCESS_DENIED` |
| TA → another temple; DC → other district (`assertSameDistrict`) | `JurisdictionAccessDeniedException` | **403** | its message |
| DC module district mismatch (`assertDistrictScope`) | `DistrictScopeViolationException` | **404** | fixed "not found" message |
| VIEW-only TA writes (where guarded) | `AccessGuard` → `AccessDeniedException` | 403 | `ACCESS_DENIED` |
| Reset-request throttled | `RateLimitExceededException` | **429** | + retry info |
| Validation failure on a login / reset DTO | `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` + list |

## 3.9 The five questions, for the two central classes

```text
Who calls me?   Spring Security filter chain (every HTTP request)
      ↓
[JwtAuthenticationFilter]
      ↓
Who do I call?  ScopeHelper.parseFull → SecurityContextHolder.setAuthentication

INPUT   HttpServletRequest (header / cookie / ?token)
  ↓     verify signature+expiry, password-change gate, build ROLE_ authority
OUTPUT  SecurityContext holding Claims  — or nothing (anonymous) — or a 403 response
```
```text
Who calls me?   AuthController.login / verifyMfa / refresh / logout / password-reset*
      ↓
[AuthServiceImpl]
      ↓
Who do I call?  UserRepository, PasswordEncoder, JwtService, RefreshTokenRepository,
                TokenRevocationGuard, EmailService, AuditService

INPUT   LoginRequest / MfaVerifyRequest / raw refresh token / reset DTOs
  ↓     lockout, BCrypt, MFA branch, token issue + rotation, reset-token lifecycle
OUTPUT  AuthTokenResponse | MfaChallengeResponse | void — or exceptions (404/423/401/422/429)
```

## 3.10 Security findings from this chapter, ranked

| # | Severity | Finding | Where |
|---|---|---|---|
| 3 | 🔴 High | **MFA is never checked.** `mfaCode` is ignored and `MfaService` is never called | `AuthServiceImpl.verifyMfa` |
| 1 | 🔴 High | **Lockout never triggers.** The failed-count update is rolled back by the exception thrown right after it | `AuthServiceImpl.login` |
| — | 🔴 High | Private JWT key and DB password are committed to the repo (Chapter 0). Anyone with repo access can mint SUPER_ADMIN tokens | `resources/keys/`, `application.yml` |
| 4 | 🟠 Medium (likely) | The filter accepts TEMP and registration tokens as full logins with role `null`, which slips past the guards | `ScopeHelper.parseFull`, `JwtAuthenticationFilter` |
| — | 🟠 Medium | DACVM policies are **not enforced** server-side (`@DacvmGuard` is unused) | `security/`, `service/**` |
| 2 | 🟠 Medium | Inactive users can log in and refresh | `AuthServiceImpl.login` / `refresh` |
| — | 🟡 Low–Med | VIEW-only TA check (`assertCanEdit`) is called from only 4 places | `AccessGuard` |
| — | 🟡 Low | Tokens returned in the JSON body despite HttpOnly cookies; `sanitize()` unused; `SameSite=None` + CSRF off | `AuthController` |
| — | 🟡 Low | Role/district changes don't apply until the token expires; logout leaves the access token valid ≤ 2 h | design |
| — | ⚪ Info | `UserDetailsServiceImpl` / `DaoAuthenticationProvider` wired but unused; AUDITOR scoped differently by two guard methods; wrong password returns 404; bare 401 body | various |

I didn't change anything. If you want, I can later write failing tests that prove #1 and #3, then fix them. Those two are small, contained changes.

---

**Next: Chapter 4 — Database & JPA.** It covers:
- `BaseEntity` in depth, and soft delete with `@SQLRestriction` / `@SQLDelete`
- the core entity graph (`User`, `Temple` → `Hobli` → `Taluk` → `District` → `City` → `State`, `Trust` / `BoardMember`, `AssetDeclaration` and its `DeclMov*` / `DeclImmov*` children, `WorkflowInstance` / `WorkflowTransition`)
- every relationship annotation actually used (owning side, `mappedBy`, cascade, fetch, orphanRemoval)
- the encrypted columns (`AesEncryptionConverter`), enum converters, optimistic locking
- the Flyway migration history and how it coexists with `ddl-auto: update`

Say **"continue"** when ready.

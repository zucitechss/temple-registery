# Profile & Password Management

Self-service profile, password change, forgot-password, and the Super Admin password reset.

Related: [Architecture & Workflow](./ARCHITECTURE_AND_WORKFLOW_DOCUMENT.md) · [Security Architecture](./architecture/02-security-architecture.html)

---

## 1. Profile page

`/profile` — available to every authenticated role, reached from the profile avatar in the top bar.

Reads `GET /api/v1/auth/me` (the endpoint `PrivateRoute` already calls on every navigation), so there is
no second profile endpoint. `UserProfileResponse` gained `districtName`, `templeName`, `lastLoginAt`,
`passwordUpdatedAt` and `mustChangePassword` — all additive, so existing consumers are unaffected.

Sections rendered:

| Section | Contents |
|---|---|
| Identity header | Avatar initial, full name, email, role badge, account status |
| Personal Information | Full name, username, email, mobile, user ID, last login |
| Role & Assignment | Role, designation, district, temple, access level, Aadhaar verification |
| Security | Masked password, last password change, **Change Password** button |
| Administrative Actions | SUPER_ADMIN only — link to User Management for resetting another user's password |

Assignment fields render only when the user actually has them, so a Super Admin does not see an empty
"Temple" row and a DC does not see TA-only fields. The response never contains a password, password
hash, reset token, MFA secret, or any JWT.

---

## 2. Change own password

`PATCH /api/v1/profile/password` — body `{ currentPassword, newPassword, confirmPassword }`.

Deliberately mounted under `/api/v1/profile`, **not** `/api/v1/auth`, because `SecurityConfig` exposes
`/api/v1/auth/**` as `permitAll` for the login and reset flows. `@PreAuthorize("isAuthenticated()")` on
`UserProfileServiceImpl.changeOwnPassword` is the final check.

Server-side rules (the browser's copy of these is convenience only):

1. `currentPassword` must match the stored BCrypt hash.
2. `newPassword` must be 8–128 characters — the existing application policy.
3. `confirmPassword` must equal `newPassword`.
4. `newPassword` must differ from the current password.

On success: the new hash is written with the existing `PasswordEncoder`, `password_updated_at` is set,
`must_change_password` is cleared, any pending reset token is invalidated, and **all** refresh tokens for
the user are revoked. The UI then signs the user out — the access token still carries pre-change claims
and the refresh token is already dead, so a fresh sign-in is the only coherent next step.

---

## 3. Forgot password

Already present before this feature; hardened here.

```
/login → "Forgot password?" → /forgot-password
   → POST /api/v1/auth/password-reset-req  { email }
   → email with link to /reset-password?token=<raw token>
   → POST /api/v1/auth/password-reset  { token, newPassword, confirmPassword }
   → redirect to /login
```

**No account enumeration.** The endpoint returns the same 200 and the same message whether or not the
address is registered: *"If the email is registered, a reset link has been sent."* The UI shows the same
confirmation panel either way.

**Token.** 32 bytes from `SecureRandom`, URL-safe Base64. Only its SHA-256 hash is stored
(`users.password_reset_token_hash`); the raw token exists only in the emailed link. Expires after
30 minutes, single use — the hash is cleared on use *and* on expiry, so an expired link cannot be replayed.

**Rate limiting.** Three requests per email address per 15 minutes, enforced before the account lookup so
throttling cannot be used to probe for existing accounts. Backed by Caffeine, already on the classpath
for `CacheConfig` — no new dependency.

> `ponytail:` the counter is per instance, which is correct for the current single-node deployment.
> Move it to a shared store if the backend is ever scaled horizontally.

---

## 4. Super Admin: reset another user's password

`POST /api/v1/admin/users/{id}/reset-password` — SUPER_ADMIN only.

**UI:** User Management → row actions → key icon → confirmation dialog. No separate admin screen.

**Flow:** generate a temporary password → store it hashed with the existing encoder → set
`must_change_password = true` → clear any pending reset token → clear lockout → revoke all the target's
refresh tokens → email the password to their registered address → write an audit event.

**Temporary password:** 12 characters from `TemporaryPasswordGenerator`, `SecureRandom`, guaranteed to
contain upper, lower and digit, shuffled so the guaranteed classes are not always in fixed positions.
Visually ambiguous characters (`0 O 1 l I`) are excluded because it is transcribed by hand from an email.

**The password never leaves the email channel.** It is not in the API response, not in any log line, and
never persisted in plaintext.

---

## 5. Forced password change

When `must_change_password` is true, `JwtServiceImpl` puts a `mustChangePassword` claim in the access
token and `JwtAuthenticationFilter` rejects every request with **403 `PASSWORD_CHANGE_REQUIRED`** except:

- `PATCH /api/v1/profile/password`
- `/api/v1/auth/**` (login, logout, refresh, me — no application data)

This is real enforcement, not a hidden button: a temporary password cannot be used to read or write
anything. The frontend mirrors it by redirecting to `/change-password` from `PrivateRoute`.

`ScopeHelper.parseFull()` returns the claims plus this flag; `ScopeHelper.Claims` — the principal record
constructed in ~50 places — is unchanged, and `parse()` still works as before.

---

## 6. Database changes

`V110__add_password_management_columns.sql` — guarded via `information_schema` (TiDB's
`ADD COLUMN IF NOT EXISTS` is rejected by the MySQL 8 used in Testcontainers), following `V108`.

| Table | Column | Type | Purpose |
|---|---|---|---|
| `users` | `must_change_password` | `TINYINT(1) NOT NULL DEFAULT 0` | Forced change after an admin reset |
| `users` | `password_updated_at` | `DATETIME NULL` | Shown as "Last Password Change" |

No new table: `users.password_reset_token_hash` and `users.password_reset_expires_at` already existed
in `V1__initial_schema.sql` and satisfy the hashed, expiring, single-use token requirement.

### Pre-existing fixes needed to make `mvn clean install` pass

None of the following belong to this feature. They are long-standing defects that the build could
not get past, all with the same origin: the entities and the migrations had drifted apart, and
`ddl-auto=update` had been papering over it in dev and prod.

#### `V111__fix_entity_schema_drift.sql`

Thirteen columns were
declared with `@Column` on entities but never created by a migration; dev and prod only have them
because `ddl-auto=update` added them silently. The Testcontainers integration tests boot a fresh MySQL
with `ddl-auto=validate`, so the context failed with
`Schema-validation: missing column [field_names_json] in table [declaration_clarifications]`.

| Table | Columns added |
|---|---|
| `declaration_clarifications` | `section_name`, `field_names_json` |
| `entity_versions` | `entity_type`, `entity_id`, `captured_at`, `captured_by_user_id`, `triggering_transition_id` |
| `governance_action_history` | `workflow_instance_id`, `workflow_transition_id`, `actor_role` |
| `in_app_notifications` | `workflow_instance_id` |
| `export_job_records` | `format` |
| `districts` | `code` |

All added as `NULL` (no backfill) and each `ADD` is `information_schema`-guarded, so on any database
where `ddl-auto` already created the column the statement is a no-op. Same approach as `V108`.

#### `V112` and `V113` — column types

Hibernate compares JDBC type codes, so six columns existed but with the wrong type. All are
widening conversions; each `MODIFY` is guarded on the current `DATA_TYPE`, so it is skipped — and
no table rebuild is triggered — where the column is already correct.

| Migration | Table | Column | Was | Now |
|---|---|---|---|---|
| V112 | `governance_action_history` | `governance_version` | `INT` | `BIGINT` |
| V112 | `idempotency_records` | `response_status` | `SMALLINT UNSIGNED` | `INT` |
| V112 | `temple_profile_staging` | `year_established` | `SMALLINT` | `INT` |
| V113 | `temple_profile_staging` | `grade` | `CHAR(1)` | `VARCHAR(1)` |
| V113 | `temple_profile_staging` | `latitude` | `DECIMAL(10,7)` | `DOUBLE` |
| V113 | `temple_profile_staging` | `longitude` | `DECIMAL(10,7)` | `DOUBLE` |

`response_status` drops `UNSIGNED` deliberately: MySQL reports `INT UNSIGNED` as `BIGINT` over JDBC,
which would fail validation for a different reason. The staging lat/long stay `Double` rather than
becoming `BigDecimal` like `temples` — the staging entity genuinely declares a different type, and
aligning the DDL to the entity is the smaller change.

#### `@SQLDelete` missing its version predicate — four entities

With `@Version` present, Hibernate binds **id and version** to the soft-delete statement. Four
entities had a single-placeholder `@SQLDelete`, so every soft delete threw
`Parameter index out of range (2 > number of parameters, which is 1)`. `Notice` already had it
right; the others were fixed to match.

| Entity | Version column | Fixed predicate |
|---|---|---|
| `Temple` | `version` | `WHERE id = ? AND version = ?` |
| `AssetDeclaration` | `lock_version` | `WHERE id = ? AND lock_version = ?` |
| `BoardMember` | `lock_version` | `WHERE id = ? AND lock_version = ?` |
| `Trust` | `lock_version` | see below |

This is production breakage, not just test breakage: any code path that soft-deletes one of these
entities was throwing.

#### `Trust` had no `@SQLDelete` at all

`Trust` was the only entity in `entity/trust/` without one, so `TrustServiceImpl.deleteTrust()`
issued a hard `DELETE`. Three foreign keys point at `trusts` (`fk_bm_trust`, `fk_bms_trust`,
`fk_tf_trust`) and all three children soft-delete, so their rows always survive to block the parent
delete — deleting any trust that had ever had a board member, meeting or financial record failed
with a constraint violation. `Trust` now soft-deletes like its siblings.

Registration numbers are still released on delete: the uniqueness check is a derived query on an
entity carrying `@SQLRestriction("is_deleted = false")`, so soft-deleted trusts drop out of it.
No cascade to children was added — `Temple` does not cascade either, so that is the convention here.

Covered by `TrustIntegrationTest.deletes_trust_that_has_board_members`, which needs a real database:
a mocked repository cannot reproduce a foreign-key violation.

---

## 7. API summary

| Method | Endpoint | Auth | Purpose |
|---|---|---|---|
| GET | `/api/v1/auth/me` | JWT | Profile (extended, not new) |
| PATCH | `/api/v1/profile/password` | JWT | Change own password |
| POST | `/api/v1/auth/password-reset-req` | Public | Request a reset link |
| POST | `/api/v1/auth/password-reset` | Public | Complete the reset |
| POST | `/api/v1/admin/users/{id}/reset-password` | SUPER_ADMIN | Temporary password + email |

---

## 8. Authorization matrix

| Action | Super Admin | DC | DC Staff | Temple Authority | Auditor | Viewer |
|---|---|---|---|---|---|---|
| View own profile | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Change own password | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Forgot password | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reset another user's password | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Generate temporary password | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Send temporary password | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |

Enforced by `@PreAuthorize(RoleConstants.ADMIN_ONLY)` on `AdminServiceImpl.resetUserPassword` and at the
class level on `AdminController`. `PasswordManagementAuthorizationTest` locks these guards in place.

---

## 9. Email

| Template | Sent when | Contains |
|---|---|---|
| `email/password-reset.html` | Forgot-password requested | Reset link, 30-minute expiry, single-use notice, security warning, support contact |
| `email/temporary-password.html` | Super Admin resets a password | Greeting, username, temporary password, login link, change-on-next-login instruction, security warning |

Both go through the existing `EmailServiceImpl` — `JavaMailSender` + Thymeleaf, `@Async("taskExecutor")`,
rows written to `email_delivery_log`, gated by `spring.mail.enabled`. Both share the
`email/email-layout.html` fragments. `EmailStartupValidator` verifies both at boot.

Links use `app.base-url`; no environment-specific URL is hardcoded.

### Configuration

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `app.base-url` | `APP_BASE_URL` | `http://localhost:5173` | Base for reset and login links |
| `spring.mail.enabled` | — | `false` | Master switch for SMTP delivery |
| `spring.mail.host` / `port` | — | `smtp.gmail.com` / `587` | SMTP endpoint |
| `spring.mail.username` | `SMTP_USERNAME` | — | SMTP account |
| `spring.mail.password` | `SMTP_PASSWORD` | — | SMTP credential |
| `spring.mail.from` | — | `noreply@templeregistry.gov.in` | From address |

With `spring.mail.enabled=false` the flows still work end to end — the email is skipped and a log line
records that it was skipped, without the token or password.

---

## 10. Security notes

- One hashing mechanism throughout: the existing `BCryptPasswordEncoder(12)` bean.
- Reset tokens are stored hashed, expire, are single use, and are cleared on both use and expiry.
- Passwords, hashes, reset tokens and temporary passwords are never logged — log lines carry user ids only.
- Every password change revokes all the user's refresh tokens.
- Forgot-password responses never reveal whether an account exists.
- Frontend authorization is convenience; `@PreAuthorize` and the JWT filter are the enforcement.

### Audit events

Written through the existing `AuditService`, with ids and descriptions only — never a secret:

| Event | Where |
|---|---|
| `PASSWORD_CHANGED` | `UserProfileServiceImpl.changeOwnPassword` |
| `PASSWORD_RESET_REQUESTED` | `AuthServiceImpl.requestPasswordReset` |
| `PASSWORD_RESET_COMPLETED` | `AuthServiceImpl.confirmPasswordReset` |
| `ADMIN_RESET_USER_PASSWORD` | `AdminServiceImpl.resetUserPassword` |

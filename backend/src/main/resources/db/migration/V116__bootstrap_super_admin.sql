-- ============================================================================
-- V116: Bootstrap the initial SUPER_ADMIN from deploy-time Flyway placeholders
--
-- WHY THIS FILE EXISTS
--   Supersedes com.templeregistry.config.BootstrapAdminInitializer (a Java
--   ApplicationRunner). The requirement is that the SUPER_ADMIN be created by
--   seed data at initial deployment, not by an application code path — this
--   migration is that seed data.
--
-- HOW THE CREDENTIAL STAYS OUT OF GIT (C-5)
--   The values below are Flyway placeholder tokens, substituted at migrate
--   time from spring.flyway.placeholders.* — bound in application-prod.yml to
--   APP_BOOTSTRAP_ADMIN_USERNAME / _EMAIL / _PASSWORD_HASH. This file itself
--   never contains a real username, email or password: nothing secret is
--   committed, exactly like the environment-variable approach it replaces.
--
--   APP_BOOTSTRAP_ADMIN_PASSWORD_HASH must be a bcrypt hash (cost factor 12,
--   matching SecurityConfig.passwordEncoder()), NOT a plaintext password —
--   MySQL cannot compute bcrypt, so the hash has to be precomputed
--   before it is set as the placeholder value, e.g.:
--     python3 -c "import bcrypt; print(bcrypt.hashpw(b'<password>', bcrypt.gensalt(12)).decode())"
--
-- OPERATIONAL NOTE — this only ever gets ONE chance to run
--   Flyway records this migration as applied the first time it executes, even
--   if the guard below turns it into a no-op (e.g. the placeholders were left
--   unset on that deploy). Unlike the old ApplicationRunner, which retried on
--   every restart, a later deploy that finally sets the placeholders will NOT
--   re-trigger this migration. If the SUPER_ADMIN doesn't exist after the
--   first deploy, the placeholders must be set BEFORE that deploy — recovering
--   afterwards requires a manual INSERT, not a redeploy.
--
-- IDEMPOTENCY / SAFETY GUARDS
--   * No-op if a SUPER_ADMIN already exists.
--   * No-op if any placeholder was left unresolved/blank, so a deploy that
--     forgets to set them gets an empty no-op instead of a SUPER_ADMIN row
--     with empty credentials.
--
-- DEV/TEST USE THE SAME MECHANISM
--   There is no seeded SUPER_ADMIN anymore (dev no longer differs from
--   production here). To get a working local login, set
--   APP_BOOTSTRAP_ADMIN_USERNAME / _EMAIL / _PASSWORD_HASH in
--   dev-secrets.properties, same as a production deploy would.
-- ============================================================================

SET @ts = NOW(6);

INSERT INTO users
    (username, email, password_hash, full_name, role,
     is_active, mfa_type, failed_login_count, is_deleted,
     created_at, updated_at, created_by, updated_by)
SELECT '${bootstrapAdminUsername}', '${bootstrapAdminEmail}', '${bootstrapAdminPasswordHash}',
       'Super Administrator', 'SUPER_ADMIN',
       1, 'NONE', 0, 0,
       @ts, @ts, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM users WHERE role = 'SUPER_ADMIN')
  AND '${bootstrapAdminUsername}'     <> ''
  AND '${bootstrapAdminEmail}'        <> ''
  AND '${bootstrapAdminPasswordHash}' <> '';

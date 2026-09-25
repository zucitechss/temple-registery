-- V110: Password management — forced password change after an admin-issued temporary
-- password, plus a last-password-change timestamp shown on the user profile page.
--
-- Guarded via information_schema because `ADD COLUMN IF NOT EXISTS` is TiDB-only —
-- MySQL 8.0 (used by the Testcontainers integration tests) rejects it.

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'users'
        AND COLUMN_NAME  = 'must_change_password') = 0,
    'ALTER TABLE users ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'users'
        AND COLUMN_NAME  = 'password_updated_at') = 0,
    'ALTER TABLE users ADD COLUMN password_updated_at DATETIME NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

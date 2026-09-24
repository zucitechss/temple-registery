-- V108: Add the physical-verification workflow columns that AssetDeclaration declares
-- but no migration ever created. Dev/prod only have them because ddl-auto=update added
-- them silently; a fresh DB (or any test running with ddl-auto=validate) fails without this.
--
-- Guarded via information_schema because `ADD COLUMN IF NOT EXISTS` is TiDB-only —
-- MySQL 8.0 (used by the Testcontainers integration tests) rejects it.

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'asset_declarations'
        AND COLUMN_NAME  = 'physical_verification_ordered_at') = 0,
    'ALTER TABLE asset_declarations ADD COLUMN physical_verification_ordered_at DATETIME NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'asset_declarations'
        AND COLUMN_NAME  = 'physical_verification_ordered_by') = 0,
    'ALTER TABLE asset_declarations ADD COLUMN physical_verification_ordered_by BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'asset_declarations'
        AND COLUMN_NAME  = 'physical_verification_completed_at') = 0,
    'ALTER TABLE asset_declarations ADD COLUMN physical_verification_completed_at DATETIME NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

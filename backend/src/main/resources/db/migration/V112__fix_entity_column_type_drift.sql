-- V112: Align three column types with the entities that map them.
--
-- Companion to V111 (which added the missing columns). These three exist but with a narrower
-- integer type than the entity declares, so Hibernate schema-validation rejects them:
--
--   Schema-validation: wrong column type encountered in column [governance_version]
--   in table [governance_action_history]; found [int], but expecting [bigint]
--
-- As with V111, dev and prod only agree with the entities because ddl-auto=update widened these
-- silently; a fresh database built from the migrations alone does not.
--
-- All three are widening conversions, so no value can be truncated and no backfill is needed.
-- Note `response_status` drops UNSIGNED deliberately: MySQL reports INT UNSIGNED as BIGINT over
-- JDBC, which would fail validation again. HTTP status codes fit a signed INT comfortably.
--
-- Each MODIFY is guarded on the current DATA_TYPE so the statement is skipped — and no table
-- rebuild is triggered — on any database where the column is already correct.

-- governance_action_history.governance_version : INT -> BIGINT
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'governance_action_history'
        AND COLUMN_NAME  = 'governance_version'
        AND DATA_TYPE    = 'bigint') = 0,
    'ALTER TABLE governance_action_history MODIFY COLUMN governance_version BIGINT NOT NULL DEFAULT 1',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- idempotency_records.response_status : SMALLINT UNSIGNED -> INT
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'idempotency_records'
        AND COLUMN_NAME  = 'response_status'
        AND DATA_TYPE    = 'int'
        AND COLUMN_TYPE NOT LIKE '%unsigned%') = 0,
    'ALTER TABLE idempotency_records MODIFY COLUMN response_status INT NOT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- temple_profile_staging.year_established : SMALLINT -> INT
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'temple_profile_staging'
        AND COLUMN_NAME  = 'year_established'
        AND DATA_TYPE    = 'int') = 0,
    'ALTER TABLE temple_profile_staging MODIFY COLUMN year_established INT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V111: Close the entity/migration drift that makes Hibernate schema-validation fail.
--
-- Each of these columns is declared with @Column on an entity but was never created by any
-- migration. Dev and prod only have them because ddl-auto=update added them silently; a fresh
-- database — such as the Testcontainers MySQL the integration tests boot with
-- ddl-auto=validate — fails to start without them. Same class of fix as V108.
--
-- Every column is added as NULL so existing rows need no backfill, and each ADD is guarded via
-- information_schema because `ADD COLUMN IF NOT EXISTS` is TiDB-only — MySQL 8.0 (used by the
-- Testcontainers integration tests) rejects it. On any database where ddl-auto already created
-- the column, the guard makes that statement a no-op.

-- declaration_clarifications.section_name
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'declaration_clarifications'
        AND COLUMN_NAME  = 'section_name') = 0,
    'ALTER TABLE declaration_clarifications ADD COLUMN section_name VARCHAR(100) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- declaration_clarifications.field_names_json
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'declaration_clarifications'
        AND COLUMN_NAME  = 'field_names_json') = 0,
    'ALTER TABLE declaration_clarifications ADD COLUMN field_names_json JSON NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- entity_versions.entity_type
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'entity_versions'
        AND COLUMN_NAME  = 'entity_type') = 0,
    'ALTER TABLE entity_versions ADD COLUMN entity_type VARCHAR(30) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- entity_versions.entity_id
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'entity_versions'
        AND COLUMN_NAME  = 'entity_id') = 0,
    'ALTER TABLE entity_versions ADD COLUMN entity_id BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- entity_versions.captured_at
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'entity_versions'
        AND COLUMN_NAME  = 'captured_at') = 0,
    'ALTER TABLE entity_versions ADD COLUMN captured_at DATETIME(6) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- entity_versions.captured_by_user_id
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'entity_versions'
        AND COLUMN_NAME  = 'captured_by_user_id') = 0,
    'ALTER TABLE entity_versions ADD COLUMN captured_by_user_id BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- entity_versions.triggering_transition_id
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'entity_versions'
        AND COLUMN_NAME  = 'triggering_transition_id') = 0,
    'ALTER TABLE entity_versions ADD COLUMN triggering_transition_id BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- export_job_records.format
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'export_job_records'
        AND COLUMN_NAME  = 'format') = 0,
    'ALTER TABLE export_job_records ADD COLUMN format VARCHAR(10) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- governance_action_history.workflow_instance_id
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'governance_action_history'
        AND COLUMN_NAME  = 'workflow_instance_id') = 0,
    'ALTER TABLE governance_action_history ADD COLUMN workflow_instance_id BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- governance_action_history.workflow_transition_id
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'governance_action_history'
        AND COLUMN_NAME  = 'workflow_transition_id') = 0,
    'ALTER TABLE governance_action_history ADD COLUMN workflow_transition_id BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- governance_action_history.actor_role
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'governance_action_history'
        AND COLUMN_NAME  = 'actor_role') = 0,
    'ALTER TABLE governance_action_history ADD COLUMN actor_role VARCHAR(32) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- in_app_notifications.workflow_instance_id
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'in_app_notifications'
        AND COLUMN_NAME  = 'workflow_instance_id') = 0,
    'ALTER TABLE in_app_notifications ADD COLUMN workflow_instance_id BIGINT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- districts.code
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'districts'
        AND COLUMN_NAME  = 'code') = 0,
    'ALTER TABLE districts ADD COLUMN code VARCHAR(10) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V113: Align the remaining temple_profile_staging column types with the entity.
--
-- Third and final batch of the entity/migration drift fix (see V111 for missing columns and
-- V112 for the integer types). Hibernate compares JDBC type codes, so CHAR and VARCHAR are not
-- interchangeable and neither are DECIMAL and DOUBLE:
--
--   Schema-validation: wrong column type encountered in column [grade]
--   in table [temple_profile_staging]; found [char], but expecting [varchar(1)]
--
-- Note this aligns the staging table with TempleProfileStaging, which is deliberately *not* the
-- same shape as the main temples table: temples.grade is a TempleGrade enum in VARCHAR(5) and
-- temples.latitude is a BigDecimal in DECIMAL(10,7), while the staging entity declares a String
-- and a Double. Only the DDL is changed here — no entity, no mapper, no service.
--
-- CHAR(1) -> VARCHAR(1) cannot truncate. DECIMAL(10,7) -> DOUBLE is well within a double's ~15
-- significant digits, so no coordinate loses precision.
--
-- Each MODIFY is guarded on the current DATA_TYPE so it is skipped, with no table rebuild, on any
-- database where the column is already correct.

-- temple_profile_staging.grade : CHAR(1) -> VARCHAR(1)
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'temple_profile_staging'
        AND COLUMN_NAME  = 'grade'
        AND DATA_TYPE    = 'varchar') = 0,
    'ALTER TABLE temple_profile_staging MODIFY COLUMN grade VARCHAR(1) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- temple_profile_staging.latitude : DECIMAL(10,7) -> DOUBLE
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'temple_profile_staging'
        AND COLUMN_NAME  = 'latitude'
        AND DATA_TYPE    = 'double') = 0,
    'ALTER TABLE temple_profile_staging MODIFY COLUMN latitude DOUBLE NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- temple_profile_staging.longitude : DECIMAL(10,7) -> DOUBLE
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'temple_profile_staging'
        AND COLUMN_NAME  = 'longitude'
        AND DATA_TYPE    = 'double') = 0,
    'ALTER TABLE temple_profile_staging MODIFY COLUMN longitude DOUBLE NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

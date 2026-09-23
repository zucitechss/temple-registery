-- TODO 10 — final Flyway validation. Hibernate's schema validator throws on the FIRST
-- mismatch it finds and stops, so these column-type mismatches were hidden behind the 13
-- missing columns V111 just fixed — each only became visible once the one before it in
-- validation order was resolved.

-- GovernanceActionHistory.governanceVersion is mapped as Long (BIGINT), but V1 created this
-- column as INT. The comment on that field calls it a "Legacy column ... not used by
-- application logic", so nothing depends on its value. Widening INT -> BIGINT is lossless.
ALTER TABLE governance_action_history
    MODIFY COLUMN governance_version BIGINT NOT NULL DEFAULT 1;

-- IdempotencyRecord.responseStatus (com.templeregistry.entity.dc) is a plain Java int, which
-- Hibernate maps to SQL INTEGER, but V1 created this column as SMALLINT UNSIGNED. Both hold
-- every valid HTTP status code (100-599); widening to INT is lossless.
ALTER TABLE idempotency_records
    MODIFY COLUMN response_status INT NOT NULL;

-- TempleProfileStaging.grade is @Column(length = 1) with no columnDefinition, which Hibernate
-- maps to VARCHAR(1), but V1 created this column as CHAR(1). Both store the same single-char
-- grade codes (A/B/C); converting is lossless.
ALTER TABLE temple_profile_staging
    MODIFY COLUMN grade VARCHAR(1) NULL;

-- TempleProfileStaging.latitude/longitude are Double (unlike Temple's own BigDecimal
-- latitude/longitude, precision 10/11), which Hibernate maps to DOUBLE, but V1 created these
-- columns as DECIMAL(10,7). DOUBLE's ~15-17 significant digits comfortably cover DECIMAL(10,7)'s
-- 7 fractional digits (sub-millimeter GPS resolution), so this is not a precision regression.
ALTER TABLE temple_profile_staging
    MODIFY COLUMN latitude  DOUBLE NULL,
    MODIFY COLUMN longitude DOUBLE NULL;

-- TempleProfileStaging.yearEstablished is Integer, which Hibernate maps to INTEGER, but V1
-- created this column as SMALLINT. Widening is lossless.
ALTER TABLE temple_profile_staging
    MODIFY COLUMN year_established INT NULL;

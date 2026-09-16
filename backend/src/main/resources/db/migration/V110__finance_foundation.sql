-- ============================================================================
-- V110: Finance Platform Foundation (Phase 1)
--
-- Creates the CONFIGURATION and OPERATIONAL spine of the multi-temple Finance
-- Integration & Reporting Platform.  No fact, dimension, staging or aggregate
-- table is created here -- those arrive in later phases.
--
-- Architecture reference:
--   docs/finance/MULTI_TEMPLE_FINANCE_ARCHITECTURE.md
--   docs/finance/FINANCE_DATA_MODEL.md  section 3
--   docs/finance/adr/ADR-001 (no runtime source access)
--   docs/finance/adr/ADR-007 (availability is first-class data)
--   docs/finance/adr/ADR-008 (source of truth is declared, versioned config)
--
-- HARD CONSTRAINT enforced structurally by this schema:
--   The Temple Registry runtime NEVER queries a temple operational database.
--   fin_source_system therefore stores a credential *reference*, never a
--   credential.  There is no password, connection string or host column.
--
-- Pipeline these tables support:
--   Temple DB -> Connector -> Staging -> Validation -> Mapping -> Canonical
--             -> Aggregation -> Finance API -> Dashboard
--
-- No FOREIGN KEY constraints are declared (see IMPLEMENTATION_DECISIONS.md
-- FIN-D-003): relationships are enforced by the application and supported by
-- indexes, consistent with V10 and the TiDB deployment target.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. fin_source_system
--    Which external system feeds which temple.  For Kollur this table holds
--    the 300001 <-> 43 identity mapping that exists nowhere else today.
--    A temple may have more than one source system, which is why this is a
--    table rather than columns on `temples`.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_source_system (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id                 BIGINT        NOT NULL                COMMENT 'Registry temple id, e.g. 300001',
    system_code               VARCHAR(50)   NOT NULL                COMMENT 'Stable code, e.g. KOLSOHAM',
    system_name               VARCHAR(200)  NOT NULL,

    source_technology         VARCHAR(30)   NOT NULL                COMMENT 'SQL_SERVER | MYSQL | POSTGRESQL | ORACLE | FILE | API',
    connector_type            VARCHAR(30)   NOT NULL                COMMENT 'PULL_JDBC | PUSH_AGENT | SOURCE_API | FILE_DROP',
    connector_bean            VARCHAR(150)  NOT NULL                COMMENT 'Spring bean name of the TempleFinanceConnector',

    source_temple_code        VARCHAR(50)   NULL                    COMMENT 'Temple identifier INSIDE the source system, e.g. 43',
    source_database_name      VARCHAR(100)  NULL                    COMMENT 'Documentation only, e.g. KOLSOHAM_LOCAL. NOT a connection string',

    -- Credentials are NEVER stored here. This is a lookup key resolved by the
    -- sync worker from environment/secret configuration at extraction time.
    credential_ref            VARCHAR(200)  NULL                    COMMENT 'Alias/key only -- never a credential value',

    sync_schedule_cron        VARCHAR(50)   NULL,
    sync_enabled              TINYINT(1)    NOT NULL DEFAULT 0      COMMENT 'Kill switch; defaults OFF so registering a source never starts traffic',
    staleness_threshold_hours INT           NOT NULL DEFAULT 48     COMMENT 'Drives FRESH / STALE in report responses',
    schema_fingerprint        VARCHAR(64)   NULL                    COMMENT 'Detects source schema drift between syncs',
    source_timezone           VARCHAR(50)   NOT NULL DEFAULT 'Asia/Kolkata',
    notes                     TEXT          NULL,

    is_deleted                TINYINT(1)    NOT NULL DEFAULT 0,
    created_at                DATETIME(6)   NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,
    created_by                BIGINT        NOT NULL DEFAULT 0,
    updated_by                BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fss_temple_system UNIQUE (temple_id, system_code)
);

CREATE INDEX idx_fss_temple   ON fin_source_system (temple_id, is_deleted);
CREATE INDEX idx_fss_enabled  ON fin_source_system (sync_enabled, is_deleted);


-- ----------------------------------------------------------------------------
-- 2. fin_temple_capability
--    ADR-007. What each temple can actually answer, and the reason when it
--    cannot.  A missing capability is NOT zero -- `availability_reason` is
--    user-facing text rendered verbatim by the dashboard.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_temple_capability (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id            BIGINT        NOT NULL,
    source_system_id     BIGINT        NOT NULL,

    capability           VARCHAR(50)   NOT NULL   COMMENT 'REVENUE | SEVA | PRECIOUS_METAL_VALUE | NIRANTARA_EXECUTION | EXPENSE | ...',
    availability         VARCHAR(30)   NOT NULL   COMMENT 'AVAILABLE | PARTIALLY_AVAILABLE | NOT_AVAILABLE | NOT_APPLICABLE',
    availability_reason  TEXT          NULL       COMMENT 'USER-FACING. Shown verbatim when data is absent',

    coverage_from        DATE          NULL       COMMENT 'Earliest date this capability has data for',
    coverage_to          DATE          NULL       COMMENT 'Latest date in the SOURCE (not the last sync time)',
    known_gaps_json      JSON          NULL       COMMENT 'Documented holes inside the coverage window',
    last_reviewed_at     DATETIME(6)   NULL,

    is_deleted           TINYINT(1)    NOT NULL DEFAULT 0,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    created_by           BIGINT        NOT NULL DEFAULT 0,
    updated_by           BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_ftc_temple_capability UNIQUE (temple_id, capability)
);

CREATE INDEX idx_ftc_source       ON fin_temple_capability (source_system_id);
CREATE INDEX idx_ftc_availability ON fin_temple_capability (availability);


-- ----------------------------------------------------------------------------
-- 3. fin_source_of_truth_decl
--    ADR-008. Which source field is authoritative for a metric -- declared as
--    reviewable, versioned, signed-off data rather than a WHERE clause buried
--    in a connector.
--
--    For Kollur this prevents a 41% revenue understatement: the *more granular*
--    DailySevaNewDetails.TotalAmount looks like an improvement to anyone
--    arriving without context, and is wrong.  Recording the rejected
--    alternatives with their measured totals is what gives the decision force.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_source_of_truth_decl (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    source_system_id           BIGINT        NOT NULL,
    metric                     VARCHAR(50)   NOT NULL  COMMENT 'REVENUE_AMOUNT | PRECIOUS_METAL_WEIGHT | ...',
    version                    INT           NOT NULL DEFAULT 1 COMMENT 'Incremented on change; never overwritten',

    source_object              VARCHAR(200)  NOT NULL  COMMENT 'e.g. DailySevaNew',
    source_field               VARCHAR(100)  NOT NULL  COMMENT 'e.g. Amount',
    filter_predicate           TEXT          NULL      COMMENT 'e.g. BillCancled=0 AND Deleteflag=0',
    rejected_alternatives_json JSON          NULL      COMMENT 'Candidates considered, measured totals, and why each was rejected',
    rationale                  TEXT          NULL,

    approved_by                BIGINT        NULL,
    approved_at                DATETIME(6)   NULL,
    effective_from             DATE          NULL,
    effective_to               DATE          NULL      COMMENT 'NULL = currently in force',

    is_deleted                 TINYINT(1)    NOT NULL DEFAULT 0,
    created_at                 DATETIME(6)   NOT NULL,
    updated_at                 DATETIME(6)   NOT NULL,
    created_by                 BIGINT        NOT NULL DEFAULT 0,
    updated_by                 BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fsotd_source_metric_version UNIQUE (source_system_id, metric, version)
);

CREATE INDEX idx_fsotd_current ON fin_source_of_truth_decl (source_system_id, metric, effective_to);


-- ----------------------------------------------------------------------------
-- 4. fin_mapping_rule
--    ADR-004. Source value -> canonical value, as configuration.
--    Unmapped values are NOT silently defaulted: they route to UNMAPPED and
--    are surfaced as an operational warning, so a new seva code added at the
--    temple shows up instead of quietly vanishing from a revenue total.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_mapping_rule (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    source_system_id  BIGINT        NOT NULL,
    mapping_type      VARCHAR(40)   NOT NULL  COMMENT 'REVENUE_CATEGORY | SERVICE | PAYMENT_MODE | STATUS | FINANCIAL_YEAR | METAL_TYPE',
    source_value      VARCHAR(200)  NOT NULL  COMMENT 'Raw value as it appears in the source, e.g. 430, KN, 2',
    source_label      VARCHAR(400)  NULL      COMMENT 'Human label from the source; may be non-Latin script',
    canonical_value   VARCHAR(100)  NOT NULL  COMMENT 'e.g. HUNDI_DONATION, DONATION, GOLD',
    is_active         TINYINT(1)    NOT NULL DEFAULT 1,
    notes             TEXT          NULL,

    is_deleted        TINYINT(1)    NOT NULL DEFAULT 0,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        BIGINT        NOT NULL DEFAULT 0,
    updated_by        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fmr_source_type_value UNIQUE (source_system_id, mapping_type, source_value)
);

CREATE INDEX idx_fmr_lookup ON fin_mapping_rule (source_system_id, mapping_type, is_active);


-- ----------------------------------------------------------------------------
-- 5. fin_sync_batch
--    The audit spine.  Every extraction -- successful or not -- leaves a row.
--    Shape deliberately mirrors email_outbox (V105): a status machine with
--    exponential back-off and a terminal dead-letter state, which is the
--    pattern this codebase already operates and monitors.
--
--    State machine:
--      PENDING -> RUNNING -> SUCCESS
--                         -> FAILED            -> (retry) -> RUNNING
--                         -> RECONCILE_FAILED  (loaded, but totals disagree)
--                         -> DEAD_LETTER       (retries exhausted)
--
--    Retained indefinitely: it is the evidence base for every published figure.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_sync_batch (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    batch_ref           VARCHAR(64)   NOT NULL  COMMENT 'UUID; externally quotable in support conversations',
    temple_id           BIGINT        NOT NULL,
    source_system_id    BIGINT        NOT NULL,
    capability          VARCHAR(50)   NOT NULL  COMMENT 'One batch per capability',
    sync_type           VARCHAR(20)   NOT NULL  COMMENT 'HISTORICAL | INCREMENTAL | BACKFILL | REPLAY | DRY_RUN',
    status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',

    window_from         DATETIME(6)   NULL      COMMENT 'Extraction window start',
    window_to           DATETIME(6)   NULL,
    watermark_before    VARCHAR(100)  NULL      COMMENT 'Watermark at batch start',
    watermark_after     VARCHAR(100)  NULL      COMMENT 'Advanced ONLY on SUCCESS',

    rows_extracted      BIGINT        NOT NULL DEFAULT 0,
    rows_rejected       BIGINT        NOT NULL DEFAULT 0,
    rows_loaded         BIGINT        NOT NULL DEFAULT 0,

    retry_count         INT           NOT NULL DEFAULT 0,
    max_retries         INT           NOT NULL DEFAULT 5,
    next_retry_at       DATETIME(6)   NULL      COMMENT 'Exponential back-off, as in email_outbox',
    last_failure_reason TEXT          NULL,

    schema_fingerprint  VARCHAR(64)   NULL      COMMENT 'Compared against fin_source_system to detect drift',
    triggered_by        VARCHAR(50)   NOT NULL DEFAULT 'SCHEDULER' COMMENT 'SCHEDULER | MANUAL | ONBOARDING',

    started_at          DATETIME(6)   NULL,
    finished_at         DATETIME(6)   NULL,
    duration_ms         BIGINT        NULL,

    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fsb_batch_ref UNIQUE (batch_ref)
);

CREATE INDEX idx_fsb_temple_status ON fin_sync_batch (temple_id, status);
CREATE INDEX idx_fsb_retry         ON fin_sync_batch (status, next_retry_at);
CREATE INDEX idx_fsb_source_cap    ON fin_sync_batch (source_system_id, capability, started_at);


-- ----------------------------------------------------------------------------
-- 6. fin_sync_error
--    Row-level rejections.  A batch can succeed overall while individual
--    source rows are rejected; those rows are recorded here rather than
--    dropped, so "rows_rejected = 143" is always explainable.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_sync_error (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    sync_batch_id     BIGINT        NOT NULL,
    source_record_ref VARCHAR(200)  NULL      COMMENT 'Locator for the offending source row',
    error_stage       VARCHAR(30)   NOT NULL  COMMENT 'EXTRACT | VALIDATE | MAP | NORMALIZE | LOAD',
    error_code        VARCHAR(60)   NOT NULL  COMMENT 'e.g. UNMAPPED_SERVICE, NEGATIVE_AMOUNT, NULL_DATE',
    error_message     TEXT          NULL,
    raw_payload_json  JSON          NULL      COMMENT 'The rejected row, for diagnosis and replay',
    created_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_fse_batch ON fin_sync_error (sync_batch_id, error_stage);
CREATE INDEX idx_fse_code  ON fin_sync_error (error_code);


-- ----------------------------------------------------------------------------
-- 7. fin_reconciliation_result
--    Source-computed total vs centrally-computed total, per period.
--    A FAILED result BLOCKS publication of the affected aggregates: the prior
--    good figures remain visible and the temple is marked stale.  Slightly old
--    and correct beats fresh and wrong on a government oversight dashboard.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_reconciliation_result (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    temple_id           BIGINT         NOT NULL,
    source_system_id    BIGINT         NOT NULL,
    sync_batch_id       BIGINT         NULL      COMMENT 'NULL for scheduled re-verification of existing data',
    capability          VARCHAR(50)    NOT NULL,
    metric              VARCHAR(50)    NOT NULL  COMMENT 'e.g. REVENUE_AMOUNT, TRANSACTION_COUNT',

    period_type         VARCHAR(20)    NOT NULL  COMMENT 'DAY | MONTH | FINANCIAL_YEAR | FULL_HISTORY',
    period_key          VARCHAR(20)    NOT NULL  COMMENT 'e.g. 2025-26, 2025-04, 2025-04-01',

    -- Nullable, never zero: a total that could not be computed is NOT_AVAILABLE.
    source_total        DECIMAL(20,2)  NULL      COMMENT 'Computed BY THE SOURCE, via connector.sourceTotals()',
    central_total       DECIMAL(20,2)  NULL      COMMENT 'Computed from canonical facts',
    difference          DECIMAL(20,2)  NULL      COMMENT 'central - source',
    difference_pct      DECIMAL(9,4)   NULL,
    tolerance_pct       DECIMAL(9,4)   NOT NULL DEFAULT 0.0000 COMMENT 'Revenue tolerance is 0 by default',

    status              VARCHAR(20)    NOT NULL  COMMENT 'PASSED | FAILED | NOT_AVAILABLE',
    status_reason       TEXT           NULL,
    checked_at          DATETIME(6)    NOT NULL,

    created_at          DATETIME(6)    NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_frr_temple_period ON fin_reconciliation_result (temple_id, metric, period_type, period_key);
CREATE INDEX idx_frr_batch         ON fin_reconciliation_result (sync_batch_id);
CREATE INDEX idx_frr_status        ON fin_reconciliation_result (status, checked_at);

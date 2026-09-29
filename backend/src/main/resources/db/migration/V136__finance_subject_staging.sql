-- ============================================================================
-- V136: Staging for the four new subjects
--
-- One staging table per capability, following the convention fin_stg_revenue
-- set in V121 and the note in its header: "other capabilities get their own
-- staging tables with their phases".
--
-- WHY FOUR TABLES AND NOT ONE WITH A CAPABILITY COLUMN
--
-- A single generic staging table would need a discriminator, would lose the
-- per-subject advisory business-date column, and would put four subjects behind
-- one unique key and one index -- so a bulk expense import would contend with a
-- fund submission for the same rows. The cost of four near-identical tables is
-- four CREATE statements; the cost of one shared one is paid on every query.
--
-- WHY STAGE AT ALL FOR MANUAL INPUT
--
-- A form submission could be written straight to its fact from a controller.
-- Staging it instead is what makes the manual lane use the same validator, the
-- same mapping record, the same idempotent load and the same error trail as a
-- connector. It also means a rejected submission leaves evidence of what was
-- submitted, which a failed controller call does not.
--
-- THE SHAPE IS COPIED DELIBERATELY
--
-- Every column below means what the same column means in fin_stg_revenue, and
-- the three timestamps keep the same separation V121 insisted on:
--
--   extracted_at  when the row was read from the source, or submitted
--   created_at    when this platform stored it
--   updated_at    when its processing state last changed
--
-- None is a business date. source_business_date stays advisory: normalization
-- derives the authoritative date from raw_json against the source-of-truth
-- declaration, and NULL here means "not declared at staging", never "no date".
--
-- Unlike revenue, source_record_ref is the fact grain for these subjects
-- (see V132), so what the staging unique key prevents -- the same record twice
-- in one batch -- is also what stops two facts being written for one input row.
-- ============================================================================

CREATE TABLE fin_stg_expense (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id            BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id     BIGINT        NOT NULL,
    sync_batch_id        BIGINT        NOT NULL,
    source_record_ref    VARCHAR(200)  NOT NULL  COMMENT 'Opaque locator: a form submission handle, or a sheet and row such as Expenses!R42. Never SQL',
    raw_json             JSON          NOT NULL  COMMENT 'The submitted or extracted record in its own field names. Source vocabulary stops here',
    source_business_date DATE          NULL      COMMENT 'ADVISORY. Normalization derives the authoritative expense_date',
    validation_status    VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED | VALID | REJECTED | LOADED',
    rejection_reason     TEXT          NULL      COMMENT 'Human-readable. The coded, queryable form is fin_sync_error',
    extracted_at         DATETIME(6)   NOT NULL  COMMENT 'When the row was read or submitted. NOT a business date',
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fse_batch_record UNIQUE (sync_batch_id, source_record_ref)
);
CREATE INDEX idx_fse_batch_status ON fin_stg_expense (sync_batch_id, validation_status);
CREATE INDEX idx_fse_temple_date  ON fin_stg_expense (temple_id, source_business_date);

CREATE TABLE fin_stg_fund (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id            BIGINT        NOT NULL,
    source_system_id     BIGINT        NOT NULL,
    sync_batch_id        BIGINT        NOT NULL,
    source_record_ref    VARCHAR(200)  NOT NULL,
    record_kind          VARCHAR(20)   NOT NULL  COMMENT 'FUND | UTILISATION. One staging table serves both because a fund and its drawdowns arrive together on one form and in one sheet',
    raw_json             JSON          NOT NULL,
    source_business_date DATE          NULL      COMMENT 'ADVISORY -- approval date for a FUND row, utilisation date for a UTILISATION row',
    validation_status    VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',
    rejection_reason     TEXT          NULL,
    extracted_at         DATETIME(6)   NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fsf_batch_record UNIQUE (sync_batch_id, source_record_ref)
);
CREATE INDEX idx_fsf_batch_status ON fin_stg_fund (sync_batch_id, validation_status, record_kind);
CREATE INDEX idx_fsf_temple_date  ON fin_stg_fund (temple_id, source_business_date);

CREATE TABLE fin_stg_precious_item (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id            BIGINT        NOT NULL,
    source_system_id     BIGINT        NOT NULL,
    sync_batch_id        BIGINT        NOT NULL,
    source_record_ref    VARCHAR(200)  NOT NULL,
    raw_json             JSON          NOT NULL,
    source_business_date DATE          NULL      COMMENT 'ADVISORY -- the received date, not the valuation date',
    validation_status    VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',
    rejection_reason     TEXT          NULL,
    extracted_at         DATETIME(6)   NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fspi_batch_record UNIQUE (sync_batch_id, source_record_ref)
);
CREATE INDEX idx_fspi_batch_status ON fin_stg_precious_item (sync_batch_id, validation_status);
CREATE INDEX idx_fspi_temple_date  ON fin_stg_precious_item (temple_id, source_business_date);

CREATE TABLE fin_stg_nirantara (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id            BIGINT        NOT NULL,
    source_system_id     BIGINT        NOT NULL,
    sync_batch_id        BIGINT        NOT NULL,
    source_record_ref    VARCHAR(200)  NOT NULL,
    record_kind          VARCHAR(20)   NOT NULL  COMMENT 'SUBSCRIPTION | PAYMENT. Same reasoning as fin_stg_fund.record_kind',
    raw_json             JSON          NOT NULL,
    source_business_date DATE          NULL      COMMENT 'ADVISORY -- start date for a SUBSCRIPTION row, paid date for a PAYMENT row',
    validation_status    VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',
    rejection_reason     TEXT          NULL,
    extracted_at         DATETIME(6)   NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fsn_batch_record UNIQUE (sync_batch_id, source_record_ref)
);
CREATE INDEX idx_fsn_batch_status ON fin_stg_nirantara (sync_batch_id, validation_status, record_kind);
CREATE INDEX idx_fsn_temple_date  ON fin_stg_nirantara (temple_id, source_business_date);

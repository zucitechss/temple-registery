-- ============================================================================
-- V133: DC-approved funds and their utilisation (FR1.4, FR5, FR6)
--
-- FR5 names eight things to track per fund, and two of them -- supporting
-- receipts and supporting photographic evidence -- are documents rather than
-- figures. They attach through the existing document store; no new storage.
--
-- WHY TWO TABLES AND NOT A FACT
--
-- A fund is an approval, not a transaction. It has an approval date, an
-- approver, a sanction letter reference and a deadline, none of which fit a fact
-- row, and it exists from the moment the DC signs it -- before a rupee is spent.
-- Utilisation is the transaction side, and there are many per fund.
--
-- Modelling "approved vs spent" as one table would force a choice between a row
-- that cannot be drawn down incrementally and a row that repeats the approval on
-- every payment. Both are wrong in the way that produces a plausible incorrect
-- total.
--
-- FR6 IS THIS REPORT FILTERED, NOT A THIRD TABLE
--
-- The specification says so itself: ongoing works is "a subset of the report
-- above and will track only those works currently in progress". work_status on
-- the fund answers it. A fin_work_project table would carry a copy of the fund
-- name, amount and approver, and the two copies would disagree within a month.
--
-- WHERE THE DATA COMES FROM
--
-- Manual input, entirely. The approval is the DC office's own act and no temple
-- operational system holds it. Both tables therefore carry the same provenance
-- columns as every other fact, and the same record grain (see V132): the unique
-- key is (source_system_id, source_record_ref), so a correction restates one row.
--
-- APPROVED_AMOUNT IS NULLABLE
--
-- ADR-007 applies to an approval as much as to a measurement: a fund recorded
-- from a sanction letter whose amount is illegible is not a fund of zero rupees.
-- The FR5 chart renders such a row as unavailable rather than as a bar of height
-- nought.
--
-- THE BALANCE IS NOT STORED
--
-- approved_amount minus the sum of its utilisations is a two-table arithmetic
-- that changes on every payment. Storing it would create a second number that
-- can disagree with the rows it summarises, which is the failure ADR-011 keeps
-- aggregates gated for. Fund volumes are in the tens per temple, so the report
-- computes it.
-- ============================================================================

CREATE TABLE fin_dc_fund (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id               BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id        BIGINT        NOT NULL  COMMENT 'fin_source_system.id -- in practice a MANUAL_ENTRY or FILE_UPLOAD channel',
    sync_batch_id           BIGINT        NOT NULL  COMMENT 'fin_sync_batch.id -- which submission recorded it',
    source_record_ref       VARCHAR(200)  NOT NULL  COMMENT 'Row-exact provenance handle. In the grain',

    fund_name               VARCHAR(300)  NOT NULL  COMMENT 'FR5.1',
    approval_date           DATE          NULL      COMMENT 'FR5.2. NULL only where the sanction letter does not state one',
    use_by_date             DATE          NULL      COMMENT 'FR5.3 -- funds to be used by. Drives the FR6 deadline view',
    approved_by_name        VARCHAR(200)  NULL      COMMENT 'FR5.4, person. A name as written on the sanction letter, not a user account',
    approved_by_department  VARCHAR(200)  NULL      COMMENT 'FR5.4, department',
    fund_category           VARCHAR(100)  NULL      COMMENT 'FR5.5 -- explicitly "if available", so nullable by requirement',
    sanction_letter_ref     VARCHAR(150)  NULL      COMMENT 'FR5.6. The quotable reference in correspondence with the DC office',
    approved_amount         DECIMAL(18,2) NULL      COMMENT 'NULL = not recorded (ADR-007). Never defaulted to zero',
    currency                CHAR(3)       NOT NULL DEFAULT 'INR' COMMENT 'ISO 4217',

    work_status             VARCHAR(30)   NOT NULL DEFAULT 'NOT_STARTED'
                                                    COMMENT 'NOT_STARTED | IN_PROGRESS | COMPLETED | CANCELLED | ON_HOLD. FR6 is this column filtered to IN_PROGRESS',
    work_description        VARCHAR(1000) NULL,
    financial_year          VARCHAR(10)   NOT NULL  COMMENT 'FY of the approval. FR1 box 4 sums utilisation within the current one',

    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fdf_record UNIQUE (source_system_id, source_record_ref)
);

CREATE INDEX idx_fdf_temple_fy     ON fin_dc_fund (temple_id, financial_year);
CREATE INDEX idx_fdf_temple_status ON fin_dc_fund (temple_id, work_status);
CREATE INDEX idx_fdf_sanction      ON fin_dc_fund (sanction_letter_ref);
CREATE INDEX idx_fdf_batch         ON fin_dc_fund (sync_batch_id);

CREATE TABLE fin_fund_utilisation (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id               BIGINT        NOT NULL  COMMENT 'Denormalised from the fund so every isolation check is one table deep',
    fund_id                 BIGINT        NOT NULL  COMMENT 'fin_dc_fund.id',
    source_system_id        BIGINT        NOT NULL,
    sync_batch_id           BIGINT        NOT NULL,
    source_record_ref       VARCHAR(200)  NOT NULL  COMMENT 'Row-exact provenance handle. In the grain',

    utilised_on             DATE          NOT NULL  COMMENT 'BUSINESS date of the drawdown',
    financial_year          VARCHAR(10)   NOT NULL  COMMENT 'FY of the drawdown. May differ from the fund FY -- a fund approved in March is often spent in April',
    amount                  DECIMAL(18,2) NULL      COMMENT 'NULL = not recorded (ADR-007)',
    description             VARCHAR(1000) NULL,
    progress_percent        TINYINT       NULL      COMMENT 'FR6 progress as reported by the temple. Advisory: it is a statement, not a measurement derived from money spent',

    receipt_document_id     BIGINT        NULL      COMMENT 'FR5.7. Existing document store; this platform adds no second file store',
    photo_document_id       BIGINT        NULL      COMMENT 'FR5.8. Same',

    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ffu_record UNIQUE (source_system_id, source_record_ref)
);

CREATE INDEX idx_ffu_fund        ON fin_fund_utilisation (fund_id, utilised_on);
CREATE INDEX idx_ffu_temple_fy   ON fin_fund_utilisation (temple_id, financial_year);
CREATE INDEX idx_ffu_batch       ON fin_fund_utilisation (sync_batch_id);
CREATE INDEX idx_ffu_supersede   ON fin_fund_utilisation (temple_id, source_system_id, utilised_on);

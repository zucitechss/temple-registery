-- ============================================================================
-- V124: Canonical expenditure -- category dimension and fact (FR1.3, FR4)
--
-- Nothing in the model records money leaving. FinanceCapability.EXPENSE has been
-- an enum value with no table behind it since V110.
--
-- ============================================================================
-- THE GRAIN OF EVERY NEW FACT: ONE ROW PER SOURCE RECORD
-- ============================================================================
--
-- This is the contract the four new subjects introduced by the Financial
-- Dashboard specification share (expenditure, funds, precious items, Nirantara),
-- and it is deliberately NOT the revenue grain. It is stated here because this
-- is the first of them, and because ingestion and reporting are built against it
-- by two different people.
--
--   fin_revenue_fact   one row per (temple, source, day, service, category,
--                      payment mode, counter, operator)          -- ADR-003
--   these facts        one row per (source system, source record)
--
-- Revenue collapses to a daily grain because one source holds 22,348,125
-- receipts and copying them would be expensive and pointless. Expenditure does
-- not: a temple records a handful of vouchers a day, each a distinct thing with
-- its own payee and its own receipt. Collapsing three vouchers in one category
-- on one day into a single row would destroy exactly the detail FR4's grid
-- exists to show, and would make "which payment was that?" unanswerable.
--
-- So the unique key is (source_system_id, source_record_ref) and the load is an
-- upsert on it. Consequences, all intended:
--
--   * Re-running a batch restates each record rather than duplicating it, which
--     is the same idempotency guarantee uk_frf_grain gives revenue.
--   * source_record_ref is NEVER NULL here. For revenue it is nullable because a
--     grouped fact has no single source record (FIN-D-040); here every fact has
--     exactly one, so provenance is row-exact and the trail reaches an individual
--     form submission or spreadsheet cell.
--   * A correction re-submits the SAME ref. The manual input API returns the ref
--     on create and an edit posts it back, so an edit restates one line instead
--     of appending a second.
--
-- REPLACING A WHOLE DAY IS AN IMPORTER OPERATION, NOT A GRAIN
--
-- A second spreadsheet covering a day already loaded carries different row
-- numbers, so the upsert alone would leave the first file's facts in place and
-- double the day. The Excel importer therefore supersedes explicitly: on commit,
-- for each (temple, source system, expense_date) the file covers, it deletes the
-- facts that source previously wrote for that date before inserting the batch's.
-- idx_fef_supersede exists to make that delete an index range scan.
--
-- This is a protocol between the importer and the grain, not a column. It is
-- recorded here because a later reader will otherwise wonder why the unique key
-- does not prevent the double count. It cannot, and is not meant to.
--
-- ============================================================================
-- WHY A SEPARATE CATEGORY DIMENSION
-- ============================================================================
--
-- fin_revenue_category is a revenue taxonomy. Putting expense codes into it
-- would let a query group by category_id across both facts and produce a number
-- that is neither income nor spend. Two tables, two vocabularies, no join.
--
-- The seeded list is canonical and source-agnostic: a source's own spelling
-- reaches it through fin_mapping_rule with mapping_type = EXPENSE_CATEGORY,
-- exactly as revenue categories do. UNMAPPED is present for the same reason it
-- is present in revenue -- spend nobody has classified is still spend, and it
-- must stay visible rather than being absorbed into OTHER_EXPENSE.
--
-- AMOUNT IS NULLABLE (ADR-007)
--
-- NULL means the source does not record it. Zero means it was recorded and was
-- zero. A nil return -- "we spent nothing on Tuesday" -- is a
-- fin_daily_data_status row (V129), not a zero-amount fact, because a fact
-- asserts that a transaction happened.
-- ============================================================================

CREATE TABLE fin_expense_category (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    category_code     VARCHAR(50)   NOT NULL  COMMENT 'Canonical code, e.g. SALARIES. Never a source value',
    category_name     VARCHAR(150)  NOT NULL  COMMENT 'Display name; user-facing',
    description       TEXT          NULL      COMMENT 'What belongs in this category, and what does not',
    display_order     INT           NOT NULL DEFAULT 100,
    is_active         TINYINT(1)    NOT NULL DEFAULT 1,
    is_deleted        TINYINT(1)    NOT NULL DEFAULT 0,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        BIGINT        NOT NULL DEFAULT 0,
    updated_by        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fec_code UNIQUE (category_code)
);

CREATE TABLE fin_expense_fact (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id               BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id        BIGINT        NOT NULL  COMMENT 'fin_source_system.id -- which channel produced it. In the grain',
    sync_batch_id           BIGINT        NOT NULL  COMMENT 'fin_sync_batch.id -- which run or submission produced it',
    source_of_truth_version INT           NULL      COMMENT 'fin_source_of_truth_decl.version in force at load (ADR-008)',
    source_record_ref       VARCHAR(200)  NOT NULL  COMMENT 'NOT NULL here, unlike revenue: every expense fact has exactly one source record. e.g. STG:90118 or Expenses!R42',
    expense_date            DATE          NOT NULL  COMMENT 'BUSINESS date the spend belongs to. Never the entry date',
    financial_year          VARCHAR(10)   NOT NULL  COMMENT 'Canonical string, e.g. 2025-26. Never 20252026',
    category_id             BIGINT        NOT NULL  COMMENT 'fin_expense_category.id. Unmappable values land in UNMAPPED, never in OTHER_EXPENSE',
    fund_utilisation_id     BIGINT        NULL      COMMENT 'fin_fund_utilisation.id where this spend was met from a DC-approved fund (V125). NULL for ordinary temple expenditure',
    amount                  DECIMAL(18,2) NULL      COMMENT 'NULL = not recorded by this source. 0 = recorded, and zero (ADR-007)',
    payment_mode            VARCHAR(30)   NOT NULL DEFAULT 'UNRECORDED' COMMENT 'CASH | CARD | UPI | BANK_TRANSFER | CHEQUE | OTHER | UNRECORDED',
    vendor_ref              VARCHAR(200)  NULL      COMMENT 'Payee as recorded. Not a person record, and never linked to one',
    voucher_ref             VARCHAR(100)  NULL      COMMENT 'The temple own voucher or bill number, for reconciliation against its books',
    description             VARCHAR(500)  NULL,
    currency                CHAR(3)       NOT NULL DEFAULT 'INR' COMMENT 'ISO 4217',
    created_at              DATETIME(6)   NOT NULL  COMMENT 'When this row was first loaded. NOT a business date',
    updated_at              DATETIME(6)   NOT NULL  COMMENT 'When it was last restated. NOT a business date',
    PRIMARY KEY (id),
    CONSTRAINT uk_fef_record UNIQUE (source_system_id, source_record_ref)
);

CREATE INDEX idx_fef_temple_fy     ON fin_expense_fact (temple_id, financial_year);
CREATE INDEX idx_fef_temple_date   ON fin_expense_fact (temple_id, expense_date);
CREATE INDEX idx_fef_temple_cat_fy ON fin_expense_fact (temple_id, category_id, financial_year);
CREATE INDEX idx_fef_batch         ON fin_expense_fact (sync_batch_id);
CREATE INDEX idx_fef_fund          ON fin_expense_fact (fund_utilisation_id);
-- Supports the importer supersede-the-day delete described in the header.
CREATE INDEX idx_fef_supersede     ON fin_expense_fact (temple_id, source_system_id, expense_date);

INSERT IGNORE INTO fin_expense_category
    (category_code, category_name, description, display_order, is_active,
     is_deleted, created_at, updated_at, created_by, updated_by)
VALUES
    ('SALARIES', 'Salaries & Honoraria',
     'Payments to temple staff, priests and honorarium recipients.',
     10, 1, 0, NOW(6), NOW(6), 0, 0),
    ('MAINTENANCE', 'Maintenance & Repairs',
     'Upkeep of existing temple property. Creating something new is CONSTRUCTION.',
     20, 1, 0, NOW(6), NOW(6), 0, 0),
    ('CONSTRUCTION', 'Construction & Renovation',
     'Capital work that creates or substantially alters a structure. Usually the spend side of a DC-approved fund.',
     30, 1, 0, NOW(6), NOW(6), 0, 0),
    ('UTILITIES', 'Utilities',
     'Electricity, water, telephone and connectivity.',
     40, 1, 0, NOW(6), NOW(6), 0, 0),
    ('RITUAL_SUPPLIES', 'Ritual Supplies',
     'Materials consumed in worship: flowers, oil, camphor, ritual provisions.',
     50, 1, 0, NOW(6), NOW(6), 0, 0),
    ('PRASADAM_COST', 'Prasadam & Annadana Cost',
     'Cost of producing prasadam and of feeding programmes. The revenue side is PRASADAM_SALE.',
     60, 1, 0, NOW(6), NOW(6), 0, 0),
    ('FESTIVAL', 'Festival & Event Expenditure',
     'Spend attributable to a specific festival or event.',
     70, 1, 0, NOW(6), NOW(6), 0, 0),
    ('ADMINISTRATION', 'Administration',
     'Office running costs, printing, stationery, professional fees.',
     80, 1, 0, NOW(6), NOW(6), 0, 0),
    ('SECURITY', 'Security & Housekeeping',
     'Contracted security, cleaning and crowd management.',
     90, 1, 0, NOW(6), NOW(6), 0, 0),
    ('TRANSPORT', 'Transport & Vehicles',
     'Vehicle running, hire and fuel.',
     100, 1, 0, NOW(6), NOW(6), 0, 0),
    ('STATUTORY', 'Statutory & Remittances',
     'Taxes, levies and amounts remitted onward to another body.',
     110, 1, 0, NOW(6), NOW(6), 0, 0),
    ('OTHER_EXPENSE', 'Other Expenditure',
     'Spend of a known kind that fits no other category. Only for values a reviewer has classified -- never a destination for values nobody has mapped.',
     120, 1, 0, NOW(6), NOW(6), 0, 0),
    ('UNMAPPED', 'Unmapped',
     'A source value that no mapping rule covers. Deliberately visible and deliberately unattractive to report: the figure is real expenditure whose kind is not yet known, and it must be resolved by adding a mapping rule rather than absorbed into Other Expenditure.',
     999, 1, 0, NOW(6), NOW(6), 0, 0);

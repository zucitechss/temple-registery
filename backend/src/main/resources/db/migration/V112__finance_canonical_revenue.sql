-- ============================================================================
-- V112: Canonical Revenue Model (FIN-051, FIN-052)
--
-- The reporting boundary of the finance platform. Everything above this line
-- reads from these tables; nothing above this line ever queries a temple
-- operational database (ADR-001, ADR-002).
--
--   Temple source -> connector -> staging -> validation -> mapping
--                 -> CANONICAL (this migration) -> aggregation -> API -> UI
--
-- Architecture reference:
--   docs/finance/FINANCE_DATA_MODEL.md         sections 5 and 6.1
--   docs/finance/adr/ADR-003 (daily grain, and the uniqueness key)
--   docs/finance/adr/ADR-007 (availability is data; amounts are nullable)
--   docs/finance/adr/ADR-008 (source of truth is declared and versioned)
--   docs/finance/FINANCE_REPORT_CATALOG.md     R1-R8, R26, R27
--
-- What is deliberately NOT here:
--   * No source vocabulary. No SevaCode, ssv_*, BillCancled, Deleteflag,
--     MultiRecpNo, receipt number or source table name appears above staging.
--     A source's own words are translated by fin_mapping_rule before a row
--     reaches this layer.
--   * No devotee identity. No name, address, mobile or email column exists,
--     because no catalogued report needs one and a central government platform
--     holding personal data it cannot use is a liability, not an asset.
--   * No credential, endpoint, connection detail or raw SQL.
--   * No temple id, temple name or category belonging to any one temple. The
--     first onboarded temple is one row of data in a table shaped for many.
--
-- No FOREIGN KEY constraints, per FIN-D-003 and consistent with V10 and V110:
-- relationships are enforced by the application and supported by indexes.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. fin_revenue_category  (FIN-051)
--
--    The canonical income taxonomy, platform-wide and identical for every
--    temple. This table is the answer to "do not simply call every transaction
--    a Seva": a prasadam sale is retail, a donation-box collection is not a
--    purchased ritual, and proceeds from auctioning a donated article are not
--    a donation. Reports that rank "top sevas" are wrong the moment those
--    distinctions are lost.
--
--    Source vocabulary never appears here. A source system's own category
--    values are translated by fin_mapping_rule into one of these codes.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_revenue_category (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    category_code     VARCHAR(50)   NOT NULL  COMMENT 'Canonical code, e.g. SEVA, PRASADAM_SALE. Never a source value',
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
    CONSTRAINT uk_frc_code UNIQUE (category_code)
);


-- ----------------------------------------------------------------------------
-- 2. fin_service_dim  (FIN-051)
--
--    Canonical service identity, scoped per temple: service catalogues are
--    genuinely temple-specific, so this is the one dimension that is not
--    platform-wide. Every service still resolves to a platform-wide category,
--    which is what makes two temples comparable without pretending their
--    service lists are the same.
--
--    rate_card_amount is the published list price. It is stored because it is
--    useful context and never because it is revenue: what a temple charged and
--    what its rate card says legitimately differ, and summing the rate card
--    would report a price list as income.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_service_dim (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id          BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key',
    service_code       VARCHAR(100)  NOT NULL  COMMENT 'Canonical, stable code within this temple',
    service_name_en    VARCHAR(200)  NOT NULL,
    service_name_local VARCHAR(200)  NULL      COMMENT 'Local script (e.g. Kannada); utf8mb4 stores it directly',
    category_id        BIGINT        NOT NULL  COMMENT 'fin_revenue_category.id',
    rate_card_amount   DECIMAL(18,2) NULL      COMMENT 'List price. NEVER revenue, and never summed as such',
    is_active          TINYINT(1)    NOT NULL DEFAULT 1,
    source_record_ref  VARCHAR(200)  NULL      COMMENT 'Opaque provenance handle, e.g. <table>|<key>. Not a query',
    notes              TEXT          NULL,

    is_deleted         TINYINT(1)    NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    created_by         BIGINT        NOT NULL DEFAULT 0,
    updated_by         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fsd_temple_service UNIQUE (temple_id, service_code)
);

CREATE INDEX idx_fsd_category ON fin_service_dim (category_id, is_deleted);
CREATE INDEX idx_fsd_temple   ON fin_service_dim (temple_id, is_active, is_deleted);


-- ----------------------------------------------------------------------------
-- 3. fin_revenue_fact  (FIN-052)
--
--    GRAIN: one row per
--      (temple, business date, service, category, payment mode, counter, operator)
--
--    Not one row per receipt (ADR-003). Measured on the first onboarded source:
--    22,348,125 receipt rows collapse to ~121,242 rows at date x service x
--    counter, a 184x reduction, with every catalogued report still satisfiable.
--    Receipts are never copied centrally; the cancellation detail that carries
--    real audit value is kept separately and in full.
--
--    BUSINESS DATE, NOT MODIFICATION DATE. transaction_date is when the revenue
--    belongs financially. A source may edit a two-year-old receipt today; that
--    corrects an old day, it does not move money into today. The modification
--    axis lives in fin_sync_batch and in created_at / updated_at here, and the
--    two must never be conflated (FIN-D-012).
--
--    AMOUNTS ARE NULLABLE (ADR-007). NULL means "the source does not record
--    this", and NULL cannot be summed, charted or averaged by accident. There
--    is no sentinel zero anywhere in this table: zero asserts that something
--    was measured and found to be nothing.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_revenue_fact (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,

    -- Identity and provenance: "where did this figure come from, and when?"
    temple_id               BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id        BIGINT        NOT NULL  COMMENT 'fin_source_system.id -- which system produced it',
    sync_batch_id           BIGINT        NOT NULL  COMMENT 'fin_sync_batch.id -- which run produced it, and when',
    source_of_truth_version INT           NULL      COMMENT 'fin_source_of_truth_decl.version in force at load (ADR-008)',
    source_record_ref       VARCHAR(200)  NULL      COMMENT 'Opaque group handle for tracing back to staging. Not SQL',

    -- The canonical grain
    transaction_date        DATE          NOT NULL  COMMENT 'BUSINESS date. Never the source modification date',
    financial_year          VARCHAR(10)   NOT NULL  COMMENT 'Canonical string, e.g. 2025-26. Never 20252026',
    service_id              BIGINT        NULL      COMMENT 'fin_service_dim.id. NULL where revenue is not a service (e.g. a donation box)',
    category_id             BIGINT        NOT NULL  COMMENT 'fin_revenue_category.id. Always known: unmappable values land in UNMAPPED, never in OTHER_INCOME',
    payment_mode            VARCHAR(30)   NOT NULL  COMMENT 'CASH | CARD | UPI | BANK_TRANSFER | CHEQUE | OTHER | UNRECORDED',
    payment_mode_confidence VARCHAR(20)   NOT NULL DEFAULT 'RECORDED' COMMENT 'RECORDED | INFERRED -- whether the source stated the mode or the connector derived it',
    counter_ref             VARCHAR(50)   NULL      COMMENT 'Collection point. NULL where the source does not record one',
    operator_ref            VARCHAR(50)   NULL      COMMENT 'Pseudonymous operator handle. Never a person name',

    -- Measures. Every one nullable: absence is not zero.
    transaction_count       BIGINT        NULL      COMMENT 'Receipts in this group. NULL if the source cannot supply a count',
    gross_amount            DECIMAL(18,2) NULL      COMMENT 'Recognised gross for this group',
    cancelled_count         BIGINT        NULL      COMMENT 'NULL = this source does not record cancellations at all. 0 = it does, and there were none',
    cancelled_amount        DECIMAL(18,2) NULL      COMMENT 'Same distinction as cancelled_count: NULL is unknown, 0 is measured',
    quantity                DECIMAL(18,3) NULL      COMMENT 'Items, where counting them is meaningful (e.g. prasadam)',
    currency                CHAR(3)       NOT NULL DEFAULT 'INR' COMMENT 'ISO 4217',

    -- Derived by the database so that no loader can disagree with it.
    -- NULL propagates deliberately: where cancellations are unrecorded, net
    -- revenue is genuinely unknown, and reporting gross as net would assert
    -- that nothing was cancelled. Such a temple is reported from gross_amount
    -- with its capability caveat, not by inventing a net.
    net_amount              DECIMAL(18,2) GENERATED ALWAYS AS (gross_amount - cancelled_amount) STORED
                                                    COMMENT 'gross - cancelled. NULL where cancellations are not recorded',

    created_at              DATETIME(6)   NOT NULL  COMMENT 'When this row was first loaded. NOT a business date',
    updated_at              DATETIME(6)   NOT NULL  COMMENT 'When this row was last restated. NOT a business date',

    -- ------------------------------------------------------------------------
    -- Grain enforcement.
    --
    -- Three of the six grain columns are legitimately nullable, and MySQL and
    -- TiDB both treat NULLs in a UNIQUE index as distinct from one another --
    -- so a unique key over the nullable columns themselves would happily accept
    -- the same fact twice and double the reported revenue. These generated
    -- columns collapse "no service", "no counter" and "no operator" to single
    -- concrete values so the grain is enforced by the database rather than by
    -- the loader remembering to check (FIN-D-018).
    --
    -- They are a storage mechanism only. Nothing reads them: reports read the
    -- nullable columns, where NULL keeps its meaning.
    -- ------------------------------------------------------------------------
    grain_service_key       BIGINT        GENERATED ALWAYS AS (IFNULL(service_id,   0))        STORED,
    grain_counter_key       VARCHAR(50)   GENERATED ALWAYS AS (IFNULL(counter_ref,  '~NONE~')) STORED,
    grain_operator_key      VARCHAR(50)   GENERATED ALWAYS AS (IFNULL(operator_ref, '~NONE~')) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uk_frf_grain UNIQUE (
        temple_id, transaction_date, grain_service_key, category_id,
        payment_mode, grain_counter_key, grain_operator_key)
);

-- Reporting paths. Every one is temple-scoped first: no report, ever, reads
-- across temples without saying which temples it is reading.
CREATE INDEX idx_frf_temple_fy       ON fin_revenue_fact (temple_id, financial_year);
CREATE INDEX idx_frf_temple_date     ON fin_revenue_fact (temple_id, transaction_date);
CREATE INDEX idx_frf_temple_cat_fy   ON fin_revenue_fact (temple_id, category_id, financial_year);
CREATE INDEX idx_frf_temple_service  ON fin_revenue_fact (temple_id, service_id, financial_year);
CREATE INDEX idx_frf_batch           ON fin_revenue_fact (sync_batch_id);


-- ----------------------------------------------------------------------------
-- 4. Canonical category vocabulary
--
--    Platform-wide and seeded here rather than created during onboarding: a
--    taxonomy that each temple invents for itself is not a taxonomy, and two
--    temples whose categories differ cannot be compared in a district total.
--
--    Onboarding maps a temple's own vocabulary onto these codes through
--    fin_mapping_rule. No temple-specific category is ever added to this table.
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO fin_revenue_category
    (category_code, category_name, description, display_order, is_active,
     is_deleted, created_at, updated_at, created_by, updated_by)
VALUES
    ('SEVA', 'Seva',
     'A ritual service performed for a devotee who requested it.',
     10, 1, 0, NOW(6), NOW(6), 0, 0),

    ('SPECIAL_SEVA', 'Special Seva',
     'High-value or occasional ritual service, distinguished from routine seva because ranking the two together hides both.',
     20, 1, 0, NOW(6), NOW(6), 0, 0),

    ('DONATION', 'Donation',
     'Voluntary offering attributable to a donor or a receipt. Not a purchased service.',
     30, 1, 0, NOW(6), NOW(6), 0, 0),

    ('HUNDI_DONATION', 'Hundi Collection',
     'Donation-box collection. A counting event, not a service a devotee bought; it must never be ranked among sevas.',
     40, 1, 0, NOW(6), NOW(6), 0, 0),

    ('PRASADAM_SALE', 'Prasadam Sale',
     'Retail sale of prasadam or related articles. Revenue from a sale, not from a ritual.',
     50, 1, 0, NOW(6), NOW(6), 0, 0),

    ('ENTRY_FEE', 'Entry / Darshan Fee',
     'Charge levied for entry or darshan access, distinct from any ritual service performed.',
     60, 1, 0, NOW(6), NOW(6), 0, 0),

    ('IN_KIND_DONATION', 'In-kind Donation',
     'Non-cash donation carried at a declared or assessed value. Must never be summed with the proceeds of realising the same articles.',
     70, 1, 0, NOW(6), NOW(6), 0, 0),

    ('ASSET_REALISATION', 'Asset Realisation',
     'Proceeds from realising donated assets, e.g. by auction. The realisation of value already recorded as an in-kind donation.',
     80, 1, 0, NOW(6), NOW(6), 0, 0),

    ('RENT_LEASE', 'Rent / Lease',
     'Income from renting or leasing temple property.',
     90, 1, 0, NOW(6), NOW(6), 0, 0),

    ('HALL_BOOKING', 'Hall Booking',
     'Income from booking temple halls or facilities.',
     100, 1, 0, NOW(6), NOW(6), 0, 0),

    ('OTHER_INCOME', 'Other Income',
     'Income of a known kind that fits no other category. Only for values a reviewer has classified -- never a destination for values nobody has mapped.',
     110, 1, 0, NOW(6), NOW(6), 0, 0),

    ('UNMAPPED', 'Unmapped',
     'A source value that no mapping rule covers. Deliberately visible and deliberately unattractive to report: the figure is real revenue whose kind is not yet known, and it must be resolved by adding a mapping rule rather than absorbed into Other Income.',
     999, 1, 0, NOW(6), NOW(6), 0, 0);

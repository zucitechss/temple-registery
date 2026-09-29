-- ============================================================================
-- V139: Service-grained revenue aggregate and period-grained expense aggregate
--       (FR4, FR8, FR10)
--
-- Both mirror fin_agg_revenue_period (V127) deliberately: same period vocabulary,
-- same source-system scope, same nullable measures with companion counts, same
-- reconciliation columns, same calc_version. A reader who understands one
-- understands all three, and RevenueAggregationWriter's shape carries over.
--
-- ============================================================================
-- fin_agg_revenue_service -- AND THE REASON IT IS EMPTY TODAY
-- ============================================================================
--
-- FR10 wants revenue split by seva; FR8 wants the top five. Neither can be
-- answered from fin_agg_revenue_period, which is grained by category, and
-- re-graining that table by service would multiply every existing row.
--
-- This table CANNOT RECEIVE A ROW until service resolution exists.
-- fin_revenue_fact.service_id is nullable by design and null in practice --
-- always, not sometimes: RevenueNormalizer's single row-construction site passes
-- a literal null, fin_service_dim has no writer, and no migration seeds it. That
-- is recorded as FIN-D-069 and it is why FIN-071 was blocked rather than started.
--
-- The table is created now, empty and unread, because it is part of the schema
-- contract two people are building against in parallel: the reporting side needs
-- to know its shape to write queries and fixtures, and the ingestion side needs
-- to know it exists so nothing invents a second one. Creating it does not claim
-- it works.
--
-- Facts with a null service_id are EXCLUDED from this aggregate rather than
-- folded into an "unattributed" bucket. A bucket would be indistinguishable from
-- a real service in the FR8 ranking, and would top it. The report states the
-- excluded share instead, which is the ADR-007 reading: unattributed revenue is
-- not a seva that earned nothing.
--
-- ============================================================================
-- fin_agg_expense_period
-- ============================================================================
--
-- Honest note on necessity: expense volumes are orders of magnitude below
-- revenue, and reading fin_expense_fact directly would serve FR4 for a long
-- time. This table exists for the reason V127 gives -- not speed, but the
-- ability to WITHHOLD. A query-time SUM always reflects the newest facts,
-- including facts the publication gate says must not be published. A stored
-- answer can lag the facts on purpose, and a temple whose expenditure failed
-- reconciliation must keep showing its last good figures rather than gaining
-- unverified ones.
--
-- ============================================================================
-- RECONCILIATION OF MANUALLY ENTERED FIGURES
-- ============================================================================
--
-- Both tables carry reconciliation_status, and for a manual or Excel source it
-- will be NOT_AVAILABLE permanently: reconciliation compares a source's own
-- total against the canonical total, and a self-reported figure has no
-- independent source to compare against. V127 already established that
-- NOT_AVAILABLE publishes with a flag, so nothing is blocked -- but the reports
-- must render that flag rather than showing a self-reported figure with the same
-- badge as a source-verified one.
-- ============================================================================

CREATE TABLE fin_agg_revenue_service (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id                   BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id            BIGINT        NOT NULL  COMMENT 'In the grain: aggregates are NEVER summed across sources by the aggregator',
    period_type                 VARCHAR(20)   NOT NULL  COMMENT 'FINANCIAL_YEAR | MONTH. The PeriodType spelling used by V127 and fin_reconciliation_result',
    period_key                  VARCHAR(20)   NOT NULL  COMMENT '2025-26 for a financial year, 2025-04 for a calendar month',
    service_id                  BIGINT        NOT NULL  COMMENT 'fin_service_dim.id. NOT NULL, and never a null-means-all row: facts without a service are excluded, not bucketed',
    category_id                 BIGINT        NOT NULL  COMMENT 'The service category, carried so FR8 can rank within a kind without a join',
    financial_year              VARCHAR(10)   NOT NULL  COMMENT 'Parent FY, carried on MONTH rows too: it is the scope ReconciliationGate is asked about',
    period_start                DATE          NOT NULL,
    period_end                  DATE          NOT NULL,

    fact_count                  INT           NOT NULL  COMMENT 'Canonical facts that contributed. A roll-up count, NEVER a receipt count',
    transaction_count           BIGINT        NULL      COMMENT 'Summed source transactions. NULL when any contributing fact did not record one',
    facts_with_unknown_count    INT           NOT NULL  COMMENT 'How many contributing facts had no transaction_count. Explains the NULL above',
    gross_amount                DECIMAL(20,2) NULL      COMMENT 'Summed recognised gross. NULL only when no contributing fact recorded any',
    facts_with_unknown_gross    INT           NOT NULL  COMMENT 'The sum above is a floor by this many facts',
    cancelled_amount            DECIMAL(20,2) NULL      COMMENT 'NULL when any contributing fact did not record cancellations at all. 0 means measured, and none',
    quantity                    DECIMAL(20,3) NULL,
    currency                    CHAR(3)       NOT NULL  COMMENT 'Asserted single-valued across the facts rolled up here. A sum across currencies is a nonsense number',
    net_amount                  DECIMAL(20,2) GENERATED ALWAYS AS (gross_amount - cancelled_amount) STORED,

    reconciliation_status       VARCHAR(20)   NOT NULL  COMMENT 'The gate verdict this row was written under: PASSED, or NOT_AVAILABLE published with a flag. FAILED and PENDING never reach this table',
    reconciliation_inherited    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 on MONTH rows: the verdict is the parent financial year, because reconciliation records no month-scoped result',
    calc_version                SMALLINT      NOT NULL,
    computed_at                 DATETIME(6)   NOT NULL  COMMENT 'When this figure was computed. NOT a business date',
    created_at                  DATETIME(6)   NOT NULL,
    updated_at                  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fars_grain UNIQUE (
        temple_id, source_system_id, period_type, period_key, service_id)
);

CREATE INDEX idx_fars_temple_source_fy ON fin_agg_revenue_service (temple_id, source_system_id, financial_year);
-- The FR8 top-five query: one period, ordered by value.
CREATE INDEX idx_fars_rank   ON fin_agg_revenue_service (temple_id, period_type, period_key, net_amount);
CREATE INDEX idx_fars_service ON fin_agg_revenue_service (service_id, period_type, period_key);

CREATE TABLE fin_agg_expense_period (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id                   BIGINT        NOT NULL,
    source_system_id            BIGINT        NOT NULL  COMMENT 'In the grain, for the same reason as V127',
    period_type                 VARCHAR(20)   NOT NULL  COMMENT 'FINANCIAL_YEAR | MONTH',
    period_key                  VARCHAR(20)   NOT NULL,
    category_id                 BIGINT        NOT NULL  COMMENT 'fin_expense_category.id. Never a null-means-all total row',
    payment_mode                VARCHAR(30)   NOT NULL  COMMENT 'UNRECORDED is not CASH',
    financial_year              VARCHAR(10)   NOT NULL,
    period_start                DATE          NOT NULL,
    period_end                  DATE          NOT NULL,

    fact_count                  INT           NOT NULL,
    amount                      DECIMAL(20,2) NULL      COMMENT 'Summed expenditure. NULL only when no contributing fact recorded any',
    facts_with_unknown_amount   INT           NOT NULL  COMMENT 'The sum above is a floor by this many facts',
    fund_backed_amount          DECIMAL(20,2) NULL      COMMENT 'The part met from a DC-approved fund, i.e. facts with a fund_utilisation_id. Lets FR1 box 4 and FR4 agree without a join at read time',
    currency                    CHAR(3)       NOT NULL,

    reconciliation_status       VARCHAR(20)   NOT NULL,
    reconciliation_inherited    TINYINT(1)    NOT NULL DEFAULT 0,
    calc_version                SMALLINT      NOT NULL,
    computed_at                 DATETIME(6)   NOT NULL,
    created_at                  DATETIME(6)   NOT NULL,
    updated_at                  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_faep_grain UNIQUE (
        temple_id, source_system_id, period_type, period_key, category_id, payment_mode)
);

CREATE INDEX idx_faep_temple_source_fy ON fin_agg_expense_period (temple_id, source_system_id, financial_year);
CREATE INDEX idx_faep_report ON fin_agg_expense_period (temple_id, period_type, period_key, category_id);

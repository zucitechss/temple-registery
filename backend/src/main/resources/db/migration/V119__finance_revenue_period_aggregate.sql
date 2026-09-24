-- ============================================================================
-- V119: Period-level revenue aggregates (FIN-070)
--
-- Decisions: ADR-011 (precomputed aggregates, published only after
-- reconciliation passes) and FIN-070B (the grain, the category key, the
-- source-system scope). Deviations from those documents are listed at the
-- bottom of this comment and in FIN-D-068.
--
--   fin_revenue_fact  --(deterministic roll-up)-->  fin_agg_revenue_period
--
-- WHY A TABLE AT ALL
--
-- Not performance. `fin_revenue_fact` is empty in production, no connector
-- exists, and nothing in this project has ever been benchmarked. The reason is
-- that a query-time SUM cannot be withheld: it always reflects the newest
-- facts, including facts ReconciliationGate says must not be published. A
-- stored answer can lag the facts on purpose, and that is the whole point.
--
-- THE GRAIN
--
--   (temple_id, source_system_id, period_type, period_key, category_id,
--    payment_mode)
--
-- Every column NOT NULL, so uk_farp_grain constrains every row. This matters
-- more than it looks: MySQL and TiDB treat NULLs in a UNIQUE index as distinct,
-- so a nullable category_id carrying a "null = all" total row would not be
-- constrained at all, and every aggregation run would INSERT another total
-- instead of replacing one. V112 met that same defect in the fact grain and
-- had to add three generated stand-in columns (FIN-D-018). Totals here are a
-- SUM over category rows at read time; there are no total rows to drift.
--
-- source_system_id is in the grain and is never aggregated across. A temple's
-- figure is the sum of its sources, and the gate's unit of decision is
-- (temple, source, financial year) -- an aggregate that spanned sources could
-- not be gated at all.
--
-- PERIOD TYPES: FINANCIAL_YEAR and MONTH only.
--
-- period_key is 2025-26 for a financial year (canonical, from FinancialYear)
-- and 2025-04 for a calendar month. DAY is deliberately absent: no catalogued
-- report in FINANCE_REPORT_CATALOG.md reads one, and drill-down to a single day
-- goes to the facts, which is what ADR-011 says drill-down is for.
--
-- financial_year is carried on MONTH rows too, and is not redundant with
-- period_key there: it is the scope key ReconciliationGate is asked about, and
-- no reconciliation result exists at month granularity, so a month's verdict is
-- always its parent year's (recorded in reconciliation_inherited).
--
-- NO FOREIGN KEYS, per FIN-D-003 and consistent with V10, V110 and V112.
-- ============================================================================


CREATE TABLE fin_agg_revenue_period (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,

    -- ---- the grain ---------------------------------------------------------
    temple_id                   BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id            BIGINT        NOT NULL  COMMENT 'fin_source_system.id. In the grain: aggregates are NEVER summed across sources',
    period_type                 VARCHAR(20)   NOT NULL  COMMENT 'FINANCIAL_YEAR | MONTH. The PeriodType enum spelling, which fin_reconciliation_result already persists',
    period_key                  VARCHAR(20)   NOT NULL  COMMENT '2025-26 for a financial year, 2025-04 for a calendar month',
    category_id                 BIGINT        NOT NULL  COMMENT 'fin_revenue_category.id. NOT NULL, and never a "null = all" total row -- see the header',
    payment_mode                VARCHAR(30)   NOT NULL  COMMENT 'CASH | CARD | UPI | BANK_TRANSFER | CHEQUE | OTHER | UNRECORDED. UNRECORDED is not CASH',

    -- ---- period identity ---------------------------------------------------
    financial_year              VARCHAR(10)   NOT NULL  COMMENT 'Parent FY, carried on MONTH rows too: it is the scope ReconciliationGate is asked about',
    period_start                DATE          NOT NULL  COMMENT 'Inclusive business-date lower bound of this period',
    period_end                  DATE          NOT NULL  COMMENT 'Inclusive business-date upper bound. Leap-year correct, from FinancialYear/AggregationPeriod',

    -- ---- measures ----------------------------------------------------------
    -- Every nullable measure means "not known", never zero (ADR-007). The two
    -- counter columns beside them say how much of the period the platform could
    -- not measure, so a NULL is explicable rather than merely absent.
    fact_count                  INT           NOT NULL  COMMENT 'Canonical facts that contributed. A roll-up count, NEVER a receipt count',
    transaction_count           BIGINT        NULL      COMMENT 'Summed source transactions. NULL when any contributing fact did not record one -- a partial count presented as a total is the ADR-007 failure',
    facts_with_unknown_count    INT           NOT NULL  COMMENT 'How many contributing facts had no transaction_count. Explains the NULL above',
    gross_amount                DECIMAL(20,2) NULL      COMMENT 'Summed recognised gross. NULL only when no contributing fact recorded any',
    facts_with_unknown_gross    INT           NOT NULL  COMMENT 'Contributing facts with NULL gross_amount: the sum above is a floor by this many facts',
    cancelled_count             BIGINT        NULL      COMMENT 'NULL when any contributing fact did not record cancellations at all. 0 means measured, and none',
    cancelled_amount            DECIMAL(20,2) NULL      COMMENT 'Same distinction as cancelled_count',
    quantity                    DECIMAL(20,3) NULL      COMMENT 'Summed items where counting them is meaningful',
    currency                    CHAR(3)       NOT NULL  COMMENT 'ISO 4217, asserted single-valued across the facts rolled up here. A sum across currencies is a nonsense number, so the aggregator refuses rather than grouping by it',

    -- DECIMAL(20,2) rather than the facts DECIMAL(18,2): this column holds a sum
    -- of many of those, and a width that cannot express the total of its own
    -- inputs is a silent overflow waiting for a large temple.

    -- Derived by the database so that no writer can disagree with it, exactly as
    -- in V112. NULL propagates deliberately: cancelled_amount is NULL whenever
    -- any contributing fact did not record cancellations, so net is genuinely
    -- unknown for the period and reporting gross as net would assert that
    -- nothing was cancelled.
    net_amount                  DECIMAL(20,2) GENERATED ALWAYS AS (gross_amount - cancelled_amount) STORED
                                                        COMMENT 'gross - cancelled. NULL where cancellations are not fully recorded',

    payment_mode_inferred_facts INT           NOT NULL  COMMENT 'Contributing facts whose payment mode a connector derived rather than the source stating it. A flag, never a grouping dimension: confidence is not in the fact grain, so an upsert can change it without changing identity',

    -- ---- why this row is visible -------------------------------------------
    reconciliation_status       VARCHAR(20)   NOT NULL  COMMENT 'The gate verdict this row was written under: PASSED, or NOT_AVAILABLE (published with a flag). FAILED and PENDING never reach this table -- a blocked period writes nothing and leaves the previous row in place',
    reconciliation_inherited    TINYINT(1)    NOT NULL DEFAULT 0
                                                        COMMENT '1 on MONTH rows: the verdict is the parent financial year''s, because reconciliation records no month-scoped result. Never claim a month was verified when it was not',
    calc_version                SMALLINT      NOT NULL  COMMENT 'Which aggregation formula produced this row. If the formula changes, rows computed under the old one are identifiable and rebuildable (FIN-072)',

    computed_at                 DATETIME(6)   NOT NULL  COMMENT 'When this figure was computed. NOT a business date',
    created_at                  DATETIME(6)   NOT NULL,
    updated_at                  DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    -- Idempotency. A re-run replaces a period's row instead of adding a second,
    -- which is the same protection uk_frf_grain gives the facts -- and, as
    -- there, the constraint alone is not enough: a writer that accumulated
    -- instead of assigning would satisfy this key perfectly and double the
    -- period on every replay.
    CONSTRAINT uk_farp_grain UNIQUE (
        temple_id, source_system_id, period_type, period_key,
        category_id, payment_mode)
);

-- The gate's scope, and FIN-072's rebuild scope: "every row of this temple's
-- source for this financial year", which must also find the MONTH rows inside
-- the year. uk_farp_grain cannot serve this -- financial_year is not in it.
CREATE INDEX idx_farp_temple_source_fy ON fin_agg_revenue_period
    (temple_id, source_system_id, financial_year);

-- The reporting path: one temple's period, then narrowing by category and
-- payment mode. Deliberately one index rather than two: payment_mode has at
-- most seven values and does not earn an index of its own, but as a trailing
-- column it makes this one cover the category-and-mode reads as well.
-- Source-system-agnostic on purpose, because a temple-level report sums the
-- temple's sources.
CREATE INDEX idx_farp_report ON fin_agg_revenue_period
    (temple_id, period_type, period_key, category_id, payment_mode);


-- ============================================================================
-- Deviations from FIN-070B section 11.1, each deliberate (FIN-D-068):
--
--   * No `availability` column. Carrying it would need fin_temple_capability,
--     whose uk_ftc_temple_capability is (temple_id, capability) and cannot
--     describe two sources for one temple (limitation 67, open decision D9).
--     Baking a per-temple answer into a per-source row would bake in that
--     conflict. The reporting layer joins the capability itself.
--
--   * No `contributing_batch_ids` column. FIN-070B proposed VARCHAR(500); a
--     truncatable list of ids is the same defect ADR-011's single sync_batch_id
--     had, one size larger. The batches are derivable at any time from
--     FinRevenueFactRepository.findContributingBatchIds, which is where the gate
--     already gets them.
--
--   * `net_amount` IS generated, where FIN-070B said it should be stored.
--     FIN-070B expected net to depend on a capability lookup; deriving
--     cancellation completeness from the contributing facts instead makes plain
--     NULL propagation exactly right, so the database can own it as it does in
--     V112 and no writer can disagree with it.
--
--   * No `agg_run_id` / `fin_agg_run` table yet. A run record only has content
--     once something orchestrates runs, which is FIN-072. Creating an empty
--     audit table now would invite a reader to trust it.
--
-- NOT VERIFIED: TiDB, as for every migration in this project.
-- ============================================================================

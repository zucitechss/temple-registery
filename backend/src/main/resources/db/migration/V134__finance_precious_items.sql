-- ============================================================================
-- V134: Precious items received -- counts, weight, purity and value (FR7)
--
-- FR7 is the one report in the specification whose fields come from two
-- different sources for the same temple on the same day:
--
--   counts     available in the temple software
--   weight     available in the temple software
--   purity     NOT available -- captured through Temple Management input
--   value      NOT available -- captured through Temple Management input
--
-- The specification states this itself. The architecture therefore has to carry
-- per-field provenance on one subject, and this is the table where that stops
-- being a diagram and becomes columns.
--
-- HOW TWO SOURCES SHARE ONE SUBJECT
--
-- Not by two sources writing one row. Each channel writes its own rows, keyed by
-- its own (source_system_id, source_record_ref), and the report joins them on
-- (temple, received_date, metal_type). A connector row carries count and weight
-- with purity and value NULL; a valuation row carries purity and value with
-- count and weight NULL, and names what it is valuing through
-- valuation_of_item_id where the valuer can identify the exact consignment.
--
-- The alternative -- letting the manual channel UPDATE the connector's row --
-- was rejected: it would make a fact mutable by a party that did not produce it,
-- destroy the one-batch-one-writer property every other fact has, and leave
-- source_system_id meaning "whoever wrote last".
--
-- WHY NOT fin_revenue_fact WITH THE IN_KIND_DONATION CATEGORY
--
-- The value of an in-kind donation does belong in revenue, and that category is
-- already seeded for it. What does not belong there is a count of items and a
-- weight in grams: they are not money, they must never be summed with money, and
-- ADR-003 makes the revenue grain daily, which would destroy the per-item detail
-- FR7's grid needs. This table records the objects; the revenue category records
-- their worth as income, and ASSET_REALISATION records the proceeds if they are
-- ever sold. Three different statements, deliberately not one row.
--
-- EVERY MEASURE IS NULLABLE, AND THAT IS THE REPORT
--
-- FR7's value column renders NOT_AVAILABLE with a reason until somebody enters a
-- valuation. It is never inferred from weight -- a gram of unknown purity has no
-- derivable worth, and a plausible wrong figure on a DC dashboard is worse than
-- an honest gap (ADR-007).
--
-- METAL TYPE IS A MAPPED CANONICAL VALUE
--
-- mapping_type = METAL_TYPE has existed in fin_mapping_rule since V118 with
-- nothing to target. This is the target. The column is VARCHAR and the Java
-- MetalType enum is the vocabulary, following payment_mode.
-- ============================================================================

CREATE TABLE fin_precious_item_fact (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    temple_id               BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id        BIGINT        NOT NULL  COMMENT 'fin_source_system.id -- a connector for counts and weight, a manual channel for purity and value',
    sync_batch_id           BIGINT        NOT NULL,
    source_of_truth_version INT           NULL      COMMENT 'fin_source_of_truth_decl.version in force at load (ADR-008)',
    source_record_ref       VARCHAR(200)  NOT NULL  COMMENT 'Row-exact provenance handle. In the grain',

    received_date           DATE          NOT NULL  COMMENT 'BUSINESS date the item was received. Never the valuation date',
    financial_year          VARCHAR(10)   NOT NULL,
    metal_type              VARCHAR(30)   NOT NULL  COMMENT 'GOLD | SILVER | OTHER_PRECIOUS | UNMAPPED. Canonical, resolved via mapping_type = METAL_TYPE',
    item_description        VARCHAR(500)  NULL      COMMENT 'What was received, as the source or the donor described it',

    item_count              INT           NULL      COMMENT 'FR7 counts. NULL = this channel does not record them',
    gross_weight_g          DECIMAL(18,3) NULL      COMMENT 'FR7 weight, grams. NULL = not recorded by this channel',
    purity_karat            DECIMAL(6,3)  NULL      COMMENT 'FR7 purity. NOT available from the known source -- manual input only',
    net_weight_g            DECIMAL(18,3) NULL      COMMENT 'Fine metal content where it has been assayed. Never derived from gross weight and purity by the platform',
    estimated_value         DECIMAL(18,2) NULL      COMMENT 'FR7 value. NULL until somebody values it. NEVER inferred from weight (ADR-007)',
    currency                CHAR(3)       NOT NULL DEFAULT 'INR',
    valuation_source        VARCHAR(50)   NULL      COMMENT 'DECLARED | ASSESSED | MARKET_RATE. Who says so, recorded beside the figure so a declared value is never read as an assessed one',
    valued_on               DATE          NULL      COMMENT 'When the valuation was made. A gold price is only true on a date',
    valuation_of_item_id    BIGINT        NULL      COMMENT 'fin_precious_item_fact.id of the consignment row this valuation refers to, where the valuer could identify it. NULL where the overlay matches only on date and metal type',

    donor_ref               VARCHAR(100)  NULL      COMMENT 'Pseudonymous handle where the source has one. Never a donor name, address or contact',

    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fpif_record UNIQUE (source_system_id, source_record_ref)
);

CREATE INDEX idx_fpif_temple_fy     ON fin_precious_item_fact (temple_id, financial_year, metal_type);
CREATE INDEX idx_fpif_temple_date   ON fin_precious_item_fact (temple_id, received_date);
-- The join that assembles one FR7 row out of two channels.
CREATE INDEX idx_fpif_overlay       ON fin_precious_item_fact (temple_id, received_date, metal_type);
CREATE INDEX idx_fpif_batch         ON fin_precious_item_fact (sync_batch_id);
CREATE INDEX idx_fpif_supersede     ON fin_precious_item_fact (temple_id, source_system_id, received_date);

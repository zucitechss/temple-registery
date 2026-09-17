-- ----------------------------------------------------------------------------
-- V114 — Semantic mapping: what a staged source value MEANS canonically.
--
-- FIN-054. ADR-004 draws the line this migration sits on: anything deciding
-- WHICH ROWS AND WHICH COLUMNS is connector code; anything deciding WHAT A
-- VALUE MEANS is configuration. This file is entirely the second kind.
--
-- Nothing here names a source table, a source column, a temple or a connector.
-- The only source vocabulary in the system lives in fin_mapping_rule ROWS,
-- which are data, and in the connector, which is code. Neither is schema.
--
-- Two things are added:
--   1. fin_mapping_rule.priority — so that "the more specific rule wins"
--      (FIN-D-015) is something the engine can read rather than something a
--      per-temple class has to remember.
--   2. fin_stg_revenue_mapping — one recorded mapping decision per staged row
--      per mapping type, so that every canonical value can be traced back to
--      the rule that produced it and the source value it was produced from.
-- ----------------------------------------------------------------------------


-- ----------------------------------------------------------------------------
-- 1. Rule precedence becomes data
--
--    FIN-D-015 established that source values are namespaced and that the more
--    specific rule wins: SEVA_CODE:430 (donation-box collections) must beat
--    SANNIDHI:KN (the donation bucket it sits inside), or 13 rows averaging
--    over a crore each are reported as ordinary donations and dominate any
--    ranking of services devotees actually bought.
--
--    That decision had no column to live in. It was stated in a migration
--    comment and in one rule's notes, which means the precedence existed only
--    in prose: an engine reading these rows could not tell which was more
--    specific, and would have had to hardcode the answer per temple.
--
--    Higher priority wins. Equal priority with more than one match is an
--    AMBIGUOUS outcome, never an arbitrary pick -- see fin_stg_revenue_mapping.
-- ----------------------------------------------------------------------------
ALTER TABLE fin_mapping_rule
    ADD COLUMN priority INT NOT NULL DEFAULT 100
    COMMENT 'Higher wins where several rules match one record. Equal priority + several matches = AMBIGUOUS, never an arbitrary pick'
    AFTER canonical_value;

-- The lookup index now has to order by priority as well as filter.
CREATE INDEX idx_fmr_priority ON fin_mapping_rule (source_system_id, mapping_type, is_active, priority);


-- ----------------------------------------------------------------------------
-- 2. The first source's override is promoted above its bucket
--
--    Data-only, and expressed as a predicate on the namespace rather than on a
--    temple id: any rule keyed on a specific service code is more specific than
--    one keyed on a coarse income bucket, for every source, by construction.
--    A source that never uses these namespaces is unaffected.
--
--    Left at the default 100 deliberately: the four SANNIDHI bucket rules and
--    the two STREAM rules, which do not overlap each other.
-- ----------------------------------------------------------------------------
UPDATE fin_mapping_rule
   SET priority   = 200,
       updated_at = NOW(6)
 WHERE mapping_type = 'REVENUE_CATEGORY'
   AND source_value LIKE 'SEVA\_CODE:%'
   AND is_deleted = 0;


-- ----------------------------------------------------------------------------
-- 3. fin_stg_revenue_mapping
--
--    One decision per staged row per mapping type: what the source said, which
--    rule answered, what the canonical answer was, and -- when there was no
--    answer -- which of the several different reasons applied.
--
--    Why a separate table rather than columns on fin_stg_revenue. Staging is
--    the evidence of what the source actually sent (FIN-050), and evidence that
--    gets edited every time an interpretation changes is not evidence. Mapping
--    is an interpretation: it changes when somebody adds a rule, and it must be
--    re-runnable against an unchanged record. Keeping the two apart also means
--    SERVICE, PAYMENT_MODE and METAL_TYPE arrive later as more rows rather than
--    as more columns.
--
--    validation_status is NOT extended with a MAPPED value for the same reason.
--    The staged row's status describes the row's own lifecycle; whether an
--    interpretation of it currently exists is a fact about this table.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_stg_revenue_mapping (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,

    -- Provenance. Denormalised from the staged row on purpose: a mapping
    -- decision that cannot say which temple and which run it belongs to cannot
    -- be audited without a join to a table that may since have been purged
    -- (staging retention is unresolved, Q7).
    stg_revenue_id      BIGINT        NOT NULL  COMMENT 'fin_stg_revenue.id -- the record this decision is about',
    temple_id           BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id    BIGINT        NOT NULL  COMMENT 'fin_source_system.id -- whose rule set was consulted',
    sync_batch_id       BIGINT        NOT NULL  COMMENT 'fin_sync_batch.id -- which run produced this decision',
    source_record_ref   VARCHAR(200)  NOT NULL  COMMENT 'Opaque locator in the source, copied so the trail survives a staging purge',

    mapping_type        VARCHAR(40)   NOT NULL  COMMENT 'REVENUE_CATEGORY | SERVICE | PAYMENT_MODE | STATUS | FINANCIAL_YEAR | METAL_TYPE',

    -- What was matched on. Both nullable, because "no field to read" and "field
    -- read but empty" are real, different answers and neither is a value.
    source_field        VARCHAR(200)  NULL      COMMENT 'The staged field the winning or attempted rule reads -- the rule namespace, not a source column name',
    source_value        VARCHAR(200)  NULL      COMMENT 'The value found there, exactly as staged. NULL = nothing was found to map',

    -- What was decided.
    outcome             VARCHAR(30)   NOT NULL  COMMENT 'MAPPED | UNMAPPED | AMBIGUOUS | NOT_APPLICABLE | INVALID_CONFIGURATION',
    canonical_value     VARCHAR(100)  NULL      COMMENT 'Only set for MAPPED, and for UNMAPPED where it is the literal UNMAPPED category. NULL for every outcome that did not decide',
    mapping_rule_id     BIGINT        NULL      COMMENT 'fin_mapping_rule.id that produced canonical_value. NULL where no single rule won',
    rule_priority       INT           NULL      COMMENT 'The winning priority, recorded so a later precedence change is visible against past decisions',
    reason              TEXT          NULL      COMMENT 'Why this outcome, in terms an operator can act on. Never a stack trace, never payload content',

    mapped_at           DATETIME(6)   NOT NULL  COMMENT 'When this decision was made. NOT a business date',
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    -- Idempotency. One current decision per staged row per mapping type, so a
    -- re-run after a rule correction updates the decision instead of adding a
    -- second one. Without this a replayed batch would silently double every
    -- count taken from this table.
    CONSTRAINT uk_fsrm_row_type UNIQUE (stg_revenue_id, mapping_type)
);

-- Driving the stage: which rows of a batch still have no decision.
CREATE INDEX idx_fsrm_batch_type    ON fin_stg_revenue_mapping (sync_batch_id, mapping_type, outcome);
-- "Which source values are unmapped, and how many rows does each cost us?"
CREATE INDEX idx_fsrm_unmapped      ON fin_stg_revenue_mapping (source_system_id, mapping_type, outcome, source_value);
-- Temple-scoped first, like every other reporting path in this schema.
CREATE INDEX idx_fsrm_temple_type   ON fin_stg_revenue_mapping (temple_id, mapping_type, outcome);

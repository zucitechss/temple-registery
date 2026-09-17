-- ============================================================================
-- V113: Revenue Staging (FIN-050)
--
-- The landing table between a connector and the canonical model. Everything a
-- connector extracts for the REVENUE capability lands here first, exactly as it
-- was delivered, and only becomes a canonical fact after validation (FIN-053),
-- mapping (FIN-054) and normalization (FIN-055).
--
--   connector -> STAGING (this migration) -> validation -> mapping
--             -> normalization -> fin_revenue_fact -> aggregation -> API -> UI
--
-- Architecture reference:
--   docs/finance/FINANCE_DATA_MODEL.md         section 4
--   docs/finance/adr/ADR-003 (connectors group at source; daily canonical grain)
--   docs/finance/adr/ADR-006 (incremental sync; the batch owns the change window)
--   docs/finance/adr/ADR-007 (availability is data; absence is never zero)
--
-- WHY A LOOSE PAYLOAD. raw_json holds the connector's row as strings, not typed
-- columns. Real sources contain impossible dates, nulls where the schema
-- promises otherwise and text in numeric columns; typed staging columns would
-- turn each of those into a failure during extraction, losing the whole batch
-- because of one bad row. Here the bad row lands, is rejected with a reason, and
-- the other 40,000 proceed. A source schema change lands as unexpected keys
-- rather than as a crash.
--
-- STAGED IS NOT TRUSTED. Nothing in this table is a financial figure the
-- platform will stand behind. It is what a source said, recorded so that what
-- the platform later publishes can be traced back to it and, if wrong,
-- explained. No report reads this table.
--
-- What is deliberately NOT here:
--   * No source table name, source column name or source-specific column. The
--     connector's own field names live inside raw_json, where the source
--     vocabulary stops; nothing above normalization may read them.
--   * No credential, endpoint, connection detail, transport field or raw SQL.
--     Staging does not know how the data was obtained, which is what keeps it
--     identical for PULL_JDBC, PUSH_AGENT, SOURCE_API and FILE_DROP.
--   * No canonical dimension ids. A staged row has not been mapped yet, and a
--     column for a category would invite somebody to guess one.
--   * No temple-specific anything. The first onboarded temple is data here.
--
-- No FOREIGN KEY constraints, per FIN-D-003 and consistent with V10, V110 and
-- V112: relationships are enforced by the application and supported by indexes.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- fin_stg_revenue
--
--    GRAIN: one row per record a connector delivered for the REVENUE capability
--    within one sync batch -- one `RawRow`, exactly as extract() produced it.
--
--    Staging never merges, splits or reinterprets what it was given. Connectors
--    group at the source (ADR-003), so a delivered record is usually already a
--    source-level grouping rather than a single receipt; staging does not depend
--    on that and must not assume it. Several staged rows may therefore
--    contribute to one canonical daily fact, and that many-to-one collapse
--    happens in normalization (FIN-055), where it is visible and testable --
--    never here, where it would destroy the traceability staging exists for.
--
--    IDEMPOTENCY: uk_fsr_batch_record, one record reference per batch.
--
--    A replay stages the same source records again under a NEW batch, which is
--    legitimate and necessary -- comparing two extractions of the same window is
--    how a restatement is investigated. What the constraint forbids is the same
--    record twice inside ONE batch, which is either a connector defect or a
--    reference that does not identify what it claims to; both silently
--    double-count downstream if allowed to land.
--
--    This is safe because the connector contract makes sourceRecordRef
--    mandatory and non-blank (FIN-030). Note the deliberate contrast with
--    fin_revenue_fact, where NULL-distinct index semantics had to be worked
--    around (FIN-D-018): here every key column is NOT NULL, so the plain
--    constraint means exactly what it says.
-- ----------------------------------------------------------------------------
CREATE TABLE fin_stg_revenue (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,

    -- Provenance. Every one of these is mandatory: a staged figure whose origin
    -- is unknown cannot be investigated, and investigation is the whole purpose
    -- of keeping the row.
    temple_id            BIGINT        NOT NULL  COMMENT 'Registry temple id. Isolation key, never hardcoded anywhere',
    source_system_id     BIGINT        NOT NULL  COMMENT 'fin_source_system.id -- which system produced this record',
    sync_batch_id        BIGINT        NOT NULL  COMMENT 'fin_sync_batch.id -- which extraction delivered it, and over which window',
    source_record_ref    VARCHAR(200)  NOT NULL  COMMENT 'Opaque locator in the source, specific enough for a human to find the original. Never SQL',

    -- The payload, as delivered.
    raw_json             JSON          NOT NULL  COMMENT 'The connector record: its own field names, values as raw strings. Source vocabulary stops here',

    /* Advisory only. Populated when the connector can state the business date
       without interpreting the payload, and NULL when it cannot -- NULL means
       "not declared at extraction", never "no date". Normalization derives the
       authoritative transaction_date from raw_json against the source-of-truth
       declaration; this column exists so a human can find the staged rows for a
       disputed day without parsing JSON, and so that the business axis and the
       extraction axis are visibly separate things in this table. */
    source_business_date DATE          NULL      COMMENT 'Connector-declared business date. ADVISORY -- normalization derives the authoritative one',

    -- Processing state. See the transition table in the block comment below.
    validation_status    VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED | VALID | REJECTED | LOADED',
    rejection_reason     TEXT          NULL      COMMENT 'Human-readable reason this row was rejected. The coded, queryable form is fin_sync_error',

    /* Three timestamps, three different questions, none interchangeable:
         extracted_at  when the connector read the record from the source
         created_at    when this platform stored it
         updated_at    when its processing state last changed
       None of them is a business date. A source editing a two-year-old receipt
       produces a staged row with today's extracted_at and a two-year-old
       business date, and conflating those would restate history (FIN-D-012). */
    extracted_at         DATETIME(6)   NOT NULL  COMMENT 'When the connector read this record. NOT a business date',
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_fsr_batch_record UNIQUE (sync_batch_id, source_record_ref)
);

-- The pipeline's own scan: "give me this batch's rows in state X".
CREATE INDEX idx_fsr_batch_status  ON fin_stg_revenue (sync_batch_id, validation_status);
-- Investigation by temple and disputed day, without reading JSON.
CREATE INDEX idx_fsr_temple_date   ON fin_stg_revenue (temple_id, source_business_date);
-- Following one source record across extractions, which is how a restatement
-- is explained: the same reference, two batches, two payloads.
CREATE INDEX idx_fsr_source_record ON fin_stg_revenue (source_system_id, source_record_ref);


-- ----------------------------------------------------------------------------
-- Processing state: who moves it, and when
--
--   RECEIVED  -> VALID      validation (FIN-053): the row is structurally usable
--   RECEIVED  -> REJECTED   validation (FIN-053): with a reason, and a coded
--                           fin_sync_error row carrying the payload for replay
--   VALID     -> LOADED     the load (FIN-056), after the row has contributed to
--                           a canonical fact through uk_frf_grain
--
--   Transitions are monotonic within a batch: nothing returns to RECEIVED, and
--   a REJECTED row is terminal. Re-processing means a new batch, not a state
--   reset, so the record of what was rejected and why survives the retry.
--
--   FIN-050 creates the states and their meanings; it implements none of the
--   transitions. Every row this migration's table receives is RECEIVED until
--   FIN-053 exists.
--
--   Retention is unresolved (Q7: 30-90 days proposed). No TTL, no purge job and
--   no partitioning is created here -- deleting financial provenance on a
--   schedule nobody has agreed is not a default worth choosing quietly.
-- ----------------------------------------------------------------------------

-- ============================================================================
-- V130: Manual entry and file upload become source systems (Phase 0)
--
-- The Financial Dashboard specification adds two ways for data to enter the
-- platform: a temple-staff form and an Excel upload. Neither is an exception to
-- the ingestion architecture -- each is registered as a `fin_source_system` row
-- like any connector, so that batching, provenance, per-row error recording,
-- capability declaration, the publication gate and freshness tracking all apply
-- to it without being written a second time.
--
-- Three obstacles stood in the way, and this migration removes them.
--
-- 1. connector_bean WAS NOT NULL
--
--    It names the Spring bean of a TempleFinanceConnector. A manual channel has
--    no connector and never will: nothing is extracted, because the data arrives
--    by HTTP. Storing a placeholder bean name would be a lie the registry could
--    later try to resolve. It becomes nullable, and the pairing is now an
--    invariant the application asserts: a PULL_JDBC / PUSH_AGENT / SOURCE_API /
--    FILE_DROP source has a bean; a MANUAL_ENTRY / FILE_UPLOAD source does not.
--
-- 2. fin_sync_batch had no actor
--
--    A scheduled run has no person behind it, and until now every batch was
--    scheduled. A form submission and a file upload both do. `actor_user_id` is
--    the single column that carries "who entered this" into the provenance chain
--    -- fact -> batch -> actor -- without adding an audit table beside the one
--    the batch already is. It stays NULL for scheduler-triggered runs, which is
--    the honest value: nobody entered them.
--
-- 3. uk_ftc_temple_capability allowed one source per capability per temple
--
--    `(temple_id, capability)` was correct while a temple had exactly one source.
--    The specification breaks that on its own terms: FR7 sources precious-metal
--    counts and weight from the temple software and purity and value from staff
--    input -- two sources, one temple, one subject. A temple supplying expenses
--    by both form and spreadsheet breaks it outright.
--
--    The key widens to include source_system_id. This is the same correction
--    V126 made to uk_frf_grain for the same reason, and it has the same
--    consequence: a capability is declared per source, and a reader asking
--    "can this temple answer X?" sums across its sources rather than reading one
--    row. Existing rows are unaffected -- widening a unique key never rejects
--    data that already satisfied the narrower one.
--
-- WHAT THIS MIGRATION DOES NOT DO
--
-- It adds no enum value to a CHECK constraint, because these columns are
-- VARCHAR by design (V118): the Java enum is the vocabulary, and the database
-- stores what it is told. ConnectorType and SourceTechnology gain their new
-- values in code, in the same commit.
-- ============================================================================

ALTER TABLE fin_source_system
    MODIFY COLUMN connector_bean VARCHAR(150) NULL
        COMMENT 'Spring bean name of the TempleFinanceConnector. NULL for MANUAL_ENTRY and FILE_UPLOAD sources, which have no connector to name';

ALTER TABLE fin_sync_batch
    ADD COLUMN actor_user_id BIGINT NULL
        COMMENT 'The person who caused this batch. NULL for SCHEDULER runs -- nobody entered them. Set for TEMPLE_INPUT and EXCEL_UPLOAD'
        AFTER triggered_by;

CREATE INDEX idx_fsb_actor ON fin_sync_batch (actor_user_id, created_at);

ALTER TABLE fin_temple_capability
    DROP INDEX uk_ftc_temple_capability,
    ADD CONSTRAINT uk_ftc_temple_source_capability UNIQUE (temple_id, source_system_id, capability);

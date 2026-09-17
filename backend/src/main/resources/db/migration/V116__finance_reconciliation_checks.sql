-- ============================================================================
-- V116: Make a reconciliation result say what it compared, and stop it being
--       written twice (FIN-060)
--
-- fin_reconciliation_result has existed since V110 and nothing has ever written
-- a row to it. Two things are missing before anything can.
--
-- 1. WHAT WAS COMPARED. The table was shaped for one kind of check: a total the
--    source computed against the same total computed here. That check cannot run
--    today, because no production connector implements sourceTotals() -- and the
--    checks that CAN run are entirely local ones, comparing this platform's own
--    counts against each other.
--
--    Those are worth recording, but they are worth much less, and a table that
--    could not tell them apart would let a run of green local checks be read as
--    "the figures agree with the temple". Nobody has asked the temple.
--    check_type makes the difference queryable instead of implied.
--
--    For a local check, source_total holds the UPSTREAM side and central_total
--    the DOWNSTREAM side -- rows staged against rows that reached a terminal
--    state, for instance. The column comments below are updated to say so,
--    because "source_total" meaning "the count in staging" is exactly the kind of
--    quiet redefinition that makes an audit trail useless later.
--
-- 2. IDEMPOTENCY. Reconciliation must be re-runnable: an operator re-checking a
--    batch, or the orchestrator retrying, must not leave two contradictory rows
--    for the same question. The unique key below makes a re-run replace its own
--    previous answer.
--
-- WHY sync_batch_id STAYS NULLABLE INSIDE THE KEY. NULL is distinct from NULL in
-- a MySQL unique index, which FIN-D-018 had to engineer around with generated
-- columns. Here that behaviour is exactly what is wanted and is used on purpose:
--   - a batch-scoped result (sync_batch_id NOT NULL) de-duplicates on re-run;
--   - a scheduled re-verification of a period (sync_batch_id NULL) appends,
--     which is what an append-only history of a period's figures requires.
-- One constraint, two behaviours, no generated column.
--
-- Forward-only. No data is rewritten: the table is empty, and the default on
-- check_type exists so the statement is safe if it is not.
-- ============================================================================

ALTER TABLE fin_reconciliation_result
    ADD COLUMN check_type VARCHAR(30) NOT NULL DEFAULT 'SOURCE_VS_CENTRAL'
        COMMENT 'STAGE_COMPLETENESS | REJECTION_ACCOUNTING | SOURCE_VS_CENTRAL | SUSPECTED_SOURCE_DELETION'
        AFTER capability;

ALTER TABLE fin_reconciliation_result
    MODIFY COLUMN source_total DECIMAL(20,2) NULL
        COMMENT 'SOURCE_VS_CENTRAL: computed BY THE SOURCE via connector.sourceTotals(). Local checks: the upstream side.',
    MODIFY COLUMN central_total DECIMAL(20,2) NULL
        COMMENT 'SOURCE_VS_CENTRAL: computed from canonical facts. Local checks: the downstream side.';

-- A batch answers each question once. A null batch (scheduled re-verification)
-- is never matched by this key, so those rows accumulate as history.
ALTER TABLE fin_reconciliation_result
    ADD CONSTRAINT uk_frr_batch_check
        UNIQUE (sync_batch_id, capability, check_type, metric, period_type, period_key);

CREATE INDEX idx_frr_check_type ON fin_reconciliation_result (temple_id, check_type, status);

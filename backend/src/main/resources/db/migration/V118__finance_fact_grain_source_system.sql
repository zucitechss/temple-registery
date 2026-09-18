-- ============================================================================
-- V118: The canonical revenue grain gains source_system_id (FIN-052A)
--
-- Decision: FIN-070B D1 (docs/finance/FIN-070B_AGGREGATION_DECISIONS.md §4).
-- Amends, and does not overturn, ADR-003.
--
-- WHAT WAS WRONG
--
-- V112 declared uk_frf_grain over seven columns and omitted source_system_id,
-- although the column is NOT NULL and populated on every row. Two source
-- systems reporting the same temple, business date, service, category, payment
-- mode, counter and operator therefore collided on the key, and the loader's
-- INSERT ... ON DUPLICATE KEY UPDATE *replaced* the first source's figures with
-- the second's -- taking the row's source_system_id and sync_batch_id with it.
-- The first source's revenue was not added; it was gone, and nothing recorded
-- that it had ever been there (limitation 47).
--
-- ADR-003 says a temple's figure for a day is one figure. That stays true --
-- it simply becomes true after summing the temple's sources, rather than at the
-- row. Two systems genuinely did report separately, and the canonical layer now
-- says so instead of silently picking a winner.
--
-- WHY THIS IS SAFE ON A TABLE THAT IS NOT EMPTY
--
-- Adding a column to a UNIQUE key *widens* it. Any pair of rows that was
-- distinct over seven columns is still distinct over eight, so no existing row
-- can violate the new constraint and the ALTER cannot fail on data. And
-- source_system_id is already NOT NULL with a value on every row, so there is
-- nothing to backfill. The only cost that grows with data is the index rebuild,
-- which is an operational window, not a correctness risk.
--
-- WHAT THIS MIGRATION DOES NOT DO
--
--   * It does not recover revenue already destroyed by a prior overwrite. Those
--     figures were replaced in place by ON DUPLICATE KEY UPDATE and no history
--     of the prior values exists. This changes the future, not the past.
--   * It deletes nothing, rewrites no fact, and touches no column value. Only
--     the index definition changes.
--   * It does not make the platform multi-source-capable. fin_temple_capability
--     (uk_ftc_temple_capability) and fin_service_dim (uk_fsd_temple_service)
--     still assume one source per temple; both are deferred under FIN-070B D9
--     because neither is a mechanical widening -- each forces an unanswered
--     question about what a temple-level answer means when two sources disagree.
--
-- COLUMN ORDER
--
-- source_system_id goes second, immediately after temple_id, so the index also
-- serves the (temple_id, source_system_id) prefix that every source-scoped
-- reporting query already filters on -- sumGrossForSourceAndFinancialYear,
-- sumTransactionCountForSourceAndFinancialYear, countFactsWithUnknownTransaction-
-- Count and findContributingBatchIds (the publication gate's own lookup). That
-- prefix did not exist before. No benchmark has been taken and none is claimed.
--
-- The three generated stand-in columns are unchanged and still carry the grain's
-- nullable members, because MySQL and TiDB both treat NULLs in a UNIQUE index as
-- distinct and would otherwise accept the same fact twice (FIN-D-018).
--
-- MySQL 8.0: a single ALTER TABLE may drop an index and add another of the same
-- name, and processes the clauses in the order written. Doing it in one
-- statement rebuilds the table once and leaves no window in which the grain is
-- unenforced. Verified on MySQL 8.0 by FinanceCanonicalRevenueMigrationTest.
-- TiDB is NOT verified -- consistent with every other finance migration.
-- ============================================================================

ALTER TABLE fin_revenue_fact
    DROP INDEX uk_frf_grain,
    ADD CONSTRAINT uk_frf_grain UNIQUE (
        temple_id,
        source_system_id,
        transaction_date,
        grain_service_key,
        category_id,
        payment_mode,
        grain_counter_key,
        grain_operator_key);

-- FIN-140-B — Optimistic locking for administratively edited capability declarations.
--
-- V120 added the same column to fin_source_system and recorded why this table was left out:
-- "fin_temple_capability and fin_source_of_truth_decl deliberately do NOT get a version column
-- here. Slice 140-A does not write either table, and a lock column on a table nothing edits is
-- the dead weight V117's own note describes. They get one when the slice that writes them does."
--
-- This is that slice. A capability declaration is now human-edited, and it is the row that decides
-- whether a metric renders as a figure or as a reason. Two administrators can load the same
-- declaration and save different availabilities; without a version the second write wins silently
-- and the first caller is told it succeeded, so what a District Collector is shown about a temple
-- would be decided by request ordering.
--
-- fin_source_of_truth_decl still has no version column, and still should not: slice 140-B does not
-- write it either. That table is also versioned in the domain sense already -- a new declaration is
-- a new row with a higher `version` and the prior row superseded -- which is a different mechanism
-- from an optimistic lock and must not be confused with one when 140-C arrives.
--
-- Additive and backward compatible. Existing rows take 0, which is what Hibernate expects for a row
-- it has not yet updated, so no backfill is required. The two existing readers -- the dashboard's
-- capability endpoint and the onboarding readiness validator -- are unaffected: neither writes, and
-- neither selects columns by position.
--
-- Rollback: DROP COLUMN version. Safe at any time; nothing outside Hibernate's own locking reads it,
-- no constraint or index depends on it, and no data is derived from it.
--
-- Verified against MySQL 8.0 via Testcontainers. NOT verified against TiDB (HANDOFF limitation 10),
-- though ADD COLUMN with a literal DEFAULT is within the MySQL subset TiDB documents as supported.

ALTER TABLE fin_temple_capability
    ADD COLUMN version INT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock. Incremented by Hibernate on every administrative edit.';

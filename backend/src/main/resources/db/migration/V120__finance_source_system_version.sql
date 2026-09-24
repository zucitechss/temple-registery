-- FIN-140 (slice 140-A) — Optimistic locking for administratively edited source systems.
--
-- Until now nothing outside V111 wrote fin_source_system, so a concurrent edit was not possible.
-- The onboarding API makes the row a human-edited integration control: two administrators can
-- load the same source system and save different connector beans, credential aliases or
-- coverage-relevant metadata, and without a version the second write silently wins while the
-- first caller is told it succeeded. Which external database feeds a temple's published figures
-- would then be decided by request ordering.
--
-- This is the same reasoning, and the same column, as V117 applied to fin_mapping_rule.
--
-- fin_temple_capability and fin_source_of_truth_decl deliberately do NOT get a version column
-- here. Slice 140-A does not write either table, and a lock column on a table nothing edits is
-- the dead weight V117's own note describes. They get one when the slice that writes them does.
--
-- Additive and backward compatible. Existing rows take 0, which is the value Hibernate expects
-- for a row it has not yet updated, so no backfill is required and every existing reader --
-- the report service, the pipeline, the Source Mapper -- is unaffected.
--
-- Verified against MySQL 8.0. NOT verified against TiDB (HANDOFF limitation 10), though
-- ADD COLUMN with a literal DEFAULT is within the MySQL subset TiDB documents as supported.

ALTER TABLE fin_source_system
    ADD COLUMN version INT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock. Incremented by Hibernate on every administrative edit.';

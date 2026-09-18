-- FIN-054A — Optimistic locking for administratively edited mapping rules.
--
-- Until now nothing outside a migration wrote fin_mapping_rule, so a concurrent edit was not
-- possible and a lock column would have been dead weight. The Source Mapper API makes the rule
-- a human-edited financial control: two administrators can load the same rule and save different
-- canonical values, and without a version the second write silently wins while the first caller
-- is told it succeeded. The classification that reaches a published revenue figure would then be
-- decided by request ordering.
--
-- Additive and backward compatible. Existing rows take 0, which is the value Hibernate expects
-- for a row it has not yet updated, so no backfill is required and the pipeline -- which only
-- reads these rows -- is unaffected.
--
-- Verified against MySQL 8.0 and H2. NOT verified against TiDB (see HANDOFF limitation 10),
-- though ADD COLUMN with a literal DEFAULT is within the MySQL subset TiDB documents as supported.

ALTER TABLE fin_mapping_rule
    ADD COLUMN version INT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock. Incremented by Hibernate on every administrative edit.';

-- ============================================================================
-- V123: A seva can be marked special, per temple (FR8, Phase 0)
--
-- FR8 asks for "a list of all sevas on the UI and a checkbox to indicate whether
-- a seva is special for that temple". `fin_service_dim` is already that list:
-- one row per service per temple, keyed by (temple_id, service_code).
--
-- So this is a column, not a table. A separate special-seva configuration table
-- would be a second list of the same sevas, joined on the same key, and the two
-- would disagree the first time a service was renamed or deactivated.
--
-- WHY NOT THE SPECIAL_SEVA REVENUE CATEGORY
--
-- `fin_revenue_category` already seeds SPECIAL_SEVA, and it stays. The two answer
-- different questions and both are needed:
--
--   category = SPECIAL_SEVA   this revenue is of a high-value ritual kind,
--                             decided by a mapping rule against source values
--   is_special = 1            this temple considers this particular seva special,
--                             decided by temple staff ticking a box
--
-- The first classifies money. The second is per-temple configuration over the
-- service catalogue, and FR8's chart is driven by it. Collapsing them would mean
-- a temple could not mark a routine-category seva as locally significant, which
-- is the whole point of the checkbox.
--
-- Default 0: nothing is special until somebody says so. An empty FR8 chart with
-- a "no sevas marked special yet" state is correct on day one, and is not the
-- same thing as a temple having no sevas.
-- ============================================================================

ALTER TABLE fin_service_dim
    ADD COLUMN is_special TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'FR8. Marked by temple management for THIS temple. Independent of the SPECIAL_SEVA revenue category, which classifies money rather than configuring a catalogue'
        AFTER rate_card_amount;

CREATE INDEX idx_fsd_special ON fin_service_dim (temple_id, is_special, is_active, is_deleted);

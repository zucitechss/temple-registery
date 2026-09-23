-- TODO 10 — final Flyway validation. Migration files V13 through V99 are absent from this
-- repository (the migration directory jumps straight from V12 to V100), meaning V1's
-- "initial schema" is a squash of what was originally a much longer migration history.
-- The squash missed 13 columns across 6 tables that later entity code already depends on
-- (Hibernate's own JPA metadata for each column below has existed for some time — see e.g.
-- GovernanceActionHistory.actorRole's own "Added in V42 migration" comment, referencing a
-- migration number that no longer exists in this directory). None of these columns have ever
-- been reachable via Flyway, so this migration adds exactly what the entities require and
-- nothing else — no new columns, no renames of what's already there.

-- declaration_clarifications — DeclarationClarification.sectionName / fieldNamesJson
ALTER TABLE declaration_clarifications
    ADD COLUMN section_name     VARCHAR(100) NULL AFTER message,
    ADD COLUMN field_names_json JSON         NULL AFTER section_name;

-- districts — District.code
ALTER TABLE districts
    ADD COLUMN code VARCHAR(10) NULL AFTER name;

-- entity_versions — EntityVersion's "Phase B" denormalized fields
ALTER TABLE entity_versions
    ADD COLUMN entity_type              VARCHAR(30) NULL AFTER approved_at,
    ADD COLUMN entity_id                BIGINT      NULL AFTER entity_type,
    ADD COLUMN captured_at              DATETIME(6) NULL AFTER entity_id,
    ADD COLUMN captured_by_user_id      BIGINT      NULL AFTER captured_at,
    ADD COLUMN triggering_transition_id BIGINT      NULL AFTER captured_by_user_id;

-- export_job_records — ExportJobRecord.format ("CSV" or "PDF")
ALTER TABLE export_job_records
    ADD COLUMN format VARCHAR(10) NULL AFTER district_id;

-- governance_action_history — GovernanceActionHistory.workflowInstanceId / workflowTransitionId / actorRole
ALTER TABLE governance_action_history
    ADD COLUMN workflow_instance_id  BIGINT      NULL AFTER entity_type,
    ADD COLUMN workflow_transition_id BIGINT     NULL AFTER workflow_instance_id,
    ADD COLUMN actor_role            VARCHAR(32) NULL AFTER action;

-- in_app_notifications — InAppNotification.workflowInstanceId
ALTER TABLE in_app_notifications
    ADD COLUMN workflow_instance_id BIGINT NULL AFTER idempotency_key;

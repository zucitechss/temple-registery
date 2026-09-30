-- V119: Backfill asset_declarations.submitted_at for declarations submitted before this fix.
--
-- GovernanceWorkflowServiceImpl.submitDeclaration() set submitted_by but never
-- submitted_at, so every declaration submitted so far shows a blank "Submitted"
-- date on both the TA and DC sides. The workflow engine's own copy of this
-- timestamp (workflow_instances.submitted_at) was always set correctly on the
-- SUBMITTED transition — backfill from there.

UPDATE asset_declarations ad
JOIN workflow_instances wi
  ON wi.entity_type = 'DECLARATION' AND wi.entity_id = ad.id
SET ad.submitted_at = wi.submitted_at
WHERE ad.submitted_at IS NULL
  AND wi.submitted_at IS NOT NULL;

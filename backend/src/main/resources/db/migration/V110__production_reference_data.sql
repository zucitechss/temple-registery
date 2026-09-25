-- ============================================================================
-- V110: Production reference data
--
-- WHY THIS FILE EXISTS (audit finding C-5)
--   This migration seeds the mandatory reference data every environment
--   needs to boot: SLA/feature system_config and notification routing rules.
--   Nothing else. It originally also re-supplied a Karnataka geo hierarchy
--   (to backfill what a since-removed dev-only seed migration used to insert
--   in every environment), but that was removed once the geo hierarchy
--   became purely administrator-managed — see below.
--
-- ONE LOCATION SET, EVERY ENVIRONMENT
--   classpath:db/migration is the only Flyway location in dev, test AND
--   production (application.yml / application-dev.yml / application-prod.yml
--   all agree). There is no separate dev/test seed path anymore: no dummy
--   users, no geo hierarchy, no sample temples, in any environment. A fresh
--   dev database starts exactly like a fresh production database.
--
-- GEO HIERARCHY IS NOT SEEDED HERE ON PURPOSE
--   States/cities/districts/taluks/hoblis are administrator-configurable
--   master data, not application-startup reference data: GeoController's
--   POST /states, /cities, /districts, /taluks, /hoblis endpoints
--   (SUPER_ADMIN only) and the frontend's GeoManagementPage already let an
--   administrator build this hierarchy from an empty table.
--
-- IDEMPOTENCY
--   * system_config has UNIQUE KEY uq_sc_key(config_key) -> INSERT IGNORE is a no-op.
--   * notification_rules has NO unique key, so INSERT IGNORE would DUPLICATE
--     rows. It uses an explicit anti-join instead. Do not "simplify" it.
--
-- Initial SUPER_ADMIN provisioning is seed data, not code: see
-- db/migration/V116__bootstrap_super_admin.sql. No credential is ever
-- committed — V116's values are Flyway placeholders resolved from
-- APP_BOOTSTRAP_ADMIN_* environment variables at deploy time.
-- ============================================================================

SET @ts  = NOW();
SET @sys = 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- SYSTEM CONFIG — SLA and feature defaults
-- ─────────────────────────────────────────────────────────────────────────────

INSERT IGNORE INTO system_config
    (config_key, config_value, data_type, category, description, is_active, created_by, updated_by)
VALUES
('sla.declaration.review_days',     '30',   'INTEGER', 'SLA',          'Days DC has to review a submitted declaration before overdue', 1, @sys, @sys),
('sla.temple_profile.review_days',  '14',   'INTEGER', 'SLA',          'Days DC has to review a temple profile staging submission',    1, @sys, @sys),
('sla.clarification.response_days', '7',    'INTEGER', 'SLA',          'Days TA has to respond to a clarification request',           1, @sys, @sys),
('notification.email.enabled',      'false','BOOLEAN', 'NOTIFICATION', 'Global toggle to enable/disable email notifications',         1, @sys, @sys),
('notification.inapp.enabled',      'true', 'BOOLEAN', 'NOTIFICATION', 'Global toggle to enable/disable in-app notifications',       1, @sys, @sys),
('feature.evidence_pack.enabled',   'true', 'BOOLEAN', 'FEATURE',      'Enable evidence pack export for AUDITOR role',               1, @sys, @sys),
('feature.observation.enabled',     'true', 'BOOLEAN', 'FEATURE',      'Enable observation creation by AUDITOR role',                1, @sys, @sys);

-- ─────────────────────────────────────────────────────────────────────────────
-- NOTIFICATION RULES — canonical workflow event routing
--
-- template_key values are already in the post-V106 canonical "email/" form.
-- On a fresh production database V106 runs BEFORE this migration and updates
-- zero rows, so the canonical values must be inserted correctly here.
--
-- notification_rules has no unique constraint, so this is an explicit anti-join
-- rather than INSERT IGNORE, guarding against a second run ever duplicating
-- these eleven rows.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO notification_rules
    (event_type, entity_type, action, recipient_type, channel, priority,
     template_key, description, created_by, updated_by)
SELECT d.event_type, d.entity_type, d.action, d.recipient_type, d.channel,
       d.priority, d.template_key, d.description, @sys, @sys
FROM (
    SELECT 'WORKFLOW_TRANSITION' AS event_type, '*' AS entity_type, 'SUBMIT' AS action, 'DC' AS recipient_type, 'BOTH'   AS channel, 'MEDIUM' AS priority, 'email/submission-notification'          AS template_key, 'Notifies DC when TA submits a record for review'      AS description
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','APPROVE',               'TA','BOTH',   'HIGH',   'email/approval-notification',            'Notifies TA when DC approves their submission'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','RE_APPROVE',            'TA','BOTH',   'HIGH',   'email/approval-notification',            'Notifies TA when DC re-approves after edit'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','REJECT',                'TA','BOTH',   'HIGH',   'email/rejection-notification',           'Notifies TA when DC rejects their submission'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','REQUEST_CLARIFICATION', 'TA','BOTH',   'HIGH',   'email/clarification-request',            'Notifies TA when DC requests clarification'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','RESPOND_CLARIFICATION', 'DC','BOTH',   'MEDIUM', 'email/clarification-response',           'Notifies DC when TA responds to clarification'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','RESUBMIT',              'DC','BOTH',   'MEDIUM', 'email/resubmission-notification',        'Notifies DC when TA resubmits after clarification'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','EDIT_APPROVED',         'DC','IN_APP', 'MEDIUM', 'email/edit-after-approval-notification', 'Notifies DC when TA edits an approved record'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','BEGIN_REVIEW',          'TA','IN_APP', 'LOW',    'email/review-started-notification',      'Notifies TA when DC begins reviewing their submission'
    UNION ALL SELECT 'SYSTEM','DECLARATION','FLAG_OVERDUE',             'TA','BOTH',   'HIGH',   'email/overdue-notification',             'Notifies TA when a submission is flagged as overdue'
    UNION ALL SELECT 'WORKFLOW_TRANSITION','*','WITHDRAW',              'DC','IN_APP', 'LOW',    'email/withdrawal-notification',          'Notifies DC when TA withdraws a submission'
) AS d
LEFT JOIN notification_rules nr
       ON nr.event_type     = d.event_type
      AND nr.entity_type    = d.entity_type
      AND nr.action         = d.action
      AND nr.recipient_type = d.recipient_type
WHERE nr.id IS NULL;

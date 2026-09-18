-- ============================================================================
-- V110: Production reference data
--
-- WHY THIS FILE EXISTS (audit finding C-5)
--   V2__master_seed_data.sql mixed three different things in one migration:
--     1. Karnataka geo hierarchy      — required in every environment
--     2. SLA / feature system_config  — required in every environment
--     3. notification routing rules   — required in every environment
--     4. SEVEN dev user accounts, including a SUPER_ADMIN whose password
--        is a well-known literal published in this repository
--   Because Flyway ran classpath:db/migration unconditionally, item 4 was
--   created in production too.
--
-- THE SPLIT
--   V2 (and the V100/V101/V102 temple fixtures) moved BYTE-IDENTICALLY to
--   classpath:db/seed, which only the dev/test profiles load. Their version
--   numbers, descriptions and checksums are unchanged, so existing databases
--   that already applied them still validate.
--   This migration re-supplies items 1-3 — and nothing else — on the
--   production-only classpath:db/migration path.
--
-- IDEMPOTENCY (this migration runs on dev databases too, after V2)
--   * states/cities/districts/taluks/hoblis insert explicit PKs -> INSERT IGNORE
--     collides on PRIMARY KEY and is a no-op.
--   * system_config has UNIQUE KEY uq_sc_key(config_key) -> INSERT IGNORE is a no-op.
--   * notification_rules has NO unique key, so INSERT IGNORE would DUPLICATE
--     rows. It uses an explicit anti-join instead. Do not "simplify" it.
--
-- Initial production SUPER_ADMIN provisioning is handled by
-- BootstrapAdminInitializer, driven by APP_BOOTSTRAP_ADMIN_* environment
-- variables. No credential is ever seeded from SQL.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;
SET @ts  = NOW();
SET @sys = 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- GEO HIERARCHY — Karnataka (5 divisions, 20 districts, key taluks + hoblis)
-- ─────────────────────────────────────────────────────────────────────────────

INSERT IGNORE INTO states (id, name, code, is_deleted, created_at, updated_at, created_by, updated_by)
VALUES (1, 'Karnataka', 'KA', 0, @ts, @ts, @sys, @sys);

-- Revenue divisions (cities)
INSERT IGNORE INTO cities (id, state_id, name, is_deleted, created_at, updated_at, created_by, updated_by) VALUES
(1, 1, 'Mysuru',       0, @ts, @ts, @sys, @sys),
(2, 1, 'Bengaluru',    0, @ts, @ts, @sys, @sys),
(3, 1, 'Kalaburagi',   0, @ts, @ts, @sys, @sys),
(4, 1, 'Belagavi',     0, @ts, @ts, @sys, @sys),
(5, 1, 'Shivamogga',   0, @ts, @ts, @sys, @sys);

-- Districts
INSERT IGNORE INTO districts (id, city_id, name, is_deleted, created_at, updated_at, created_by, updated_by) VALUES
-- Mysuru Division
(1,  1, 'Mysuru',          0, @ts, @ts, @sys, @sys),
(2,  1, 'Mandya',          0, @ts, @ts, @sys, @sys),
(3,  1, 'Chamarajanagar',  0, @ts, @ts, @sys, @sys),
(4,  1, 'Kodagu',          0, @ts, @ts, @sys, @sys),
(5,  1, 'Hassan',          0, @ts, @ts, @sys, @sys),
-- Bengaluru Division
(6,  2, 'Bengaluru Urban', 0, @ts, @ts, @sys, @sys),
(7,  2, 'Bengaluru Rural', 0, @ts, @ts, @sys, @sys),
(8,  2, 'Ramanagara',      0, @ts, @ts, @sys, @sys),
(9,  2, 'Tumkuru',         0, @ts, @ts, @sys, @sys),
(10, 2, 'Kolar',           0, @ts, @ts, @sys, @sys),
-- Kalaburagi Division
(11, 3, 'Kalaburagi',      0, @ts, @ts, @sys, @sys),
(12, 3, 'Bidar',           0, @ts, @ts, @sys, @sys),
(13, 3, 'Raichur',         0, @ts, @ts, @sys, @sys),
-- Belagavi Division
(14, 4, 'Belagavi',        0, @ts, @ts, @sys, @sys),
(15, 4, 'Vijayapura',      0, @ts, @ts, @sys, @sys),
(16, 4, 'Bagalkot',        0, @ts, @ts, @sys, @sys),
(17, 4, 'Dharwad',         0, @ts, @ts, @sys, @sys),
-- Shivamogga Division
(18, 5, 'Shivamogga',      0, @ts, @ts, @sys, @sys),
(19, 5, 'Davanagere',      0, @ts, @ts, @sys, @sys),
(20, 5, 'Chitradurga',     0, @ts, @ts, @sys, @sys);

-- Taluks (representative set; 3 per district for key districts)
INSERT IGNORE INTO taluks (id, district_id, name, is_deleted, created_at, updated_at, created_by, updated_by) VALUES
-- Mysuru district (1)
(1,  1, 'Mysuru',            0, @ts, @ts, @sys, @sys),
(2,  1, 'Hunsur',            0, @ts, @ts, @sys, @sys),
(3,  1, 'Krishnarajanagara', 0, @ts, @ts, @sys, @sys),
-- Mandya district (2)
(4,  2, 'Mandya',            0, @ts, @ts, @sys, @sys),
(5,  2, 'Nagamangala',       0, @ts, @ts, @sys, @sys),
(6,  2, 'Malavalli',         0, @ts, @ts, @sys, @sys),
-- Chamarajanagar district (3)
(7,  3, 'Chamarajanagar',    0, @ts, @ts, @sys, @sys),
(8,  3, 'Gundlupet',         0, @ts, @ts, @sys, @sys),
-- Kodagu district (4)
(9,  4, 'Madikeri',          0, @ts, @ts, @sys, @sys),
(10, 4, 'Virajpet',          0, @ts, @ts, @sys, @sys),
-- Hassan district (5)
(11, 5, 'Hassan',            0, @ts, @ts, @sys, @sys),
(12, 5, 'Arsikere',          0, @ts, @ts, @sys, @sys),
-- Bengaluru Urban (6)
(13, 6, 'Bengaluru North',   0, @ts, @ts, @sys, @sys),
(14, 6, 'Bengaluru South',   0, @ts, @ts, @sys, @sys),
(15, 6, 'Bengaluru East',    0, @ts, @ts, @sys, @sys),
-- Tumkuru (9)
(16, 9, 'Tumkuru',           0, @ts, @ts, @sys, @sys),
(17, 9, 'Tiptur',            0, @ts, @ts, @sys, @sys),
-- Dharwad (17)
(18, 17, 'Dharwad',          0, @ts, @ts, @sys, @sys),
(19, 17, 'Hubli',            0, @ts, @ts, @sys, @sys),
-- Shivamogga (18)
(20, 18, 'Shivamogga',       0, @ts, @ts, @sys, @sys);

-- Hoblis (2 per key taluk)
INSERT IGNORE INTO hoblis (id, taluk_id, name, is_deleted, created_at, updated_at, created_by, updated_by) VALUES
(1,  1,  'Chamundi Hobli',         0, @ts, @ts, @sys, @sys),
(2,  1,  'Kasaba Hobli',           0, @ts, @ts, @sys, @sys),
(3,  2,  'Hunsur Hobli',           0, @ts, @ts, @sys, @sys),
(4,  3,  'Krishnarajanagara Hobli',0, @ts, @ts, @sys, @sys),
(5,  4,  'Mandya Hobli',           0, @ts, @ts, @sys, @sys),
(6,  4,  'Pandavapura Hobli',      0, @ts, @ts, @sys, @sys),
(7,  7,  'Chamarajanagar Hobli',   0, @ts, @ts, @sys, @sys),
(8,  9,  'Madikeri Hobli',         0, @ts, @ts, @sys, @sys),
(9,  11, 'Hassan Hobli',           0, @ts, @ts, @sys, @sys),
(10, 13, 'Bengaluru North Hobli',  0, @ts, @ts, @sys, @sys),
(11, 14, 'Bengaluru South Hobli',  0, @ts, @ts, @sys, @sys),
(12, 16, 'Tumkuru Hobli',          0, @ts, @ts, @sys, @sys),
(13, 18, 'Dharwad Hobli',          0, @ts, @ts, @sys, @sys),
(14, 20, 'Shivamogga Hobli',       0, @ts, @ts, @sys, @sys);

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
-- rather than INSERT IGNORE. On a dev database (where V2 already inserted these
-- eleven rules) the LEFT JOIN matches and nothing is inserted.
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

SET FOREIGN_KEY_CHECKS = 1;

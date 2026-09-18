-- ============================================================================
-- V2: Master Seed Data — Temple Registry
-- Minimal production-style seed data required to bootstrap the system.
-- Password for all dev accounts: password123
-- BCrypt hash (strength 12): $2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS
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
-- DEV USERS  (password: password123)
-- ─────────────────────────────────────────────────────────────────────────────

INSERT IGNORE INTO users (
    id, username, email, password_hash, full_name, mobile, role,
    is_active, district_id, city_id, temple_id,
    mfa_type, aadhaar_verified, failed_login_count, is_deleted,
    created_at, updated_at, created_by, updated_by
) VALUES
-- Super Admin
(1, 'super_admin', 'admin@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'Super Administrator', '9000000001', 'SUPER_ADMIN',
 1, NULL, NULL, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys),

-- District Collector — Mysuru (district 1)
(2, 'dc_mysuru', 'dc@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'District Collector Mysuru', '9000000002', 'DISTRICT_COLLECTOR',
 1, 1, 1, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys),

-- DC Staff — Mysuru (district 1)
(3, 'dc_staff_mysuru', 'staff@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'DC Staff Mysuru', '9000000003', 'DC_STAFF',
 1, 1, 1, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys),

-- Temple Authority (no temple linked yet — assigned by admin)
(4, 'ta_chamundi', 'ta@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'Temple Authority Chamundi', '9000000004', 'TEMPLE_AUTHORITY',
 1, NULL, NULL, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys),

-- Auditor
(5, 'auditor_dev', 'auditor@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'Auditor Dev', '9000000005', 'AUDITOR',
 1, NULL, NULL, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys),

-- Viewer
(6, 'viewer_dev', 'viewer@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'Viewer Dev', '9000000006', 'VIEWER',
 1, NULL, NULL, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys),

-- DC Bengaluru
(7, 'dc_bengaluru', 'dc_blr@templeregistry.dev',
 '$2a$12$Jh5AwLDdLLbuS.Otkb0AK.2aFveu2vjEBCOYKEDKEYk757A2yNrQS',
 'District Collector Bengaluru Urban', '9000000007', 'DISTRICT_COLLECTOR',
 1, 6, 2, NULL, 'NONE', 1, 0, 0, @ts, @ts, @sys, @sys);

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
-- ─────────────────────────────────────────────────────────────────────────────

INSERT IGNORE INTO notification_rules
    (event_type, entity_type, action, recipient_type, channel, priority, template_key, description, created_by, updated_by)
VALUES
('WORKFLOW_TRANSITION','*','SUBMIT',                'DC','BOTH',   'MEDIUM', 'submission-notification',          'Notify DC on any TA submission',              @sys, @sys),
('WORKFLOW_TRANSITION','*','APPROVE',               'TA','BOTH',   'HIGH',   'approval-notification',            'Notify TA on DC approval',                   @sys, @sys),
('WORKFLOW_TRANSITION','*','RE_APPROVE',            'TA','BOTH',   'HIGH',   'approval-notification',            'Notify TA on DC re-approval',                @sys, @sys),
('WORKFLOW_TRANSITION','*','REJECT',                'TA','BOTH',   'HIGH',   'rejection-notification',           'Notify TA on DC rejection',                  @sys, @sys),
('WORKFLOW_TRANSITION','*','REQUEST_CLARIFICATION', 'TA','BOTH',   'HIGH',   'clarification-request',            'Notify TA on clarification request',         @sys, @sys),
('WORKFLOW_TRANSITION','*','RESPOND_CLARIFICATION', 'DC','BOTH',   'MEDIUM', 'clarification-response',           'Notify DC on TA clarification response',     @sys, @sys),
('WORKFLOW_TRANSITION','*','RESUBMIT',              'DC','BOTH',   'MEDIUM', 'resubmission-notification',        'Notify DC on TA resubmission',               @sys, @sys),
('WORKFLOW_TRANSITION','*','EDIT_APPROVED',         'DC','IN_APP', 'MEDIUM', 'edit-after-approval-notification', 'Notify DC on TA edit of approved record',    @sys, @sys),
('WORKFLOW_TRANSITION','*','BEGIN_REVIEW',          'TA','IN_APP', 'LOW',    'review-started-notification',      'Notify TA when DC opens record for review',  @sys, @sys),
('SYSTEM','DECLARATION', 'FLAG_OVERDUE',            'TA','BOTH',   'HIGH',   'overdue-notification',             'Notify TA on declaration overdue',           @sys, @sys),
('WORKFLOW_TRANSITION','*','WITHDRAW',              'DC','IN_APP', 'LOW',    'withdrawal-notification',          'Notify DC on TA withdrawal',                 @sys, @sys);

SET FOREIGN_KEY_CHECKS = 1;

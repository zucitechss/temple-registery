-- ============================================================================
-- V102: Fix DRAFT Trusts and Complete Missing Data
-- Fixes from V101 INSERT IGNORE skips due to NOT NULL DB constraints:
--   1. Insert 4 DRAFT trusts (T14/T16/T17/T18) with proper values
--   2. Fix physical_verification_status for DRAFT declarations (T17/T18)
-- IDEMPOTENT: INSERT IGNORE / ON DUPLICATE KEY UPDATE throughout.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- Encrypted PANs and bank accounts for DRAFT trusts (AES-256-GCM, dev key)
SET @PAN_T14  = 'QUFCQ1MxMDE0WgAALqpJOKS3nF2+0usQ0iPfmJfhgSWpsvftVoE=';
SET @BANK_T14 = 'MTAxNDAxMDE0MAAAg5IsG0JMVAK0Ra6DDEzQR/d4M9HLcXjNU5E=';
SET @PAN_T16  = 'QUFCQ1MxMDE2WgAA8vJ65V6GTBBBxk019mEv0CtOM3KUuOxfi10=';
SET @BANK_T16 = 'MTAxNjAxMDE2MAAAHK9cmfI8+BYXlT6oe44Nxp6H6qHgfrWlX+8=';
SET @PAN_T17  = 'QUFCQ1MxMDE3WgAAp31YuNqdrMgSUUR2gRxY91w9iTtiS2/eVt4=';
SET @BANK_T17 = 'MTAxNzAxMDE3MAAAp90rOnTdZIBeG27nodjoUQaM91CkP5PTgNI=';
SET @PAN_T18  = 'QUFCQ1MxMDE4WgAANU9Y7MQATZeZRhktUXwvAumZ9UWtHVK8w8M=';
SET @BANK_T18 = 'MTAxODAxMDE4MAAAfb6dL2K9Lg3EuT4f6Ia3ErQnI1EYfhkHxgI=';

-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 1 — FIX DRAFT TRUSTS (ids 116-119)
-- Use ON DUPLICATE KEY UPDATE to either insert fresh or fix any empty-string rows.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT INTO trusts
    (id, lock_version, temple_id, trust_name, trust_registration_number,
     date_of_registration, registering_authority, trust_type,
     trust_pan_number, bank_account_number, bank_name_and_branch,
     annual_income, status, dissolution_date, dissolution_reason,
     system_verification_status, approved_data,
     is_deleted, created_by)
VALUES
    -- T14: Sri Tarakeshwara Temple · Davanagere
    (116,0,113,'Sri Tarakeshwara Devasthana Trust','KA/DVG/TR/2022/001',
     '2022-06-01','Sub Registrar, Davanagere','SINGLE_TRUSTEE',
     @PAN_T14,@BANK_T14,'State Bank of India, Davanagere Branch',
     600000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,113),
    -- T16: Sri Ucchangi Bhairaveshwara Temple · Chitradurga
    (117,0,115,'Ucchangi Bhairaveshwara Kshetra Trust','KA/CTD/TR/2020/001',
     '2020-08-10','Sub Registrar, Chitradurga','MULTI_TRUSTEE',
     @PAN_T16,@BANK_T16,'Canara Bank, Chitradurga Branch',
     280000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,115),
    -- T17: Sri Gurudwara Nanak Jhira Sahib · Bidar
    (118,0,116,'Gurudwara Nanak Jhira Sahib Management Committee','KA/BDR/TR/2018/001',
     '2018-04-01','Sub Registrar, Bidar','MULTI_TRUSTEE',
     @PAN_T17,@BANK_T17,'Punjab National Bank, Bidar Branch',
     1500000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,116),
    -- T18: Sri Manavi Veerbhadreshwara Temple · Raichur
    (119,0,117,'Sri Veerbhadreshwara Devasthana Trust','KA/RCR/TR/2021/001',
     '2021-01-15','Sub Registrar, Raichur','SINGLE_TRUSTEE',
     @PAN_T18,@BANK_T18,'Karnataka Bank, Raichur Branch',
     190000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,117)
ON DUPLICATE KEY UPDATE
    trust_registration_number = VALUES(trust_registration_number),
    date_of_registration      = VALUES(date_of_registration),
    registering_authority     = VALUES(registering_authority),
    trust_pan_number          = VALUES(trust_pan_number),
    bank_account_number       = VALUES(bank_account_number),
    bank_name_and_branch      = VALUES(bank_name_and_branch);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 2 — FIX DRAFT DECLARATIONS physical_verification_status
-- T17 (id=119) and T18 (id=120) declarations need physical_verification_status
-- set to empty string or a valid enum value (NOT NULL constraint in live DB).
-- Use ON DUPLICATE KEY UPDATE to fix if already inserted with ''.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT INTO asset_declarations
    (id, lock_version, temple_id, district_id, financial_year, version_number,
     status, annual_income, annual_expenditure, gold_grams, buildings_sqft,
     due_date, submitted_at, submitted_by, reviewed_at, reviewed_by,
     acknowledgement_number, acknowledged_at,
     clarification_round, is_overdue, overdue_flagged_at,
     physical_verification_status,
     is_deleted, created_by)
VALUES
    (119,0,116,12,'2025-26',1,'DRAFT',
     NULL,NULL,NULL,NULL,
     '2026-03-31',NULL,NULL,NULL,NULL,
     NULL,NULL,
     0,0,NULL,'NOT_INITIATED',0,116),
    (120,0,117,13,'2025-26',1,'DRAFT',
     NULL,NULL,NULL,NULL,
     '2026-03-31',NULL,NULL,NULL,NULL,
     NULL,NULL,
     0,0,NULL,'NOT_INITIATED',0,117)
ON DUPLICATE KEY UPDATE
    physical_verification_status = VALUES(physical_verification_status);

SET FOREIGN_KEY_CHECKS = 1;

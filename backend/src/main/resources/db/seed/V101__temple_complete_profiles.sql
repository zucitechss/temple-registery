-- ============================================================================
-- V101: Complete Temple Profiles for all 20 seed temples
-- Adds: Draft trusts (T14/T16/T17/T18) · Board members · Employees (all 20) ·
--       Contractors (19 temples) · Profile staging (missing 8 temples) ·
--       Profile current (T04/T09/T16) · Declarations (T17/T18) ·
--       WF instances + transitions for new data · Search summary updates
-- IDEMPOTENT: INSERT IGNORE throughout.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 1 — DRAFT TRUSTS for T14, T16, T17, T18
-- These 4 temples had no trust in V100. DRAFT = TA started filling data.
-- PAN/bank left NULL (not yet entered in draft state).
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO trusts
    (id, lock_version, temple_id, trust_name, trust_registration_number,
     date_of_registration, registering_authority, trust_type,
     trust_pan_number, bank_account_number, bank_name_and_branch,
     annual_income, status, dissolution_date, dissolution_reason,
     system_verification_status, approved_data,
     is_deleted, created_by)
VALUES
    -- T14: Sri Tarakeshwara Temple · Davanagere
    (116,0,113,'Sri Tarakeshwara Devasthana Trust',NULL,
     NULL,NULL,'SINGLE_TRUSTEE',
     NULL,NULL,NULL,
     600000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,113),
    -- T16: Sri Ucchangi Bhairaveshwara Temple · Chitradurga
    (117,0,115,'Ucchangi Bhairaveshwara Kshetra Trust',NULL,
     NULL,NULL,'MULTI_TRUSTEE',
     NULL,NULL,NULL,
     280000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,115),
    -- T17: Sri Gurudwara Nanak Jhira Sahib · Bidar
    (118,0,116,'Gurudwara Nanak Jhira Sahib Management Committee',NULL,
     NULL,NULL,'MULTI_TRUSTEE',
     NULL,NULL,NULL,
     1500000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,116),
    -- T18: Sri Manavi Veerbhadreshwara Temple · Raichur
    (119,0,117,'Sri Veerbhadreshwara Devasthana Trust',NULL,
     NULL,NULL,'SINGLE_TRUSTEE',
     NULL,NULL,NULL,
     190000.00,'ACTIVE',NULL,NULL,NULL,NULL,
     0,117);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 2 — BOARD MEMBERS for new DRAFT trusts (ids 232-239)
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO board_members
    (id, lock_version, trust_id, full_name, aadhaar_encrypted, aadhaar_hash,
     aadhaar_last4, designation, appointment_date, contact_number,
     is_current, is_verified_by_dc, is_deleted, created_by)
VALUES
    -- Trust 116 (T14 Tarakeshwara)
    (232,0,116,'Somappa Jois',NULL,NULL,'1033','Chairman','2022-06-01','9001000232',1,0,0,113),
    (233,0,116,'Malathi Reddy',NULL,NULL,'1034','Secretary','2022-06-01','9001000233',1,0,0,113),
    -- Trust 117 (T16 Ucchangi)
    (234,0,117,'Channappa Nayak',NULL,NULL,'1035','Chairman','2020-08-10','9001000234',1,0,0,115),
    (235,0,117,'Kalavathi Bai',NULL,NULL,'1036','Secretary','2020-08-10','9001000235',1,0,0,115),
    -- Trust 118 (T17 Nanak Jhira)
    (236,0,118,'Gurdev Singh Bhatia',NULL,NULL,'1037','Chairman','2018-04-01','9001000236',1,0,0,116),
    (237,0,118,'Paramjit Kaur',NULL,NULL,'1038','Secretary','2018-04-01','9001000237',1,0,0,116),
    -- Trust 119 (T18 Manavi)
    (238,0,119,'Veeranna Goud',NULL,NULL,'1039','Chairman','2021-01-15','9001000238',1,0,0,117),
    (239,0,119,'Shanthamma Reddy',NULL,NULL,'1040','Secretary','2021-01-15','9001000239',1,0,0,117);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 3 — TRUST WORKFLOW INSTANCES for new DRAFT trusts (ids 216-219)
-- DRAFT trusts have no transitions (TA hasn't submitted yet).
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO workflow_instances
    (id, entity_type, entity_id, status, sub_status, lock_version, version_number,
     current_actor_role, created_by_user_id, temple_id, district_id,
     submitted_at, status_updated_at, is_deleted, created_by)
VALUES
    (216,'TRUST',116,'DRAFT',NULL,0,1,NULL,113,113,19,NULL,NULL,0,113),
    (217,'TRUST',117,'DRAFT',NULL,0,1,NULL,115,115,20,NULL,NULL,0,115),
    (218,'TRUST',118,'DRAFT',NULL,0,1,NULL,116,116,12,NULL,NULL,0,116),
    (219,'TRUST',119,'DRAFT',NULL,0,1,NULL,117,117,13,NULL,NULL,0,117);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 4 — EMPLOYEES (ids 300-349)
-- 2-4 per temple; realistic Kannada/Karnataka names.
-- PRIEST = ARCHAKA designation; hereditary=1 for lineage priests.
-- Temples T15 (SUSPENDED) and T18 (FROZEN) → ON_LEAVE status.
-- Temple T20 (ARCHIVED) → RETIRED with date_of_leaving.
-- Salary grades: Grade A temples = SG-3/SG-4; Grade B = SG-2; Grade C = SG-1.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO employees
    (id, temple_id, employee_ref, full_name, employee_type, designation,
     date_of_joining, salary_grade, mobile, address, is_hereditary,
     status, date_of_leaving, is_deleted, created_by)
VALUES
    -- T01: Sri Chamundeshwari Temple (temple_id=100) · Grade A · 4 employees
    (300,100,'EMP-T01-001','Ramachandra Jois','PRIEST','Head Archaka (Hereditary)',
     '1998-04-01','SG-4','9800010001',
     'Chamundi Betta, Mysuru-570010',1,'ACTIVE',NULL,0,100),
    (301,100,'EMP-T01-002','Subrahmanya Dikshit','PRIEST','Junior Archaka',
     '2010-06-15','SG-3','9800010002',
     'J.P. Nagar, Mysuru-570008',0,'ACTIVE',NULL,0,100),
    (302,100,'EMP-T01-003','Lakshmi Narayana','ADMINISTRATIVE','Temple Manager',
     '2005-08-01','SG-4','9800010003',
     'Kuvempunagar, Mysuru-570023',0,'ACTIVE',NULL,0,100),
    (303,100,'EMP-T01-004','Muniswamy B.','MAINTENANCE','Maintenance Supervisor',
     '2012-03-10','SG-2','9800010004',
     'Chamundi Betta, Mysuru-570010',0,'ACTIVE',NULL,0,100),

    -- T02: Sri Nanjundeshwara Temple (temple_id=101) · Grade A · 3 employees
    (304,101,'EMP-T02-001','Venkatesh Jois','PRIEST','Head Archaka (Hereditary)',
     '2002-07-01','SG-4','9800010005',
     'Nanjangud Town, Mysuru-571301',1,'ACTIVE',NULL,0,101),
    (305,101,'EMP-T02-002','Kamala Bai S.','ADMINISTRATIVE','Administrative Officer',
     '2008-01-20','SG-3','9800010006',
     'Nanjangud Town, Mysuru-571301',0,'ACTIVE',NULL,0,101),
    (306,101,'EMP-T02-003','Rangaswamy D.','MAINTENANCE','Temple Caretaker',
     '2015-11-05','SG-2','9800010007',
     'Nanjangud Town, Mysuru-571301',0,'ACTIVE',NULL,0,101),

    -- T03: Sri Ranganathaswamy Temple (temple_id=102) · Grade A · 3 employees
    (307,102,'EMP-T03-001','Srinivasa Aiyangar','PRIEST','Head Archaka (Hereditary)',
     '1995-02-14','SG-4','9800010008',
     'Srirangapatna, Mandya-571438',1,'ACTIVE',NULL,0,102),
    (308,102,'EMP-T03-002','Meenakshi Sundaram','ADMINISTRATIVE','Office Manager',
     '2011-09-01','SG-3','9800010009',
     'Srirangapatna, Mandya-571438',0,'ACTIVE',NULL,0,102),
    (309,102,'EMP-T03-003','Krishnaswamy R.','MAINTENANCE','Maintenance Staff',
     '2018-04-15','SG-2','9800010010',
     'Srirangapatna, Mandya-571438',0,'ACTIVE',NULL,0,102),

    -- T04: Sri Mahadeshwara Temple (temple_id=103) · Grade A · 3 employees
    (310,103,'EMP-T04-001','Basavaraj Swami','PRIEST','Head Archaka (Hereditary)',
     '1990-01-01','SG-4','9800010011',
     'MM Hills, Chamarajanagar-571490',1,'ACTIVE',NULL,0,103),
    (311,103,'EMP-T04-002','Giriyamma S.','ADMINISTRATIVE','Administrative Officer',
     '2007-06-01','SG-3','9800010012',
     'MM Hills, Chamarajanagar-571490',0,'ACTIVE',NULL,0,103),
    (312,103,'EMP-T04-003','Hanumanthappa K.','SECURITY','Security Guard',
     '2019-01-10','SG-1','9800010013',
     'Kollegala, Chamarajanagar-571440',0,'ACTIVE',NULL,0,103),

    -- T05: Sri Omkareshwara Temple (temple_id=104) · Grade B · 2 employees
    (313,104,'EMP-T05-001','Kariappa Pujari','PRIEST','Head Archaka',
     '2004-03-15','SG-3','9800010014',
     'Madikeri, Kodagu-571201',1,'ACTIVE',NULL,0,104),
    (314,104,'EMP-T05-002','Devakiamma N.','ADMINISTRATIVE','Temple Secretary',
     '2014-07-01','SG-2','9800010015',
     'Madikeri, Kodagu-571201',0,'ACTIVE',NULL,0,104),

    -- T06: Hoysaleshwara Temple (temple_id=105) · Grade A · 3 employees
    (315,105,'EMP-T06-001','Narayana Jois V.','PRIEST','Head Archaka (Hereditary)',
     '1999-08-01','SG-4','9800010016',
     'Halebidu, Hassan-573121',1,'ACTIVE',NULL,0,105),
    (316,105,'EMP-T06-002','Hemavathi Rao','ADMINISTRATIVE','Heritage Site Manager',
     '2009-04-01','SG-4','9800010017',
     'Hassan-573201',0,'ACTIVE',NULL,0,105),
    (317,105,'EMP-T06-003','Kempaiah B.','MAINTENANCE','Stone Conservation Technician',
     '2016-02-01','SG-3','9800010018',
     'Halebidu, Hassan-573121',0,'ACTIVE',NULL,0,105),

    -- T07: Sri Chennakesava Temple (temple_id=106) · Grade B · 3 employees
    (318,106,'EMP-T07-001','Venkatesha Shastri','PRIEST','Head Archaka (Hereditary)',
     '2001-04-14','SG-3','9800010019',
     'Belur, Hassan-573115',1,'ACTIVE',NULL,0,106),
    (319,106,'EMP-T07-002','Savitha M.','ADMINISTRATIVE','Administrative Officer',
     '2013-08-01','SG-2','9800010020',
     'Belur, Hassan-573115',0,'ACTIVE',NULL,0,106),
    (320,106,'EMP-T07-003','Lingappa D.','MAINTENANCE','Site Maintenance Staff',
     '2020-01-15','SG-2','9800010021',
     'Belur, Hassan-573115',0,'ACTIVE',NULL,0,106),

    -- T08: Sri Kadu Malleshwara Temple (temple_id=107) · Grade B · 2 employees
    (321,107,'EMP-T08-001','Murugan Swami','PRIEST','Head Archaka',
     '2006-11-01','SG-3','9800010022',
     'Malleshwaram, Bengaluru-560003',0,'ACTIVE',NULL,0,107),
    (322,107,'EMP-T08-002','Suma Devi K.','ADMINISTRATIVE','Office Secretary',
     '2017-05-01','SG-2','9800010023',
     'Malleshwaram, Bengaluru-560003',0,'ACTIVE',NULL,0,107),

    -- T09: Sri Gangadhareshwara Cave Temple (temple_id=108) · Grade B · 2 employees
    (323,108,'EMP-T09-001','Parameshwara Jois','PRIEST','Head Archaka (Hereditary)',
     '1996-07-15','SG-3','9800010024',
     'Gavipuram, Bengaluru-560019',1,'ACTIVE',NULL,0,108),
    (324,108,'EMP-T09-002','Raghavendra S.','MAINTENANCE','Cave Temple Caretaker',
     '2020-09-01','SG-2','9800010025',
     'Gavipuram, Bengaluru-560019',0,'ACTIVE',NULL,0,108),

    -- T10: ISKCON Temple (temple_id=109) · Grade A · 4 employees
    (325,109,'EMP-T10-001','Gopal Krishna Das','PRIEST','Head Pujari',
     '2005-03-22','SG-4','9800010026',
     'Rajajinagar, Bengaluru-560010',0,'ACTIVE',NULL,0,109),
    (326,109,'EMP-T10-002','Radha Mohan Das','PRIEST','Junior Pujari',
     '2015-06-01','SG-3','9800010027',
     'Rajajinagar, Bengaluru-560010',0,'ACTIVE',NULL,0,109),
    (327,109,'EMP-T10-003','Priya Devi Dasi','ADMINISTRATIVE','Temple Administrator',
     '2012-01-10','SG-4','9800010028',
     'Rajajinagar, Bengaluru-560010',0,'ACTIVE',NULL,0,109),
    (328,109,'EMP-T10-004','Nanda Kumar Das','SECURITY','Head of Security',
     '2018-08-15','SG-3','9800010029',
     'Rajajinagar, Bengaluru-560010',0,'ACTIVE',NULL,0,109),

    -- T11: Sri Siddaganga Mutt (temple_id=110) · Grade A · 3 employees
    (329,110,'EMP-T11-001','Basavanna Swami','PRIEST','Head Archaka (Hereditary)',
     '2000-01-26','SG-4','9800010030',
     'Siddaganga, Tumkuru-572104',1,'ACTIVE',NULL,0,110),
    (330,110,'EMP-T11-002','Veeranna G.','ADMINISTRATIVE','Mutt Administrator',
     '2010-04-01','SG-3','9800010031',
     'Siddaganga, Tumkuru-572104',0,'ACTIVE',NULL,0,110),
    (331,110,'EMP-T11-003','Mallikarjuna H.','MAINTENANCE','Mutt Maintenance Supervisor',
     '2014-09-15','SG-2','9800010032',
     'Siddaganga, Tumkuru-572104',0,'ACTIVE',NULL,0,110),

    -- T12: Sri Siddaroodha Swami Temple (temple_id=111) · Grade B · 2 employees
    (332,111,'EMP-T12-001','Virupaksha Swami','PRIEST','Head Archaka',
     '2003-10-08','SG-3','9800010033',
     'Dharwad-580001',0,'ACTIVE',NULL,0,111),
    (333,111,'EMP-T12-002','Shantabai Kulkarni','ADMINISTRATIVE','Temple Administrator',
     '2011-03-15','SG-2','9800010034',
     'Dharwad-580001',0,'ACTIVE',NULL,0,111),

    -- T13: Sri Keladi Rameshwara Temple (temple_id=112) · Grade B · 2 employees
    (334,112,'EMP-T13-001','Parameshwara Bhatta','PRIEST','Head Archaka (Hereditary)',
     '1998-02-14','SG-3','9800010035',
     'Keladi, Shivamogga-577115',1,'ACTIVE',NULL,0,112),
    (335,112,'EMP-T13-002','Parvathi Bai M.','ADMINISTRATIVE','Office Secretary',
     '2016-07-01','SG-2','9800010036',
     'Keladi, Shivamogga-577115',0,'ACTIVE',NULL,0,112),

    -- T14: Sri Tarakeshwara Temple (temple_id=113) · Grade C · 2 employees
    (336,113,'EMP-T14-001','Somanna Jois','PRIEST','Head Archaka',
     '2010-04-01','SG-2','9800010037',
     'Davanagere-577001',0,'ACTIVE',NULL,0,113),
    (337,113,'EMP-T14-002','Jayamma R.','ADMINISTRATIVE','Temple Secretary',
     '2018-02-01','SG-1','9800010038',
     'Davanagere-577001',0,'ACTIVE',NULL,0,113),

    -- T15: Sri Sharana Basaveshwara Temple (temple_id=114) · Grade B · 2 ON_LEAVE (SUSPENDED)
    (338,114,'EMP-T15-001','Channabasavaiah','PRIEST','Head Archaka (Hereditary)',
     '2005-05-20','SG-3','9800010039',
     'Kalaburagi-585101',1,'ON_LEAVE',NULL,0,114),
    (339,114,'EMP-T15-002','Usha Patil','ADMINISTRATIVE','Administrative Officer',
     '2012-10-01','SG-2','9800010040',
     'Kalaburagi-585101',0,'ON_LEAVE',NULL,0,114),

    -- T16: Sri Ucchangi Bhairaveshwara Temple (temple_id=115) · Grade C · 2 employees
    (340,115,'EMP-T16-001','Siddanaik Pujari','PRIEST','Head Archaka',
     '2009-09-01','SG-2','9800010041',
     'Ucchangi, Chitradurga-577501',0,'ACTIVE',NULL,0,115),
    (341,115,'EMP-T16-002','Gouramma S.','MAINTENANCE','Temple Caretaker',
     '2019-03-15','SG-1','9800010042',
     'Ucchangi, Chitradurga-577501',0,'ACTIVE',NULL,0,115),

    -- T17: Gurudwara Nanak Jhira Sahib (temple_id=116) · Grade B · 2 employees
    (342,116,'EMP-T17-001','Gurpreet Singh Bhatia','ADMINISTRATIVE','Head Granthi (Religious)',
     '2008-04-10','SG-3','9800010043',
     'Bidar-585401',0,'ACTIVE',NULL,0,116),
    (343,116,'EMP-T17-002','Harpreet Kaur','MAINTENANCE','Sevadar (Service Staff)',
     '2015-01-20','SG-2','9800010044',
     'Bidar-585401',0,'ACTIVE',NULL,0,116),

    -- T18: Sri Manavi Veerbhadreshwara Temple (temple_id=117) · Grade C · 2 ON_LEAVE (FROZEN)
    (344,117,'EMP-T18-001','Veerabhadra Swami','PRIEST','Head Archaka',
     '2007-06-01','SG-2','9800010045',
     'Manavi, Raichur-584123',0,'ON_LEAVE',NULL,0,117),
    (345,117,'EMP-T18-002','Nagaraj B.','MAINTENANCE','Temple Maintenance',
     '2016-11-01','SG-1','9800010046',
     'Manavi, Raichur-584123',0,'ON_LEAVE',NULL,0,117),

    -- T19: Sri Balagangadharanatha Temple (temple_id=118) · Grade C · 2 employees
    (346,118,'EMP-T19-001','Shiva Pujari K.','PRIEST','Head Archaka',
     '2012-04-01','SG-2','9800010047',
     'Nelamangala, Bengaluru Rural-562123',0,'ACTIVE',NULL,0,118),
    (347,118,'EMP-T19-002','Mangalamma Naidu','ADMINISTRATIVE','Temple Secretary',
     '2020-07-15','SG-1','9800010048',
     'Nelamangala, Bengaluru Rural-562123',0,'ACTIVE',NULL,0,118),

    -- T20: Sri Kambadahalli Bahubali Temple (temple_id=119) · Grade C · 2 RETIRED (ARCHIVED)
    (348,119,'EMP-T20-001','Jainacharya Gomatesh','PRIEST','Head Pujari',
     '1988-03-01','SG-2','9800010049',
     'Kambadahalli, Mandya-571422',0,'RETIRED','2020-05-01',0,119),
    (349,119,'EMP-T20-002','Vinayaka Shastri','ADMINISTRATIVE','Temple Administrator',
     '1995-06-01','SG-2','9800010050',
     'Kambadahalli, Mandya-571422',0,'RETIRED','2020-05-01',0,119);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 5 — CONTRACTORS (ids 300-324)
-- 1-2 per temple; T20 (ARCHIVED) has no active contractors.
-- service_type: stored as VARCHAR (converter converts enum to string).
-- payment_status: stored as VARCHAR (converter converts enum to string).
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO contractors
    (id, temple_id, company_name, gst_number, service_type,
     contract_reference, work_order_date, contract_start_date, contract_end_date,
     contract_value, payment_status, document_ids,
     is_verified_by_dc, is_payment_pending, is_deleted, created_by)
VALUES
    -- T01: Sri Chamundeshwari Temple (temple_id=100)
    (300,100,'M/s Chamundi Hill Civil Works Pvt Ltd','29AAACM0001A1Z5','CIVIL_WORKS',
     'WO/KA-MYS-0001/2025/01','2025-01-15','2025-02-01','2026-01-31',
     4500000.00,'PENDING',NULL,1,0,0,100),
    (301,100,'Sri Chamundi Utsav Enterprises','29AABCS0001B1Z3','EVENTS',
     'WO/KA-MYS-0001/2025/02','2025-03-01','2025-03-15','2025-04-30',
     850000.00,'COMPLETED',NULL,1,0,0,100),

    -- T02: Sri Nanjundeshwara Temple (temple_id=101)
    (302,101,'Nanjangud Temple Maintenance Services','29AABCN0002A1Z1','OTHER',
     'WO/KA-MYS-0002/2025/01','2025-04-01','2025-04-15','2026-04-14',
     320000.00,'PENDING',NULL,0,0,0,101),

    -- T03: Sri Ranganathaswamy Temple (temple_id=102)
    (303,102,'M/s Srirangapatna Heritage Constructions','29AAACS0003A1Z4','CIVIL_WORKS',
     'WO/KA-MDY-0001/2025/01','2025-02-10','2025-03-01','2025-12-31',
     2800000.00,'PENDING',NULL,1,0,0,102),
    (304,102,'Mandya Catering & Annadana Services','29AABCM0003B1Z2','CATERING',
     'WO/KA-MDY-0001/2025/02','2025-01-01','2025-01-15','2025-12-31',
     480000.00,'COMPLETED',NULL,1,0,0,102),

    -- T04: Sri Mahadeshwara Temple (temple_id=103)
    (305,103,'Hill Temple Infrastructure Developers','29AABCH0004A1Z6','CIVIL_WORKS',
     'WO/KA-CJN-0001/2025/01','2025-06-01','2025-07-01','2026-06-30',
     1600000.00,'PENDING',NULL,0,0,0,103),

    -- T05: Sri Omkareshwara Temple (temple_id=104)
    (306,104,'Kodagu Upkeep Services','29AABCK0005A1Z9','OTHER',
     'WO/KA-KDG-0001/2025/01','2025-03-01','2025-03-15','2026-03-14',
     180000.00,'PENDING',NULL,0,0,0,104),

    -- T06: Hoysaleshwara Temple (temple_id=105)
    (307,105,'M/s Karnataka Heritage Restoration Works','29AAACK0006A1Z7','CIVIL_WORKS',
     'WO/KA-HSN-0001/2025/01','2025-01-20','2025-02-01','2026-07-31',
     6200000.00,'PENDING',NULL,1,0,0,105),
    (308,105,'Halebidu Sound & Light Event Management','29AABCH0006B1Z5','EVENTS',
     'WO/KA-HSN-0001/2025/02','2025-09-01','2025-10-01','2025-10-31',
     420000.00,'COMPLETED',NULL,1,0,0,105),

    -- T07: Sri Chennakesava Temple (temple_id=106)
    (309,106,'Belur Heritage Stone Restoration Co.','29AABCB0007A1Z3','CIVIL_WORKS',
     'WO/KA-HSN-0002/2025/01','2025-03-15','2025-04-01','2026-03-31',
     3800000.00,'PENDING',NULL,1,0,0,106),
    (310,106,'Karnataka Tourism Event Coordinators','29AABCK0007B1Z1','EVENTS',
     'WO/KA-HSN-0002/2025/02','2025-12-01','2025-12-15','2025-12-31',
     290000.00,'COMPLETED',NULL,1,0,0,106),

    -- T08: Sri Kadu Malleshwara Temple (temple_id=107)
    (311,107,'Malleshwaram Electricals Pvt Ltd','29AABCM0008A1Z8','ELECTRICAL',
     'WO/KA-BLR-0001/2025/01','2025-05-01','2025-05-15','2025-11-14',
     550000.00,'PENDING',NULL,0,0,0,107),

    -- T09: Sri Gangadhareshwara Cave Temple (temple_id=108)
    (312,108,'Bengaluru Heritage Upkeep Services','29AABCB0009A1Z6','OTHER',
     'WO/KA-BLR-0002/2025/01','2025-04-01','2025-04-15','2026-04-14',
     240000.00,'PENDING',NULL,0,0,0,108),

    -- T10: ISKCON Temple (temple_id=109)
    (313,109,'SecurePro Security Services Pvt Ltd','29AAACS0010A1Z4','SECURITY',
     'WO/KA-BLR-0003/2025/01','2025-01-01','2025-01-15','2025-12-31',
     1800000.00,'PENDING',NULL,1,0,0,109),
    (314,109,'ISKCON Prasadam Catering Cooperative','29AAACI0010B1Z2','CATERING',
     'WO/KA-BLR-0003/2025/02','2025-01-01','2025-01-15',NULL,
     3600000.00,'PENDING',NULL,1,0,0,109),

    -- T11: Sri Siddaganga Mutt (temple_id=110)
    (315,110,'Tumkuru Construction & Maintenance Co.','29AAACT0011A1Z9','CIVIL_WORKS',
     'WO/KA-TMK-0001/2025/01','2025-02-01','2025-03-01','2025-12-31',
     2200000.00,'PENDING',NULL,1,0,0,110),
    (316,110,'Siddaganga Mutt Maintenance Trust','29AABCS0011B1Z7','OTHER',
     'WO/KA-TMK-0001/2025/02','2025-01-01','2025-01-01','2025-12-31',
     560000.00,'PENDING',NULL,1,0,0,110),

    -- T12: Sri Siddaroodha Swami Temple (temple_id=111)
    (317,111,'Dharwad Renovation & Upkeep Pvt Ltd','29AAACD0012A1Z5','CIVIL_WORKS',
     'WO/KA-DWD-0001/2025/01','2025-03-01','2025-04-01','2026-03-31',
     1100000.00,'PENDING',NULL,0,0,0,111),

    -- T13: Sri Keladi Rameshwara Temple (temple_id=112)
    (318,112,'Shivamogga Heritage Maintenance Services','29AABCS0013A1Z3','OTHER',
     'WO/KA-SHV-0001/2025/01','2025-02-15','2025-03-01','2026-02-28',
     280000.00,'PENDING',NULL,0,0,0,112),

    -- T14: Sri Tarakeshwara Temple (temple_id=113)
    (319,113,'Davanagere Temple Construction Works','29AAACD0014A1Z1','CIVIL_WORKS',
     'WO/KA-DVG-0001/2025/01','2025-07-01','2025-08-01','2026-07-31',
     720000.00,'PENDING',NULL,0,0,0,113),

    -- T15: Sri Sharana Basaveshwara Temple (temple_id=114) · SUSPENDED; contractor engaged before suspension
    (320,114,'Kalaburagi Structural Repairs Pvt Ltd','29AAACK0015A1Z8','CIVIL_WORKS',
     'WO/KA-KLB-0001/2024/01','2024-03-01','2024-04-01','2024-12-31',
     950000.00,'COMPLETED',NULL,1,0,0,114),

    -- T16: Sri Ucchangi Bhairaveshwara Temple (temple_id=115)
    (321,115,'Chitradurga Temple Works Contractor','29AABCC0016A1Z6','CIVIL_WORKS',
     'WO/KA-CTD-0001/2025/01','2025-08-01','2025-09-01','2026-08-31',
     460000.00,'PENDING',NULL,0,0,0,115),

    -- T17: Gurudwara Nanak Jhira Sahib (temple_id=116)
    (322,116,'Bidar Sacred Site Maintenance Group','29AABCB0017A1Z4','OTHER',
     'WO/KA-BDR-0001/2025/01','2025-03-01','2025-04-01','2026-03-31',
     380000.00,'PENDING',NULL,0,0,0,116),

    -- T18: Sri Manavi Veerbhadreshwara Temple (temple_id=117) · FROZEN; old contract
    (323,117,'Raichur Temple Maintenance Services','29AAACR0018A1Z2','OTHER',
     'WO/KA-RCR-0001/2024/01','2024-06-01','2024-07-01','2024-12-31',
     210000.00,'COMPLETED',NULL,0,0,0,117),

    -- T19: Sri Balagangadharanatha Temple (temple_id=118)
    (324,118,'Nelamangala Construction Services','29AABCN0019A1Z9','CIVIL_WORKS',
     'WO/KA-BLR-0004/2025/01','2025-05-01','2025-06-01','2026-05-31',
     580000.00,'PENDING',NULL,0,0,0,118);
    -- T20 ARCHIVED: no active contractors


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 6 — TEMPLE PROFILE STAGING (ids 114-121)
-- New staging rows for 8 temples missing profiles.
-- T04 → APPROVED (active temple with overdue decl, profile approved)
-- T08 → v2 SUBMITTED (resubmission after rejection)
-- T09 → APPROVED (active temple, profile approved)
-- T14 → SUBMITTED (active temple, profile awaiting DC)
-- T16 → APPROVED (active temple with withdrawn decl, profile approved)
-- T18 → SUBMITTED (frozen temple, profile in review)
-- T19 → SUBMITTED (active temple, profile in review)
-- T20 → DRAFT (archived temple, TA started profile but not submitted)
-- UNIQUE KEY uk_profile_staging_temple_version (temple_id, version):
--   T08 existing v1 REJECTED (id=106); new v2 here has version=2.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO temple_profile_staging
    (id, temple_id, status, version, version_number, lock_version,
     phone, email, website, contact_person_name, contact_person_designation,
     bank_name, bank_account_number_encrypted, bank_ifsc,
     languages_of_worship, description,
     reviewed_at, reviewed_by,
     is_deleted, created_by)
VALUES
    -- T04: Sri Mahadeshwara Temple — APPROVED profile
    (114,103,'APPROVED',1,1,0,
     '08224-242424','mahadeshwara@templeregistry.dev','https://srimahadeshwara.kshetra.in',
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada,Telugu',
     'Sri Mahadeshwara Temple at MM Hills — major pilgrimage site in Chamarajanagar. Famous for Jatras and annual festivals attracting lakhs of devotees.',
     '2025-09-15',121,0,103),

    -- T08: Sri Kadu Malleshwara — v2 SUBMITTED (after rejection, new attempt)
    (115,107,'SUBMITTED',2,2,0,
     '080-23569999','kadu.malleshwara@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     'Canara Bank',NULL,'CNRB0001234',
     'Kannada',
     'Sri Kadu Malleshwara Temple, Malleshwaram — 6th century Shiva temple in the heart of Bengaluru. One of the oldest temples in the city.',
     NULL,NULL,0,107),

    -- T09: Sri Gangadhareshwara Cave Temple — APPROVED profile
    (116,108,'APPROVED',1,1,0,
     '080-28460789','gangadhareshwara@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada,Sanskrit',
     'Sri Gangadhareshwara Cave Temple, Gavipuram — ancient rock-cut cave temple dedicated to Lord Shiva. Famous for the unique solar alignment on Makara Sankranti.',
     '2025-11-20',7,0,108),

    -- T14: Sri Tarakeshwara Temple — SUBMITTED profile
    (117,113,'SUBMITTED',1,1,0,
     '08192-226655','tarakeshwara@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada',
     'Sri Tarakeshwara Temple, Davanagere — historic Shiva temple in central Karnataka.',
     NULL,NULL,0,113),

    -- T16: Sri Ucchangi Bhairaveshwara Temple — APPROVED profile
    (118,115,'APPROVED',1,1,0,
     '08193-278899','ucchangi@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada',
     'Sri Ucchangi Bhairaveshwara Temple, Chitradurga — hilltop temple dedicated to Bhairaveshwara (a form of Shiva). Offers panoramic views of the Chitradurga fort.',
     '2025-12-10',137,0,115),

    -- T18: Sri Manavi Veerbhadreshwara Temple — SUBMITTED profile
    (119,117,'SUBMITTED',1,1,0,
     '08532-268800','manavi.veerbhadra@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada','Sri Manavi Veerbhadreshwara Temple — ancient temple in Raichur district dedicated to Veerbhadra, an avatar of Shiva.',
     NULL,NULL,0,117),

    -- T19: Sri Balagangadharanatha Temple — SUBMITTED profile
    (120,118,'SUBMITTED',1,1,0,
     '080-27726272','balagangadharanatha@templeregistry.dev','https://bgsnagara.org',
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada',
     'Sri Balagangadharanatha Swamiji Temple, Nelamangala — vibrant temple-math complex with extensive social welfare activities.',
     NULL,NULL,0,118),

    -- T20: Sri Kambadahalli Bahubali Temple — DRAFT profile (archived)
    (121,119,'DRAFT',1,1,0,
     '08232-265151','kambadahalli@templeregistry.dev',NULL,
     'Site Caretaker','Temple Authority',
     NULL,NULL,NULL,
     'Kannada,Prakrit',
     'Sri Kambadahalli Bahubali Temple, Mandya — 9th century Jain monument complex with four Tirthankaras.',
     NULL,NULL,0,119);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 7 — TEMPLE PROFILE CURRENT (ids 110-112)
-- Approved staging rows T04/T09/T16 → publish to temple_profile_current.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO temple_profile_current
    (id, temple_id, phone, email, website,
     contact_person_name, contact_person_designation,
     bank_name, bank_account_number_encrypted, bank_ifsc,
     languages_of_worship, description,
     published_at, published_by)
VALUES
    -- T04: Sri Mahadeshwara Temple
    (110,103,'08224-242424','mahadeshwara@templeregistry.dev','https://srimahadeshwara.kshetra.in',
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada,Telugu',
     'Sri Mahadeshwara Temple at MM Hills — major pilgrimage site in Chamarajanagar. Famous for Jatras and annual festivals attracting lakhs of devotees.',
     '2025-09-15',121),
    -- T09: Sri Gangadhareshwara Cave Temple
    (111,108,'080-28460789','gangadhareshwara@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada,Sanskrit',
     'Sri Gangadhareshwara Cave Temple, Gavipuram — ancient rock-cut cave temple dedicated to Lord Shiva. Famous for the unique solar alignment on Makara Sankranti.',
     '2025-11-20',7),
    -- T16: Sri Ucchangi Bhairaveshwara Temple
    (112,115,'08193-278899','ucchangi@templeregistry.dev',NULL,
     'Executive Officer','Temple Authority',
     NULL,NULL,NULL,
     'Kannada',
     'Sri Ucchangi Bhairaveshwara Temple, Chitradurga — hilltop temple dedicated to Bhairaveshwara (a form of Shiva). Offers panoramic views of the Chitradurga fort.',
     '2025-12-10',137);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 8 — ASSET DECLARATIONS for T17 and T18 (ids 119-120)
-- Both temples had no declaration at all in V100.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO asset_declarations
    (id, lock_version, temple_id, district_id, financial_year, version_number,
     status, annual_income, annual_expenditure, gold_grams, buildings_sqft,
     due_date, submitted_at, submitted_by, reviewed_at, reviewed_by,
     acknowledgement_number, acknowledged_at,
     clarification_round, is_overdue, overdue_flagged_at,
     physical_verification_status,
     is_deleted, created_by)
VALUES
    -- T17: Gurudwara Nanak Jhira Sahib · Bidar · 2025-26 DRAFT
    (119,0,116,12,'2025-26',1,'DRAFT',
     NULL,NULL,NULL,NULL,
     '2026-03-31',NULL,NULL,NULL,NULL,
     NULL,NULL,
     0,0,NULL,NULL,0,116),
    -- T18: Sri Manavi Veerbhadreshwara Temple · Raichur · 2025-26 DRAFT (temple FROZEN)
    (120,0,117,13,'2025-26',1,'DRAFT',
     NULL,NULL,NULL,NULL,
     '2026-03-31',NULL,NULL,NULL,NULL,
     NULL,NULL,
     0,0,NULL,NULL,0,117);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 9 — WORKFLOW INSTANCES (ids 254-265)
-- New instances for: 8 profile staging rows + 2 new declarations
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO workflow_instances
    (id, entity_type, entity_id, status, sub_status, lock_version, version_number,
     current_actor_role, created_by_user_id, temple_id, district_id,
     submitted_at, status_updated_at, is_deleted, created_by)
VALUES
    -- Profile WF for new staging rows
    (254,'TEMPLE_PROFILE',114,'APPROVED',  NULL,0,2,NULL,103,103,3,'2025-08-01','2025-09-15',0,103),
    (255,'TEMPLE_PROFILE',115,'SUBMITTED', NULL,0,1,'DC',107,107,6,'2026-05-01','2026-05-01',0,107),
    (256,'TEMPLE_PROFILE',116,'APPROVED',  NULL,0,2,NULL,108,108,6,'2025-10-01','2025-11-20',0,108),
    (257,'TEMPLE_PROFILE',117,'SUBMITTED', NULL,0,1,'DC',113,113,19,'2026-04-15','2026-04-15',0,113),
    (258,'TEMPLE_PROFILE',118,'APPROVED',  NULL,0,2,NULL,115,115,20,'2025-11-01','2025-12-10',0,115),
    (259,'TEMPLE_PROFILE',119,'SUBMITTED', NULL,0,1,'DC',117,117,13,'2026-05-05','2026-05-05',0,117),
    (260,'TEMPLE_PROFILE',120,'SUBMITTED', NULL,0,1,'DC',118,118,7,'2026-05-10','2026-05-10',0,118),
    (261,'TEMPLE_PROFILE',121,'DRAFT',     NULL,0,1,NULL,119,119,2,NULL,NULL,0,119),
    -- Declaration WF for T17, T18
    (262,'DECLARATION',119,'DRAFT',NULL,0,1,NULL,116,116,12,NULL,NULL,0,116),
    (263,'DECLARATION',120,'DRAFT',NULL,0,1,NULL,117,117,13,NULL,NULL,0,117);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 10 — WORKFLOW TRANSITIONS (ids 397-413)
-- APPROVED profile instances get SUBMIT + APPROVE transitions.
-- SUBMITTED profile instances get SUBMIT only.
-- DRAFT instances get no transitions.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

INSERT IGNORE INTO workflow_transitions
    (id, workflow_instance_id, from_status, to_status, action,
     actor_id, actor_role, comment, instance_version_at_transition, performed_at,
     is_deleted, created_by)
VALUES
    -- WF 254: T04 profile APPROVED
    (397,254,NULL,'SUBMITTED','SUBMIT',103,'TEMPLE_AUTHORITY',NULL,1,'2025-08-01',0,1),
    (398,254,'SUBMITTED','APPROVED','APPROVE',121,'DISTRICT_COLLECTOR',NULL,2,'2025-09-15',0,1),
    -- WF 255: T08 profile v2 SUBMITTED (after rejection)
    (399,255,NULL,'SUBMITTED','SUBMIT',107,'TEMPLE_AUTHORITY','Resubmitting with correct contact details.',1,'2026-05-01',0,1),
    -- WF 256: T09 profile APPROVED
    (400,256,NULL,'SUBMITTED','SUBMIT',108,'TEMPLE_AUTHORITY',NULL,1,'2025-10-01',0,1),
    (401,256,'SUBMITTED','APPROVED','APPROVE',7,'DISTRICT_COLLECTOR',NULL,2,'2025-11-20',0,1),
    -- WF 257: T14 profile SUBMITTED
    (402,257,NULL,'SUBMITTED','SUBMIT',113,'TEMPLE_AUTHORITY',NULL,1,'2026-04-15',0,1),
    -- WF 258: T16 profile APPROVED
    (403,258,NULL,'SUBMITTED','SUBMIT',115,'TEMPLE_AUTHORITY',NULL,1,'2025-11-01',0,1),
    (404,258,'SUBMITTED','APPROVED','APPROVE',137,'DISTRICT_COLLECTOR',NULL,2,'2025-12-10',0,1),
    -- WF 259: T18 profile SUBMITTED
    (405,259,NULL,'SUBMITTED','SUBMIT',117,'TEMPLE_AUTHORITY',NULL,1,'2026-05-05',0,1),
    -- WF 260: T19 profile SUBMITTED
    (406,260,NULL,'SUBMITTED','SUBMIT',118,'TEMPLE_AUTHORITY',NULL,1,'2026-05-10',0,1);


-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- SECTION 11 — UPDATE TEMPLE SEARCH SUMMARY
-- Refresh the materialised view rows for temples with new data.
-- Changes: pending_profile_review, has_active_trust, last_profile_update_at.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

-- T04: profile now APPROVED → pending_profile_review=0
UPDATE temple_search_summary
SET pending_profile_review=0, last_profile_update_at='2025-09-15'
WHERE temple_id=103;

-- T08: profile v2 SUBMITTED → pending_profile_review=1
UPDATE temple_search_summary
SET pending_profile_review=1, last_profile_update_at='2026-05-01'
WHERE temple_id=107;

-- T09: profile now APPROVED → pending_profile_review=0
UPDATE temple_search_summary
SET pending_profile_review=0, last_profile_update_at='2025-11-20'
WHERE temple_id=108;

-- T14: trust DRAFT (has_active_trust=0 — not yet approved), profile SUBMITTED
UPDATE temple_search_summary
SET pending_profile_review=1, has_active_trust=0, last_profile_update_at='2026-04-15'
WHERE temple_id=113;

-- T16: profile now APPROVED → pending_profile_review=0
UPDATE temple_search_summary
SET pending_profile_review=0, last_profile_update_at='2025-12-10'
WHERE temple_id=115;

-- T17: declaration DRAFT (has_active_trust=0 — trust DRAFT)
UPDATE temple_search_summary
SET has_active_trust=0, last_declaration_at=NULL
WHERE temple_id=116;

-- T18: profile SUBMITTED → pending_profile_review=1, trust DRAFT
UPDATE temple_search_summary
SET pending_profile_review=1, has_active_trust=0, last_profile_update_at='2026-05-05'
WHERE temple_id=117;

-- T19: profile SUBMITTED → pending_profile_review=1
UPDATE temple_search_summary
SET pending_profile_review=1, last_profile_update_at='2026-05-10'
WHERE temple_id=118;

-- T20: profile DRAFT → no pending review (DRAFT doesn't trigger pending_profile_review)
UPDATE temple_search_summary
SET pending_profile_review=0, last_profile_update_at=NULL
WHERE temple_id=119;

SET FOREIGN_KEY_CHECKS = 1;

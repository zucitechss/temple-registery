-- ============================================================================
-- V115: Complete the Karnataka taluk/hobli hierarchy
--
-- WHY THIS FILE EXISTS
--   V110 (and its dev/test twin, V2__master_seed_data.sql) only ever seeded a
--   "representative set" of taluks/hoblis — 3 per district for a handful of
--   key districts, leaving 11 of Karnataka's 20 districts with zero taluks at
--   all. A separate, much more complete hierarchy (66 taluks, 132 hoblis)
--   existed only in classpath:db/seeds/geo_seed.sql (note the plural
--   "seeds" folder) — a path Flyway's configured locations
--   (classpath:db/migration, classpath:db/seed, both singular) never load, so
--   that file was dead weight that ran nowhere.
--
--   This migration folds that complete data set in as genuine, permanent
--   production reference data (this repo's stated policy: geo hierarchy is
--   "required in every environment", never dev-only fixture data — see
--   V110's own header) instead of leaving it as unused/orphaned seed SQL.
--
-- WHY A NEW MIGRATION INSTEAD OF EDITING V110/V2 IN PLACE
--   V110 is a versioned migration that has already been applied to real
--   databases (including production). Editing an already-applied migration's
--   content changes its checksum and is a Flyway anti-pattern — already-run
--   migrations must only be added to, never rewritten. V110's existing 20
--   taluks/14 hoblis are left completely untouched here; this migration only
--   INSERTs what was missing.
--
-- ID SCHEME
--   V110 already owns taluk ids 1-20 and hobli ids 1-14. New taluks continue
--   from 21 (46 rows, ids 21-66); new hoblis continue from 15 (108 rows, ids
--   15-122). Where geo_seed.sql's own (district, name) pair already matches
--   an existing V110 taluk, that existing id is reused (e.g. Hubli stays
--   taluk id 19, nested under district_id 17 = Dharwad, exactly as it already
--   was in V110 — this migration does not change that nesting, it only adds
--   the two Hubli hoblis V110 never had).
--
-- IDEMPOTENCY
--   states/cities/districts already fully covered by V110/V2 — untouched.
--   taluks/hoblis insert explicit PKs -> INSERT IGNORE collides on PRIMARY
--   KEY and is a no-op on repeat runs, exactly like V110.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;
SET @ts  = NOW();
SET @sys = 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- TALUKS — the 46 missing from V110, giving every one of the 20 districts
-- its full, real set of taluks.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT IGNORE INTO taluks (id, district_id, name, is_deleted, created_at, updated_at, created_by, updated_by) VALUES
-- Mysuru district (1)
(21, 1,  'Periyapatna',       0, @ts, @ts, @sys, @sys),
-- Mandya district (2)
(22, 2,  'Maddur',            0, @ts, @ts, @sys, @sys),
-- Chamarajanagar district (3)
(23, 3,  'Yelandur',          0, @ts, @ts, @sys, @sys),
-- Kodagu district (4)
(24, 4,  'Somwarpet',         0, @ts, @ts, @sys, @sys),
-- Hassan district (5)
(25, 5,  'Sakleshpur',        0, @ts, @ts, @sys, @sys),
(26, 5,  'Belur',             0, @ts, @ts, @sys, @sys),
-- Bengaluru Urban (6)
(27, 6,  'Yelahanka',         0, @ts, @ts, @sys, @sys),
-- Bengaluru Rural (7) — previously had no taluks at all
(28, 7,  'Devanahalli',       0, @ts, @ts, @sys, @sys),
(29, 7,  'Doddaballapur',     0, @ts, @ts, @sys, @sys),
(30, 7,  'Nelamangala',       0, @ts, @ts, @sys, @sys),
-- Ramanagara (8) — previously had no taluks at all
(31, 8,  'Ramanagara',        0, @ts, @ts, @sys, @sys),
(32, 8,  'Channapatna',       0, @ts, @ts, @sys, @sys),
(33, 8,  'Kanakapura',        0, @ts, @ts, @sys, @sys),
-- Tumkuru (9)
(34, 9,  'Sira',              0, @ts, @ts, @sys, @sys),
(35, 9,  'Madhugiri',         0, @ts, @ts, @sys, @sys),
-- Kolar (10) — previously had no taluks at all
(36, 10, 'Kolar',             0, @ts, @ts, @sys, @sys),
(37, 10, 'Mulbagal',          0, @ts, @ts, @sys, @sys),
(38, 10, 'Srinivasapur',      0, @ts, @ts, @sys, @sys),
-- Kalaburagi (11) — previously had no taluks at all
(39, 11, 'Kalaburagi',        0, @ts, @ts, @sys, @sys),
(40, 11, 'Yadgir',            0, @ts, @ts, @sys, @sys),
(41, 11, 'Sedam',             0, @ts, @ts, @sys, @sys),
-- Bidar (12) — previously had no taluks at all
(42, 12, 'Bidar',             0, @ts, @ts, @sys, @sys),
(43, 12, 'Bhalki',            0, @ts, @ts, @sys, @sys),
(44, 12, 'Basavakalyan',      0, @ts, @ts, @sys, @sys),
-- Raichur (13) — previously had no taluks at all
(45, 13, 'Raichur',           0, @ts, @ts, @sys, @sys),
(46, 13, 'Sindhanur',         0, @ts, @ts, @sys, @sys),
(47, 13, 'Lingasugur',        0, @ts, @ts, @sys, @sys),
-- Belagavi (14) — previously had no taluks at all
(48, 14, 'Belagavi',          0, @ts, @ts, @sys, @sys),
(49, 14, 'Gokak',             0, @ts, @ts, @sys, @sys),
(50, 14, 'Bailhongal',        0, @ts, @ts, @sys, @sys),
(51, 14, 'Hukkeri',           0, @ts, @ts, @sys, @sys),
-- Vijayapura (15) — previously had no taluks at all
(52, 15, 'Vijayapura',        0, @ts, @ts, @sys, @sys),
(53, 15, 'Indi',              0, @ts, @ts, @sys, @sys),
(54, 15, 'Sindagi',           0, @ts, @ts, @sys, @sys),
-- Bagalkot (16) — previously had no taluks at all
(55, 16, 'Bagalkot',          0, @ts, @ts, @sys, @sys),
(56, 16, 'Badami',            0, @ts, @ts, @sys, @sys),
(57, 16, 'Jamakhandi',        0, @ts, @ts, @sys, @sys),
-- Dharwad (17)
(58, 17, 'Navalgund',         0, @ts, @ts, @sys, @sys),
-- Shivamogga (18)
(59, 18, 'Bhadravati',        0, @ts, @ts, @sys, @sys),
(60, 18, 'Tirthahalli',       0, @ts, @ts, @sys, @sys),
-- Davanagere (19) — previously had no taluks at all
(61, 19, 'Davanagere',        0, @ts, @ts, @sys, @sys),
(62, 19, 'Harihar',           0, @ts, @ts, @sys, @sys),
(63, 19, 'Jagalur',           0, @ts, @ts, @sys, @sys),
-- Chitradurga (20) — previously had no taluks at all
(64, 20, 'Chitradurga',       0, @ts, @ts, @sys, @sys),
(65, 20, 'Hiriyur',           0, @ts, @ts, @sys, @sys),
(66, 20, 'Holalkere',         0, @ts, @ts, @sys, @sys);

-- ─────────────────────────────────────────────────────────────────────────────
-- HOBLIS — 2 per newly-added taluk above, plus the missing pair for each of
-- the 8 taluks V110 already had but never gave any hoblis to (Nagamangala,
-- Malavalli, Gundlupet, Virajpet, Arsikere, Bengaluru East, Tiptur, and
-- Hubli itself — the example this migration was written to close out).
-- ─────────────────────────────────────────────────────────────────────────────

INSERT IGNORE INTO hoblis (id, taluk_id, name, is_deleted, created_at, updated_at, created_by, updated_by) VALUES
(15,  21, 'Periyapatna North Hobli',    0, @ts, @ts, @sys, @sys),
(16,  21, 'Periyapatna South Hobli',    0, @ts, @ts, @sys, @sys),
(17,  5,  'Nagamangala Hobli',          0, @ts, @ts, @sys, @sys),
(18,  5,  'Doddagaddavalli Hobli',      0, @ts, @ts, @sys, @sys),
(19,  22, 'Maddur Hobli',               0, @ts, @ts, @sys, @sys),
(20,  22, 'Kokkare Bellur Hobli',       0, @ts, @ts, @sys, @sys),
(21,  6,  'Malavalli Hobli',            0, @ts, @ts, @sys, @sys),
(22,  6,  'Kere Hobli',                 0, @ts, @ts, @sys, @sys),
(23,  8,  'Gundlupet Hobli',            0, @ts, @ts, @sys, @sys),
(24,  8,  'Hangala Hobli',              0, @ts, @ts, @sys, @sys),
(25,  23, 'Yelandur Hobli',             0, @ts, @ts, @sys, @sys),
(26,  23, 'Sathegala Hobli',            0, @ts, @ts, @sys, @sys),
(27,  10, 'Virajpet Hobli',             0, @ts, @ts, @sys, @sys),
(28,  10, 'Ponnampet Hobli',            0, @ts, @ts, @sys, @sys),
(29,  24, 'Somwarpet Hobli',            0, @ts, @ts, @sys, @sys),
(30,  24, 'Shanthalli Hobli',           0, @ts, @ts, @sys, @sys),
(31,  12, 'Arsikere Hobli',             0, @ts, @ts, @sys, @sys),
(32,  12, 'Bukkapatna Hobli',           0, @ts, @ts, @sys, @sys),
(33,  25, 'Sakleshpur Hobli',           0, @ts, @ts, @sys, @sys),
(34,  25, 'Donigal Hobli',              0, @ts, @ts, @sys, @sys),
(35,  26, 'Belur Hobli',                0, @ts, @ts, @sys, @sys),
(36,  26, 'Halebidu Hobli',             0, @ts, @ts, @sys, @sys),
(37,  15, 'Hoodi Hobli',                0, @ts, @ts, @sys, @sys),
(38,  15, 'Varthur Hobli',              0, @ts, @ts, @sys, @sys),
(39,  27, 'Yelahanka South Hobli',      0, @ts, @ts, @sys, @sys),
(40,  27, 'Dasarahalli Hobli',          0, @ts, @ts, @sys, @sys),
(41,  28, 'Devanahalli Hobli',          0, @ts, @ts, @sys, @sys),
(42,  28, 'Vijayapura Hobli',           0, @ts, @ts, @sys, @sys),
(43,  29, 'Doddaballapur Hobli',        0, @ts, @ts, @sys, @sys),
(44,  29, 'Rajanukunte Hobli',          0, @ts, @ts, @sys, @sys),
(45,  30, 'Nelamangala Hobli',          0, @ts, @ts, @sys, @sys),
(46,  30, 'Hesaraghatta Hobli',         0, @ts, @ts, @sys, @sys),
(47,  31, 'Ramanagara Hobli',           0, @ts, @ts, @sys, @sys),
(48,  31, 'Bidadi Hobli',               0, @ts, @ts, @sys, @sys),
(49,  32, 'Channapatna Hobli',          0, @ts, @ts, @sys, @sys),
(50,  32, 'Maddur South Hobli',         0, @ts, @ts, @sys, @sys),
(51,  33, 'Kanakapura Hobli',           0, @ts, @ts, @sys, @sys),
(52,  33, 'Sathanur Hobli',             0, @ts, @ts, @sys, @sys),
(53,  17, 'Tiptur Hobli',               0, @ts, @ts, @sys, @sys),
(54,  17, 'Chikkanayakanahalli Hobli', 0, @ts, @ts, @sys, @sys),
(55,  34, 'Sira Hobli',                 0, @ts, @ts, @sys, @sys),
(56,  34, 'Bukkapatna South Hobli',     0, @ts, @ts, @sys, @sys),
(57,  35, 'Madhugiri Hobli',            0, @ts, @ts, @sys, @sys),
(58,  35, 'Koratagere Hobli',           0, @ts, @ts, @sys, @sys),
(59,  36, 'Kolar Hobli',                0, @ts, @ts, @sys, @sys),
(60,  36, 'Oorgaum Hobli',              0, @ts, @ts, @sys, @sys),
(61,  37, 'Mulbagal Hobli',             0, @ts, @ts, @sys, @sys),
(62,  37, 'Gudibande Hobli',            0, @ts, @ts, @sys, @sys),
(63,  38, 'Srinivasapur Hobli',         0, @ts, @ts, @sys, @sys),
(64,  38, 'Bangarapet Hobli',           0, @ts, @ts, @sys, @sys),
(65,  39, 'Kalaburagi Hobli',           0, @ts, @ts, @sys, @sys),
(66,  39, 'Afzalpur Hobli',             0, @ts, @ts, @sys, @sys),
(67,  40, 'Yadgir Hobli',               0, @ts, @ts, @sys, @sys),
(68,  40, 'Shorapur Hobli',             0, @ts, @ts, @sys, @sys),
(69,  41, 'Sedam Hobli',                0, @ts, @ts, @sys, @sys),
(70,  41, 'Chittapur Hobli',            0, @ts, @ts, @sys, @sys),
(71,  42, 'Bidar Hobli',                0, @ts, @ts, @sys, @sys),
(72,  42, 'Humnabad Hobli',             0, @ts, @ts, @sys, @sys),
(73,  43, 'Bhalki Hobli',               0, @ts, @ts, @sys, @sys),
(74,  43, 'Udgir Hobli',                0, @ts, @ts, @sys, @sys),
(75,  44, 'Basavakalyan Hobli',         0, @ts, @ts, @sys, @sys),
(76,  44, 'Hulsoor Hobli',              0, @ts, @ts, @sys, @sys),
(77,  45, 'Raichur Hobli',              0, @ts, @ts, @sys, @sys),
(78,  45, 'Devadurga Hobli',            0, @ts, @ts, @sys, @sys),
(79,  46, 'Sindhanur Hobli',            0, @ts, @ts, @sys, @sys),
(80,  46, 'Mudgal Hobli',               0, @ts, @ts, @sys, @sys),
(81,  47, 'Lingasugur Hobli',           0, @ts, @ts, @sys, @sys),
(82,  47, 'Maski Hobli',                0, @ts, @ts, @sys, @sys),
(83,  48, 'Belagavi Hobli',             0, @ts, @ts, @sys, @sys),
(84,  48, 'Khanapura Hobli',            0, @ts, @ts, @sys, @sys),
(85,  49, 'Gokak Hobli',                0, @ts, @ts, @sys, @sys),
(86,  49, 'Mudalagi Hobli',             0, @ts, @ts, @sys, @sys),
(87,  50, 'Bailhongal Hobli',           0, @ts, @ts, @sys, @sys),
(88,  50, 'Saundatti Hobli',            0, @ts, @ts, @sys, @sys),
(89,  51, 'Hukkeri Hobli',              0, @ts, @ts, @sys, @sys),
(90,  51, 'Nippanal Hobli',             0, @ts, @ts, @sys, @sys),
(91,  52, 'Vijayapura Hobli',           0, @ts, @ts, @sys, @sys),
(92,  52, 'Tikota Hobli',               0, @ts, @ts, @sys, @sys),
(93,  53, 'Indi Hobli',                 0, @ts, @ts, @sys, @sys),
(94,  53, 'Talikoti Hobli',             0, @ts, @ts, @sys, @sys),
(95,  54, 'Sindagi Hobli',              0, @ts, @ts, @sys, @sys),
(96,  54, 'Muddebihal Hobli',           0, @ts, @ts, @sys, @sys),
(97,  55, 'Bagalkot Hobli',             0, @ts, @ts, @sys, @sys),
(98,  55, 'Kaladgi Hobli',              0, @ts, @ts, @sys, @sys),
(99,  56, 'Badami Hobli',               0, @ts, @ts, @sys, @sys),
(100, 56, 'Guledgudda Hobli',           0, @ts, @ts, @sys, @sys),
(101, 57, 'Jamakhandi Hobli',           0, @ts, @ts, @sys, @sys),
(102, 57, 'Bilgi Hobli',                0, @ts, @ts, @sys, @sys),
(103, 19, 'Dharwad South Hobli',        0, @ts, @ts, @sys, @sys),
(104, 19, 'Hubli Hobli',                0, @ts, @ts, @sys, @sys),
(105, 58, 'Navalgund Hobli',            0, @ts, @ts, @sys, @sys),
(106, 58, 'Kundagol Hobli',             0, @ts, @ts, @sys, @sys),
(107, 59, 'Bhadravati Hobli',           0, @ts, @ts, @sys, @sys),
(108, 59, 'Honnali Hobli',              0, @ts, @ts, @sys, @sys),
(109, 60, 'Tirthahalli Hobli',          0, @ts, @ts, @sys, @sys),
(110, 60, 'Hosanagara Hobli',           0, @ts, @ts, @sys, @sys),
(111, 61, 'Davanagere Hobli',           0, @ts, @ts, @sys, @sys),
(112, 61, 'Channagiri Hobli',           0, @ts, @ts, @sys, @sys),
(113, 62, 'Harihar Hobli',              0, @ts, @ts, @sys, @sys),
(114, 62, 'Ranebennur Hobli',           0, @ts, @ts, @sys, @sys),
(115, 63, 'Jagalur Hobli',              0, @ts, @ts, @sys, @sys),
(116, 63, 'Harapanahalli Hobli',        0, @ts, @ts, @sys, @sys),
(117, 64, 'Chitradurga Hobli',          0, @ts, @ts, @sys, @sys),
(118, 64, 'Molakalmuru Hobli',          0, @ts, @ts, @sys, @sys),
(119, 65, 'Hiriyur Hobli',              0, @ts, @ts, @sys, @sys),
(120, 65, 'Challakere Hobli',           0, @ts, @ts, @sys, @sys),
(121, 66, 'Holalkere Hobli',            0, @ts, @ts, @sys, @sys),
(122, 66, 'Hosadurga Hobli',            0, @ts, @ts, @sys, @sys);

SET FOREIGN_KEY_CHECKS = 1;

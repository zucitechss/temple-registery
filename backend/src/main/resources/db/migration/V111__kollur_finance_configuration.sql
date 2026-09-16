-- ============================================================================
-- V111: Kollur finance configuration (FIN-021 .. FIN-024)
--
-- CONFIGURATION DATA ONLY. This migration creates no table, alters no schema,
-- and enables no synchronization. It records what is known about one temple's
-- source system so that a future connector can consume it without the canonical
-- model, the APIs or the dashboard changing.
--
-- Contains NO credential, NO host, NO port, NO URL and NO connection string.
-- fin_source_system.credential_ref is an ALIAS resolved by the sync worker from
-- its own environment (FIN-D-002, FIN-D-009).
--
-- sync_enabled = 0. Registering a source system must never, by itself, cause
-- the platform to contact a government temple's production database. Enabling
-- it is a separate, deliberate act that comes after: a connector exists (FIN-040),
-- the network path is decided (Q4), the credential store is decided (Q5), a dry
-- run has passed, and reconciliation has passed.
--
-- ---------------------------------------------------------------------------
-- CONDITIONAL BY DESIGN
--
-- Every statement below is guarded by the existence of temple 300001. No
-- migration in this repository creates that temple -- V100 seeds temples with
-- ids 100..., and 300001 exists only in environments where it was created
-- through the application. Seeding finance configuration for a temple that is
-- not present would leave orphan rows in every fresh developer and CI database.
--
-- Consequence, recorded in HANDOFF.md: in a database where temple 300001 is
-- created AFTER this migration runs, the seed is a no-op and Kollur
-- configuration must be applied through the onboarding path (FIN-140) instead.
-- ---------------------------------------------------------------------------
--
-- Evidence: docs/finance/KOLLUR_FINANCE_DATA_ANALYSIS.md
-- Model:    docs/finance/FINANCE_DATA_MODEL.md
-- ADRs:     ADR-007 (availability is data), ADR-008 (source of truth),
--           ADR-009 (Nirantara lifecycle separation)
-- ============================================================================


-- ----------------------------------------------------------------------------
-- FIN-021 — Source system
--
-- connector_type is PROVISIONAL. Q4 (the network path to Kollur) is unresolved,
-- and the column is NOT NULL, so a value must be chosen now. PULL_JDBC reflects
-- how the source was analysed, not a decision that it is reachable. If the
-- network decision goes to PUSH_AGENT this row is corrected with one UPDATE and
-- nothing else changes -- which is the point of the connector contract. Nothing
-- depends on the value while sync_enabled = 0.
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO fin_source_system
    (temple_id, system_code, system_name,
     source_technology, connector_type, connector_bean,
     source_temple_code, source_database_name, credential_ref,
     sync_schedule_cron, sync_enabled, staleness_threshold_hours,
     schema_fingerprint, source_timezone, notes,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT
    300001,
    'KOLSOHAM',
    'Kollur Sri Mookambika Devi Temple operational system',
    'SQL_SERVER',
    'PULL_JDBC',
    'kollurFinanceConnector',
    '43',
    'KOLSOHAM_LOCAL',
    'kollur-readonly',
    NULL,
    0,
    48,
    NULL,
    'Asia/Kolkata',
    CONCAT(
      'Registered from the FIN-021 analysis. connector_type is PROVISIONAL pending Q4 ',
      '(network path); connector_bean names a connector that does not exist yet (FIN-040). ',
      'credential_ref is an alias only -- no credential is stored here or anywhere in the ',
      'registry database. Source data was observed to end 2026-07-26, several weeks behind ',
      'real time, so reported freshness must distinguish sourceDataThrough from lastSyncedAt.'),
    0, NOW(6), NOW(6), 0, 0
FROM DUAL
WHERE EXISTS (SELECT 1 FROM temples t WHERE t.id = 300001 AND t.is_deleted = 0);


-- ----------------------------------------------------------------------------
-- FIN-022 — Capabilities (19 rows, one per canonical capability)
--
-- Every value of the canonical FinanceCapability vocabulary is declared, so that
-- "not declared" never has to be guessed at. availability_reason is USER-FACING
-- text rendered verbatim where a figure would otherwise appear; it states what
-- was measured, not that data is merely "unavailable".
--
-- A NOT_AVAILABLE capability is never rendered as zero (ADR-007).
-- ----------------------------------------------------------------------------

-- --- AVAILABLE (10) ---------------------------------------------------------

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'REVENUE', 'AVAILABLE',
       'Receipt-level income is recorded with a transaction date, an amount and a cancellation flag. Figures are receipt counts and amounts, not numbers of devotees.',
       '2019-04-01', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'SEVA', 'AVAILABLE',
       'Each receipt identifies the service purchased. Ritual sevas are distinguished from prasadam sales and donations by the income bucket the service belongs to.',
       '2019-04-01', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'DONATION', 'AVAILABLE',
       'Donations, including donation-box (hundi) collections, are recorded as receipts under the donation income bucket. The dedicated donation and hundi-collection tables in the source are empty and are not used.',
       '2019-04-01', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'PRASADAM_SALE', 'AVAILABLE',
       'Prasadam items are sold through the same receipt system as sevas but belong to a separate income bucket, so retail sales are reported separately from rituals rather than counted as sevas.',
       '2019-04-01', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'CANCELLATION', 'AVAILABLE',
       'Cancelled receipts are flagged rather than deleted, so cancelled amounts can be reported separately and deducted from gross income.',
       '2019-04-01', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'PRECIOUS_METAL_COUNT', 'AVAILABLE',
       'Donated gold and silver articles are recorded individually and can be counted per year, separated by metal. Two financial years hold no records at all.',
       '2015-04-01', '2026-07-25',
       '{"missingFinancialYears":["2021-22","2022-23"],"note":"No gold or silver records exist for these years. This is an absence of records, not an absence of donations."}',
       NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'PRECIOUS_METAL_WEIGHT', 'AVAILABLE',
       'Actual weight is recorded for each donated article in grams, and can be reported per year split by gold and silver. Weight is measured, not estimated from item counts.',
       '2015-04-01', '2026-07-25',
       '{"missingFinancialYears":["2021-22","2022-23"],"note":"No gold or silver records exist for these years."}',
       NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'IN_KIND_DONATION', 'AVAILABLE',
       'Donated sarees are recorded with a value stated by the donor. This is a declared value, not an appraisal, and must not be added to auction proceeds for the same sarees -- the auction realises the value of the identical articles, so summing both counts them twice.',
       '2016-04-14', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'NIRANTARA_SUBSCRIPTION', 'AVAILABLE',
       'Perpetual seva subscriptions are recorded with subscriber details, the sevas subscribed to and the booking date. Subscription status columns hold a single constant value throughout and therefore carry no information about whether a subscription is still active.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'NIRANTARA_SCHEDULE', 'AVAILABLE',
       'Scheduled occurrences of perpetual sevas are generated in advance and extend into 2027. A scheduled occurrence is a plan, not a record that the seva took place.',
       '2019-05-01', '2027-07-26',
       '{"note":"Scheduled dates extend beyond the present into 2027. These rows are generated schedules and must never be reported as sevas performed."}',
       NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

-- --- PARTIALLY_AVAILABLE (2) ------------------------------------------------

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'NIRANTARA_PAYMENT', 'PARTIALLY_AVAILABLE',
       'Payments against perpetual seva subscriptions are recorded only up to financial year 2023-24. No payment has been recorded since, while sevas continue to be scheduled. Later years are not zero -- the payments are simply not recorded in this system.',
       '2017-04-01', '2024-03-31',
       '{"recordedThroughFinancialYear":"2023-24","note":"Financial years 2024-25 onward contain no payment records. Scheduling continues over the same period, so payment coverage does not match booking coverage."}',
       NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'PAYMENT_MODE', 'PARTIALLY_AVAILABLE',
       'The source records no payment-mode field. Card and bank details are present on almost no receipts, so what can be said is that no digital payment was recorded -- not that the payment was made in cash. A receipt where the operator left the card field blank cannot be distinguished from a genuine cash sale.',
       '2019-04-01', '2026-07-26', NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

-- --- NOT_AVAILABLE (7) ------------------------------------------------------

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'PRECIOUS_METAL_VALUE', 'NOT_AVAILABLE',
       'The source records no valuation for donated gold and silver. Rate and amount fields are zero on every record after financial year 2015-16, and no purity or assay information is held. Weight is available and is reported instead; a monetary value cannot be derived from it without an assumed rate, which would be an invention rather than a measurement.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'NIRANTARA_EXECUTION', 'NOT_AVAILABLE',
       'The source records scheduled perpetual sevas but never records that one was performed. The attendance table is empty, the count of sevas issued is zero on every subscription, and the flag that appears to indicate preparation is also set on schedules dated into 2027 -- so it means that a schedule was generated, not that a seva took place. Booking data alone cannot prove execution, and no completion or fulfilment percentage can be reported.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'EXPENSE', 'NOT_AVAILABLE',
       'The source system does not record expenditure. It holds no payroll, vendor, purchase or voucher records. The accounting ledger present in the source covers only saree auction sales and captures no spending at all. Expenditure is unknown for this temple; it is not zero.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'EXPENSE_CATEGORY', 'NOT_AVAILABLE',
       'No expenditure is recorded, so expenditure cannot be broken down by category.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'GRANT', 'NOT_AVAILABLE',
       'The source system records no grants received. The only budget records present are twelve allocation rows from financial year 2017-18, which are a planning artefact rather than actual receipts.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'GRANT_UTILISATION', 'NOT_AVAILABLE',
       'No grants are recorded, so their utilisation cannot be reported. The amount-spent column on the few budget records present is zero on almost all of them.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_temple_capability
    (temple_id, source_system_id, capability, availability, availability_reason,
     coverage_from, coverage_to, known_gaps_json, last_reviewed_at,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT 300001, s.id, 'WORKS', 'NOT_AVAILABLE',
       'The source system records no works or construction projects.',
       NULL, NULL, NULL, NOW(6), 0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';


-- ----------------------------------------------------------------------------
-- FIN-023 — Source of truth for REVENUE_AMOUNT
--
-- Three columns in the source plausibly represent revenue and they disagree
-- materially. The more granular detail table is 41% short of the header total
-- while having exact one-to-one row correspondence with it and no orphans --
-- which means the detail table is internally inconsistent, not that records are
-- missing. To an engineer arriving later without this context, moving revenue
-- to the detail table would look like an improvement.
--
-- Recording the rejected alternatives WITH their measured totals is what gives
-- this declaration force (ADR-008).
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO fin_source_of_truth_decl
    (source_system_id, metric, version, source_object, source_field,
     filter_predicate, rejected_alternatives_json, rationale,
     approved_by, approved_at, effective_from, effective_to,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT
    s.id, 'REVENUE_AMOUNT', 1, 'DailySevaNew', 'Amount',
    'Deleteflag = 0 AND TempleCode = 43 AND ReceiptDate >= ''2015-01-01'' AND BillCancled = 0 for recognised revenue. Extraction retains BillCancled = 1 rows so that cancelled counts and amounts are reported separately; net revenue is gross minus cancelled.',
    '[{"object":"DailySevaNewOld","field":"Amount","measuredRows":16982270,"reason":"Byte-exact duplicate of all six financial-year archive tables combined, with identical per-year counts. Including it alongside the archives doubles every historical year."},{"object":"DailySevaNewDetails","field":"TotalAmount","measuredAmountFy2025_26":537753226,"reason":"41% below the authoritative header total for FY2025-26 (header 906162936). Header and detail have exact 1:1 row correspondence with zero orphans, so the gap is internal inconsistency in the detail table, not missing records."},{"object":"DailySevaNewDetails","field":"Amount * Qty","measuredAmountFy2025_26":563107228,"reason":"Disagrees with both the header total and the detail table own TotalAmount column, so the detail table does not even agree with itself."}]',
    CONCAT(
      'The receipt header amount is the authoritative recognised revenue. Measured for FY2025-26: ',
      'header 906162936. Two further rules follow from the same analysis and bind extraction: ',
      '(1) revenue is the union of the live receipt table and exactly six financial-year archive ',
      'tables, never the Old archive, which duplicates all six; ',
      '(2) the service rate-card amount is a list price and is never revenue -- recognised revenue ',
      'is only what a receipt records as received. ',
      'Receipt counts are receipts, not devotees: one devotee may buy several items on one receipt ',
      'and one receipt may cover a family.'),
    NULL, NULL, '2019-04-01', NULL,
    0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';


-- ----------------------------------------------------------------------------
-- FIN-024 — Mapping rules: source value -> canonical value
--
-- Value translation only. No table name, column name, query or connector logic
-- appears here; which rows and which columns to read is code (ADR-004).
--
-- Source values are namespaced because two different source vocabularies map to
-- the same canonical category type. SANNIDHI: is the coarse four-bucket income
-- classification the source already maintains; SEVA_CODE: overrides it for a
-- specific service; STREAM: names a separate source record stream.
--
-- PRECEDENCE, which the connector must honour: SEVA_CODE beats SANNIDHI.
-- Without the override, donation-box collections would be reported as ordinary
-- donations -- 13 records averaging over a crore each are collection events,
-- not purchased services.
--
-- No PAYMENT_MODE rules are seeded. For this source, payment mode is inferred
-- from the absence of card details rather than read from a field, which is
-- connector logic, not a value mapping.
-- ----------------------------------------------------------------------------

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'SANNIDHI:DS', 'SEVAS', 'SEVA', 1,
       'Ritual services performed for a devotee.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'SANNIDHI:SS', 'SPL SEVAS', 'SPECIAL_SEVA', 1,
       'High-value or occasional rituals, kept distinct from ordinary sevas.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'SANNIDHI:KN', 'KANIKE / DONATION', 'DONATION', 1,
       'Voluntary offerings. Donation-box collections are separated by the SEVA_CODE:430 override.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'SANNIDHI:PS', 'PRASADA', 'PRASADAM_SALE', 1,
       'Retail sale of prasadam. Items such as laddu, cloth bag, panchakajjaya and theertha bottle appear in the service master but are sales, not rituals, and this bucket separates them cleanly.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'SEVA_CODE:430', 'HUNDIALS', 'HUNDI_DONATION', 1,
       'Donation-box counting events booked as receipts. Overrides the donation bucket: 13 records averaging over one crore each are collection events, not purchased services, and would otherwise dominate any ranking of services.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'STREAM:SAREE_DONATION', 'Saree donation', 'IN_KIND_DONATION', 1,
       'Non-cash donation valued by the donor. Must not be summed with the auction proceeds for the same articles.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'REVENUE_CATEGORY', 'STREAM:SAREE_AUCTION', 'Saree auction', 'ASSET_REALISATION', 1,
       'Cash realised by auctioning donated articles. A distinct financial event from the donation of those articles.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'METAL_TYPE', '2', 'Bangara (gold)', 'GOLD', 1,
       'Source stores the label in Kannada script; the transliteration is recorded here to keep the migration free of encoding risk.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

INSERT IGNORE INTO fin_mapping_rule
    (source_system_id, mapping_type, source_value, source_label, canonical_value,
     is_active, notes, is_deleted, created_at, updated_at, created_by, updated_by)
SELECT s.id, 'METAL_TYPE', '1', 'Belli (silver)', 'SILVER', 1,
       'Source stores the label in Kannada script; the transliteration is recorded here to keep the migration free of encoding risk.',
       0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

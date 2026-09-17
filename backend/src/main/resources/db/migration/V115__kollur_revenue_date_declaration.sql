-- ============================================================================
-- V115: Business-date declaration for the first onboarded source (FIN-055)
--
-- Normalization reads a staged payload only where fin_source_of_truth_decl tells
-- it to (ADR-008). FIN-023 declared which field carries the authoritative
-- REVENUE_AMOUNT; nothing declared which field carries the date that revenue
-- belongs to, so the amount could be read and never placed in time.
--
-- This migration is source-specific configuration, not platform behaviour. The
-- generic engine knows only the metric names; that a particular source keeps its
-- business date in a particular column is data, and lives here with the rest of
-- that source's configuration (V111).
--
-- WHY THIS COLUMN. The approved REVENUE_AMOUNT declaration already bounds
-- extraction with ReceiptDate >= '2015-01-01' -- that is, the analysis behind
-- FIN-023 was already treating ReceiptDate as the axis a financial window is cut
-- on. Declaring it here makes that explicit and reviewable rather than implied by
-- a filter predicate.
--
-- WHY IT IS NOT APPROVED. approved_by and approved_at are NULL, exactly as they
-- are for the FIN-023 amount declaration. The evidence above is strong but it is
-- inference from a filter, not a confirmation from anyone who runs the source
-- system; the modification-date column has not been ruled out by inspection
-- because the source has never been reachable (Q4). Conflating a modification
-- date with a business date restates history silently (FIN-D-012), so this is a
-- declaration awaiting sign-off, not a settled fact.
--
-- WHAT source_field MEANS HERE. The key to read from the staged payload -- the
-- same vocabulary a mapping rule's namespace uses (FIN-D-028). For this source
-- the connector is expected to emit each field under its source column name, so
-- the two coincide; a connector that renames fields on the way out would have to
-- declare the names it emits, not the ones it read.
-- ============================================================================

INSERT IGNORE INTO fin_source_of_truth_decl
    (source_system_id, metric, version, source_object, source_field,
     filter_predicate, rejected_alternatives_json, rationale,
     approved_by, approved_at, effective_from, effective_to,
     is_deleted, created_at, updated_at, created_by, updated_by)
SELECT
    s.id, 'REVENUE_TRANSACTION_DATE', 1, 'DailySevaNew', 'ReceiptDate',
    NULL,
    '[{"object":"DailySevaNew","field":"<modification timestamp>","reason":"Not selected, and not yet ruled out by inspection: the source has never been reachable (Q4). A modification date moves an edited two-year-old receipt into the current day, restating history and inflating whichever period the edit happened in."}]',
    CONCAT(
      'The business date a receipt is recognised on. Evidence: the approved ',
      'REVENUE_AMOUNT declaration (FIN-023) already cuts its extraction window on ',
      'ReceiptDate, so the analysis behind it treated this column as the financial ',
      'axis. Awaiting confirmation from the source operator that no separate ',
      'business-date column exists and that ReceiptDate is not itself rewritten on ',
      'edit. Until then this is the declared date and its version is stamped on ',
      'every fact, so a later correction can be explained rather than discovered.'),
    NULL, NULL, '2019-04-01', NULL,
    0, NOW(6), NOW(6), 0, 0
FROM fin_source_system s WHERE s.temple_id = 300001 AND s.system_code = 'KOLSOHAM';

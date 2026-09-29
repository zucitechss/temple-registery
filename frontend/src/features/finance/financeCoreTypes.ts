/**
 * Shared finance types (Phase 0 contract).
 *
 * The single home for types that more than one finance feature folder needs. Before this file,
 * availability and freshness lived inside `finance-reporting`, which meant any other feature
 * wanting them had to import across a feature boundary and would sooner or later copy them
 * instead.
 *
 * Ownership: the reporting stream owns this file. Other feature folders import from it and do
 * not extend it; a type only one folder needs belongs in that folder.
 *
 * Mirrors the backend records in `com.templeregistry.dto.response.finance`. Field names and
 * optionality are copied from the Java records, not from prose, because the two can drift and the
 * records are what actually serialises.
 */

// ─── availability, freshness, verification ──────────────────────────────────

export type DataAvailability = 'AVAILABLE' | 'PARTIALLY_AVAILABLE' | 'NOT_AVAILABLE' | 'NOT_APPLICABLE'

export type ReconciliationStatus = 'PASSED' | 'FAILED' | 'NOT_AVAILABLE' | 'PENDING'

export type ReconciliationCheckType =
  | 'STAGE_COMPLETENESS' | 'REJECTION_ACCOUNTING' | 'SOURCE_VS_CENTRAL' | 'SUSPECTED_SOURCE_DELETION'

export type FinanceCapability =
  | 'REVENUE' | 'SEVA' | 'DONATION' | 'PRASADAM_SALE' | 'PAYMENT_MODE' | 'CANCELLATION'
  | 'PRECIOUS_METAL_COUNT' | 'PRECIOUS_METAL_WEIGHT' | 'PRECIOUS_METAL_VALUE'
  | 'NIRANTARA_SUBSCRIPTION' | 'NIRANTARA_PAYMENT' | 'NIRANTARA_SCHEDULE' | 'NIRANTARA_EXECUTION'
  | 'EXPENSE' | 'EXPENSE_CATEGORY' | 'GRANT' | 'GRANT_UTILISATION' | 'WORKS' | 'IN_KIND_DONATION'

/**
 * Every numeric metric the finance API returns is wrapped in this shape, so absence and presence
 * share one code path. `value` is non-null only when `availability` is `AVAILABLE` or
 * `PARTIALLY_AVAILABLE`.
 *
 * Never render `0` when it is null. That is exactly the missing-data-becomes-zero failure this
 * envelope exists to prevent (ADR-007).
 */
export interface MetricEnvelope {
  value: number | null
  unit: string | null
  availability: DataAvailability
  reason: string | null
  asOfDate: string | null
  reconciliation: ReconciliationStatus
}

export interface DataFreshnessBlock {
  lastSyncedAt: string | null
  sourceDataThrough: string | null
  status: 'FRESH' | 'STALE'
  stalenessReason: string | null
}

/**
 * The one-line verdict a report carries alongside its rows.
 *
 * `inherited` is true when the verdict belongs to a wider scope than the period shown — a monthly
 * figure inherits its financial year verdict, because reconciliation records no month-scoped
 * result. Do not present an inherited verdict as if the month itself was checked.
 *
 * For a manual or Excel source, `status` is permanently `NOT_AVAILABLE` and `note` says why. That
 * is not a failure; it is a self-reported figure, and it must not wear the same badge as a
 * source-verified one.
 */
export interface ReconciliationSummary {
  status: ReconciliationStatus
  inherited: boolean
  scope: string
  checkedAt: string | null
  note: string | null
}

// ─── the shared report envelope (FR12–FR15) ─────────────────────────────────

export type ReportColumnType = 'TEXT' | 'NUMBER' | 'MONEY' | 'DATE' | 'WEIGHT' | 'PERCENT' | 'STATUS'

/**
 * The API describes its own columns, so one grid, one PDF renderer and one spreadsheet writer
 * serve all eleven reports. A column added to a report appears in all three with no frontend
 * change.
 *
 * `nullMeans` is what an absent value means in this column — render it in place of a blank cell
 * rather than showing a dash the reader has to interpret.
 */
export interface ReportColumn {
  key: string
  label: string
  type: ReportColumnType
  unit: string | null
  sortable: boolean
  nullMeans: string | null
}

/**
 * The one response shape every finance report returns.
 *
 * Chart, grid and both exports render from a single call, so a figure cannot differ between the
 * chart and the table.
 *
 * **Check `availability` before `rows`.** A report the temple source cannot answer returns
 * `NOT_AVAILABLE` with a reason and no rows. An empty `rows` therefore means one thing only: this
 * period genuinely had nothing. Drawing an empty chart for an unavailable report is the bug this
 * field prevents.
 */
export interface ReportTableResponse {
  rows: Record<string, unknown>[]
  columns: ReportColumn[]
  totalRows: number
  page: number
  pageSize: number
  availability: DataAvailability
  unavailableReason: string | null
  freshness: DataFreshnessBlock
  reconciliation: ReconciliationSummary | null
  /** Which store answered this. `FACTS` means the range did not align to a stored period. */
  servedFrom: 'AGGREGATE' | 'FACTS' | null
}

// ─── daily freshness and alerts (FR17–FR20) ─────────────────────────────────

export type DataSubmissionStatus = 'EXPECTED' | 'SUBMITTED' | 'NIL_RETURN' | 'MISSED' | 'WAIVED'

export type AlertSeverity = 'LOW' | 'HIGH'

export type AlertStatus = 'OPEN' | 'CLOSED'

/**
 * One standing missed-entry condition, as both dashboards display it.
 *
 * `consecutiveMissedDays` is recomputed server-side on every evaluation, so this number can go
 * down as well as up after a partial backfill. Do not cache it as a monotonic counter.
 */
export interface FinanceAlertRow {
  id: number
  templeId: number
  templeName: string
  capability: FinanceCapability
  firstMissedDate: string
  lastMissedDate: string
  consecutiveMissedDays: number
  severity: AlertSeverity
  status: AlertStatus
  openedAt: string
  escalatedAt: string | null
  closedAt: string | null
}

/**
 * Props of `<FinanceAlertPanel />` — the single component that crosses between the two build
 * streams (Phase 0 contract).
 *
 * The input stream implements it under `features/finance-alerts`; the reporting stream imports it
 * and places it on the DC dashboard. These props are frozen: the reporting side ships a stub with
 * this exact signature until the real component lands, and the swap is then a one-line import
 * change.
 */
export interface FinanceAlertPanelProps {
  /** Temples in the caller scope. A temple user passes exactly one. */
  templeIds: number[]
  /** Which dashboard is rendering it. Changes the wording and whether the temple name is shown. */
  scope: 'DC' | 'TEMPLE'
  /** Optional: jump to the outstanding-days view for a temple. Omit to render alerts read-only. */
  onSelectTemple?: (templeId: number) => void
}

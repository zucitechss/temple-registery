/**
 * Finance reporting types (FIN-090).
 *
 * Mirrors the backend DTOs exactly — see `docs/finance/API_CONTRACT.md` and the records in
 * `com.templeregistry.dto.response.finance`. Field names and optionality are copied from the Java
 * records, not from the prose contract, because the two can drift and the records are what
 * actually serialises.
 */

export type DataAvailability = 'AVAILABLE' | 'PARTIALLY_AVAILABLE' | 'NOT_AVAILABLE' | 'NOT_APPLICABLE'

export type ReconciliationStatus = 'PASSED' | 'FAILED' | 'NOT_AVAILABLE' | 'PENDING'

export type FinanceCapability =
  | 'REVENUE' | 'SEVA' | 'DONATION' | 'PRASADAM_SALE' | 'PAYMENT_MODE' | 'CANCELLATION'
  | 'PRECIOUS_METAL_COUNT' | 'PRECIOUS_METAL_WEIGHT' | 'PRECIOUS_METAL_VALUE'
  | 'NIRANTARA_SUBSCRIPTION' | 'NIRANTARA_PAYMENT' | 'NIRANTARA_SCHEDULE' | 'NIRANTARA_EXECUTION'
  | 'EXPENSE' | 'EXPENSE_CATEGORY' | 'GRANT' | 'GRANT_UTILISATION' | 'WORKS' | 'IN_KIND_DONATION'

export type ReconciliationCheckType =
  | 'STAGE_COMPLETENESS' | 'REJECTION_ACCOUNTING' | 'SOURCE_VS_CENTRAL' | 'SUSPECTED_SOURCE_DELETION'

/**
 * Every numeric metric this API returns is wrapped in this shape (API_CONTRACT §2), so absence
 * and presence share one code path. `value` is non-null only when `availability` is `AVAILABLE`
 * or `PARTIALLY_AVAILABLE` — never render a `0` when it is null; that is exactly the "convert
 * missing data to zero" failure this envelope exists to prevent (ADR-007).
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

export interface FinanceCapabilityRow {
  capability: FinanceCapability
  availability: DataAvailability
  reason: string | null
  coverageFrom: string | null
  coverageTo: string | null
  knownGaps: string[]
}

export interface FinanceCapabilitiesResponse {
  templeId: number
  capabilities: FinanceCapabilityRow[]
}

export interface FinanceSummaryResponse {
  templeId: number
  financialYear: string
  grossRevenue: MetricEnvelope
  netRevenue: MetricEnvelope
  transactionCount: MetricEnvelope
  expenditure: MetricEnvelope
  dataFreshness: DataFreshnessBlock
}

export interface RevenueTrendResponse {
  templeId: number
  years: { financialYear: string; grossRevenue: MetricEnvelope }[]
  dataFreshness: DataFreshnessBlock
}

export interface MonthlyRevenueResponse {
  templeId: number
  financialYear: string
  months: { month: string; grossRevenue: MetricEnvelope }[]
  dataFreshness: DataFreshnessBlock
}

export interface CategoryBreakdownResponse {
  templeId: number
  financialYear: string
  categories: { categoryCode: string; categoryName: string; grossRevenue: MetricEnvelope }[]
  dataFreshness: DataFreshnessBlock
}

export interface ReconciliationCheckResult {
  sourceSystemId: number
  checkType: ReconciliationCheckType
  metric: string
  sourceTotal: number | null
  centralTotal: number | null
  difference: number | null
  differencePct: number | null
  status: ReconciliationStatus
  statusReason: string | null
  checkedAt: string
}

export interface ReconciliationResponse {
  templeId: number
  financialYear: string
  checks: ReconciliationCheckResult[]
}

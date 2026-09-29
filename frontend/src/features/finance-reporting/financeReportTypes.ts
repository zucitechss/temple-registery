/**
 * Finance reporting types (FIN-090).
 *
 * Mirrors the backend DTOs exactly — see `docs/finance/API_CONTRACT.md` and the records in
 * `com.templeregistry.dto.response.finance`. Field names and optionality are copied from the Java
 * records, not from the prose contract, because the two can drift and the records are what
 * actually serialises.
 */

/**
 * The shared finance types now live in `features/finance/financeCoreTypes.ts` (Phase 0), because
 * the temple input, Excel and alert features need them too and importing across a feature
 * boundary invites a copy.
 *
 * They are re-exported here so that existing imports from this module keep working. New code
 * should import from `financeCoreTypes` directly.
 */
export type {
  DataAvailability,
  ReconciliationStatus,
  ReconciliationCheckType,
  FinanceCapability,
  MetricEnvelope,
  DataFreshnessBlock,
  ReconciliationSummary,
  ReportColumn,
  ReportColumnType,
  ReportTableResponse,
} from '../finance/financeCoreTypes'

import type {
  DataAvailability,
  ReconciliationStatus,
  ReconciliationCheckType,
  FinanceCapability,
  MetricEnvelope,
  DataFreshnessBlock,
} from '../finance/financeCoreTypes'

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

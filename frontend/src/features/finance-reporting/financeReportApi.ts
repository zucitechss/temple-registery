import { createApi } from '@reduxjs/toolkit/query/react'
import { baseQueryWithReauth } from '../../services/baseQueryWithReauth'
import type { ApiResponse } from '@/types'
import { financeReportRequests } from './financeReportRequests'
import type {
  CategoryBreakdownResponse,
  FinanceCapabilitiesResponse,
  FinanceSummaryResponse,
  MonthlyRevenueResponse,
  ReconciliationResponse,
  RevenueTrendResponse,
} from './financeReportTypes'

/**
 * The finance reporting API (FIN-090), against the endpoints FIN-081/082/083 implemented.
 *
 * <p>Read-only. Every response is a published figure — reconciled and gated before it was ever
 * written to `fin_agg_revenue_period` — so there is nothing here to invalidate on a local action;
 * these caches only go stale when the sync worker publishes something new, which this tab has no
 * way to know about, so a short `keepUnusedDataFor` matters less than usual and refetch-on-mount
 * is left at its default instead.
 */
export const financeReportApi = createApi({
  reducerPath: 'financeReportApi',
  baseQuery: baseQueryWithReauth,
  endpoints: (builder) => ({
    getFinanceCapabilities: builder.query<ApiResponse<FinanceCapabilitiesResponse>, number>({
      query: financeReportRequests.capabilities,
    }),

    getFinanceSummary: builder.query<
      ApiResponse<FinanceSummaryResponse>,
      { templeId: number; financialYear: string }
    >({
      query: financeReportRequests.summary,
    }),

    getRevenueTrend: builder.query<ApiResponse<RevenueTrendResponse>, number>({
      query: financeReportRequests.revenueTrend,
    }),

    getMonthlyRevenue: builder.query<
      ApiResponse<MonthlyRevenueResponse>,
      { templeId: number; financialYear: string }
    >({
      query: financeReportRequests.monthlyRevenue,
    }),

    getCategoryBreakdown: builder.query<
      ApiResponse<CategoryBreakdownResponse>,
      { templeId: number; financialYear: string }
    >({
      query: financeReportRequests.categoryBreakdown,
    }),

    getReconciliation: builder.query<
      ApiResponse<ReconciliationResponse>,
      { templeId: number; financialYear: string }
    >({
      query: financeReportRequests.reconciliation,
    }),
  }),
})

export const {
  useGetFinanceCapabilitiesQuery,
  useGetFinanceSummaryQuery,
  useGetRevenueTrendQuery,
  useGetMonthlyRevenueQuery,
  useGetCategoryBreakdownQuery,
  useGetReconciliationQuery,
} = financeReportApi

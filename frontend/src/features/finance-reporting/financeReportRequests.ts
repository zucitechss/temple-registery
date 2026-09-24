/**
 * Every request this feature is capable of making (FIN-090), against the endpoints FIN-081/082/083
 * implemented under `/api/v1/dc/temples/{templeId}/finance`.
 *
 * <p>Pure functions, used by `financeReportApi` and asserted directly by tests — the same
 * separation `financeRequests` (FIN-054B) uses, and for the same reason: a test can read the
 * whole set and prove a negative (nothing here writes anything) that watching one render could
 * never establish.
 */
export const financeReportRequests = {
  capabilities: (templeId: number) => `/dc/temples/${templeId}/finance/capabilities`,

  summary: ({ templeId, financialYear }: { templeId: number; financialYear: string }) => ({
    url: `/dc/temples/${templeId}/finance/summary`,
    params: { financialYear },
  }),

  revenueTrend: (templeId: number) => `/dc/temples/${templeId}/finance/revenue/trend`,

  monthlyRevenue: ({ templeId, financialYear }: { templeId: number; financialYear: string }) => ({
    url: `/dc/temples/${templeId}/finance/revenue/monthly`,
    params: { financialYear },
  }),

  categoryBreakdown: ({ templeId, financialYear }: { templeId: number; financialYear: string }) => ({
    url: `/dc/temples/${templeId}/finance/revenue/categories`,
    params: { financialYear },
  }),

  reconciliation: ({ templeId, financialYear }: { templeId: number; financialYear: string }) => ({
    url: `/dc/temples/${templeId}/finance/reconciliation`,
    params: { financialYear },
  }),
} as const

export type FinanceReportRequestName = keyof typeof financeReportRequests

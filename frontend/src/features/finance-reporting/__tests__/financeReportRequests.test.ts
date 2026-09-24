import { describe, expect, it } from 'vitest'
import { financeReportRequests } from '../financeReportRequests'

/**
 * The request contract, asserted without a network (FIN-090) — mirrors
 * `finance/__tests__/financeRequests.test.ts`'s own approach for the same reason: reading the
 * whole set proves a negative a single render could not.
 */
describe('financeReportRequests — URLs and parameters', () => {
  it('reads capabilities and revenue trend from the documented paths', () => {
    expect(financeReportRequests.capabilities(300001)).toBe('/dc/temples/300001/finance/capabilities')
    expect(financeReportRequests.revenueTrend(300001)).toBe('/dc/temples/300001/finance/revenue/trend')
  })

  it('scopes summary, monthly, categories and reconciliation to one temple and one financial year', () => {
    const templeId = 300001
    const financialYear = '2025-26'

    expect(financeReportRequests.summary({ templeId, financialYear })).toEqual({
      url: '/dc/temples/300001/finance/summary',
      params: { financialYear: '2025-26' },
    })
    expect(financeReportRequests.monthlyRevenue({ templeId, financialYear })).toEqual({
      url: '/dc/temples/300001/finance/revenue/monthly',
      params: { financialYear: '2025-26' },
    })
    expect(financeReportRequests.categoryBreakdown({ templeId, financialYear })).toEqual({
      url: '/dc/temples/300001/finance/revenue/categories',
      params: { financialYear: '2025-26' },
    })
    expect(financeReportRequests.reconciliation({ templeId, financialYear })).toEqual({
      url: '/dc/temples/300001/finance/reconciliation',
      params: { financialYear: '2025-26' },
    })
  })

  it('never hard-codes a temple id — every request takes it as a parameter', () => {
    // The whole point of FIN-093: nothing here may only work for temple 300001.
    expect(financeReportRequests.capabilities(999999)).toContain('/999999/')
    expect(financeReportRequests.revenueTrend(999999)).toContain('/999999/')
  })
})

describe('financeReportRequests — read-only surface', () => {
  const allRequests = () => [
    financeReportRequests.capabilities(1),
    financeReportRequests.summary({ templeId: 1, financialYear: '2025-26' }),
    financeReportRequests.revenueTrend(1),
    financeReportRequests.monthlyRevenue({ templeId: 1, financialYear: '2025-26' }),
    financeReportRequests.categoryBreakdown({ templeId: 1, financialYear: '2025-26' }),
    financeReportRequests.reconciliation({ templeId: 1, financialYear: '2025-26' }),
  ]

  const urlOf = (r: string | { url: string }) => (typeof r === 'string' ? r : r.url)

  it('issues nothing but GET — this dashboard reads published figures and changes nothing', () => {
    allRequests().forEach((r) => {
      expect(typeof r === 'string' || (r as { method?: string }).method === undefined).toBe(true)
    })
  })

  it('confines every request to this temple’s finance reporting surface', () => {
    allRequests().map(urlOf).forEach((url) => {
      expect(url).toMatch(/^\/dc\/temples\/\d+\/finance\//)
    })
  })

  it('has no request outside the six endpoints FIN-081/082/083 actually implemented', () => {
    // revenue/sevas is blocked on FIN-071 (FIN-D-069); cancellations and sync-status are not
    // built yet. A request module offering them would promise data the backend cannot serve.
    expect(Object.keys(financeReportRequests)).toHaveLength(6)
  })
})

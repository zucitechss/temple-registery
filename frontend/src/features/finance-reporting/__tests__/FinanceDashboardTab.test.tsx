import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { FinanceDashboardTab } from '../pages/FinanceDashboardTab/FinanceDashboardTab'
import type {
  FinanceCapabilitiesResponse,
  FinanceSummaryResponse,
  MetricEnvelope,
  RevenueTrendResponse,
} from '../financeReportTypes'

// The project's convention for page tests (SourceMapperPage.test.tsx): mock the RTK Query hooks.
// The request contract itself is asserted separately in financeReportRequests.test.ts.
vi.mock('../financeReportApi', () => ({
  financeReportApi: {
    reducerPath: 'financeReportApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useGetFinanceCapabilitiesQuery: vi.fn(),
  useGetFinanceSummaryQuery: vi.fn(),
  useGetRevenueTrendQuery: vi.fn(),
  useGetMonthlyRevenueQuery: vi.fn(),
  useGetCategoryBreakdownQuery: vi.fn(),
  useGetReconciliationQuery: vi.fn(),
}))

import {
  useGetCategoryBreakdownQuery,
  useGetFinanceCapabilitiesQuery,
  useGetFinanceSummaryQuery,
  useGetMonthlyRevenueQuery,
  useGetReconciliationQuery,
  useGetRevenueTrendQuery,
} from '../financeReportApi'

const available = (value: number, unit: string | null = 'INR'): MetricEnvelope => ({
  value,
  unit,
  availability: 'AVAILABLE',
  reason: null,
  asOfDate: '2026-03-31',
  reconciliation: 'PASSED',
})

const notAvailable = (reason: string): MetricEnvelope => ({
  value: null,
  unit: null,
  availability: 'NOT_AVAILABLE',
  reason,
  asOfDate: null,
  reconciliation: 'NOT_AVAILABLE',
})

const FRESHNESS = {
  lastSyncedAt: '2026-07-27T02:00:00',
  sourceDataThrough: '2026-07-26',
  status: 'FRESH' as const,
  stalenessReason: null,
}

function mockDefaults() {
  vi.mocked(useGetFinanceCapabilitiesQuery).mockReturnValue({
    data: { success: true, data: { templeId: 300001, capabilities: [] } as FinanceCapabilitiesResponse },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetFinanceCapabilitiesQuery>)

  vi.mocked(useGetRevenueTrendQuery).mockReturnValue({
    data: { success: true, data: { templeId: 300001, years: [], dataFreshness: FRESHNESS } as RevenueTrendResponse },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetRevenueTrendQuery>)

  vi.mocked(useGetFinanceSummaryQuery).mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetFinanceSummaryQuery>)

  vi.mocked(useGetMonthlyRevenueQuery).mockReturnValue({
    data: undefined, isLoading: false, isError: false,
  } as ReturnType<typeof useGetMonthlyRevenueQuery>)

  vi.mocked(useGetCategoryBreakdownQuery).mockReturnValue({
    data: undefined, isLoading: false, isError: false,
  } as ReturnType<typeof useGetCategoryBreakdownQuery>)

  vi.mocked(useGetReconciliationQuery).mockReturnValue({
    data: undefined, isLoading: false, isError: false,
  } as ReturnType<typeof useGetReconciliationQuery>)
}

describe('FinanceDashboardTab (FIN-091)', () => {
  it('works for a temple that is not 300001 — no hard-coded temple gate', () => {
    mockDefaults()
    renderWithProviders(<FinanceDashboardTab templeId={999999} />)

    expect(useGetFinanceCapabilitiesQuery).toHaveBeenCalledWith(999999)
    expect(useGetRevenueTrendQuery).toHaveBeenCalledWith(999999)
  })

  it('shows a loading state while capabilities are loading, not an empty dashboard', () => {
    mockDefaults()
    vi.mocked(useGetFinanceCapabilitiesQuery).mockReturnValue({
      data: undefined, isLoading: true, isError: false,
    } as ReturnType<typeof useGetFinanceCapabilitiesQuery>)

    const { container } = renderWithProviders(<FinanceDashboardTab templeId={300001} />)
    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
  })

  it('shows an error, not a blank screen, when the API call fails', () => {
    mockDefaults()
    vi.mocked(useGetFinanceCapabilitiesQuery).mockReturnValue({
      data: undefined, isLoading: false, isError: true, error: { status: 500 },
    } as ReturnType<typeof useGetFinanceCapabilitiesQuery>)

    renderWithProviders(<FinanceDashboardTab templeId={300001} />)
    expect(screen.getByText(/Could not load finance figures/i)).toBeInTheDocument()
  })

  it('renders a NOT_AVAILABLE metric with its reason, never a fabricated zero', () => {
    mockDefaults()
    const summary: FinanceSummaryResponse = {
      templeId: 300001,
      financialYear: '2025-26',
      grossRevenue: available(90616293600),
      netRevenue: available(90143310400),
      transactionCount: available(3950094, 'RECEIPTS'),
      expenditure: notAvailable('The source system does not record expenditure.'),
      dataFreshness: FRESHNESS,
    }
    vi.mocked(useGetFinanceSummaryQuery).mockReturnValue({
      data: { success: true, data: summary }, isLoading: false, isError: false,
    } as ReturnType<typeof useGetFinanceSummaryQuery>)

    renderWithProviders(<FinanceDashboardTab templeId={300001} />)

    expect(screen.getByText(/The source system does not record expenditure\./)).toBeInTheDocument()
    // No literal "0" or "₹0" anywhere standing in for the unavailable expenditure figure.
    expect(screen.queryByText('₹0')).not.toBeInTheDocument()
  })

  it('never renders receipts as a devotee count', () => {
    mockDefaults()
    const summary: FinanceSummaryResponse = {
      templeId: 300001,
      financialYear: '2025-26',
      grossRevenue: available(100),
      netRevenue: available(100),
      transactionCount: available(3950094, 'RECEIPTS'),
      expenditure: notAvailable('Not recorded.'),
      dataFreshness: FRESHNESS,
    }
    vi.mocked(useGetFinanceSummaryQuery).mockReturnValue({
      data: { success: true, data: summary }, isLoading: false, isError: false,
    } as ReturnType<typeof useGetFinanceSummaryQuery>)

    renderWithProviders(<FinanceDashboardTab templeId={300001} />)

    expect(screen.getByText(/Receipts recorded/i)).toBeInTheDocument()
    expect(screen.queryByText(/devotee/i)).not.toBeInTheDocument()
  })

  it('surfaces a stale-data reason from the backend rather than a generic message', () => {
    mockDefaults()
    const staleFreshness = {
      lastSyncedAt: null,
      sourceDataThrough: null,
      status: 'STALE' as const,
      stalenessReason: 'This temple has not completed a sync yet.',
    }
    const summary: FinanceSummaryResponse = {
      templeId: 300001,
      financialYear: '2025-26',
      grossRevenue: notAvailable('No aggregated revenue has been published for financial year 2025-26.'),
      netRevenue: notAvailable('No aggregated revenue has been published for financial year 2025-26.'),
      transactionCount: notAvailable('No contributing record recorded this for the requested period.'),
      expenditure: notAvailable('No expense capability has been declared for this temple.'),
      dataFreshness: staleFreshness,
    }
    vi.mocked(useGetFinanceSummaryQuery).mockReturnValue({
      data: { success: true, data: summary }, isLoading: false, isError: false,
    } as ReturnType<typeof useGetFinanceSummaryQuery>)

    renderWithProviders(<FinanceDashboardTab templeId={300001} />)

    expect(screen.getByText('This temple has not completed a sync yet.')).toBeInTheDocument()
  })
})

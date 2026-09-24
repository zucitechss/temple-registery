import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { SourceSystemsPage } from '../pages/SourceSystemsPage/SourceSystemsPage'
import type {
  SourceSystemDetail,
  SourceSystemReadiness,
  SourceSystemSummary,
} from '../financeOnboardingTypes'

// The project's convention for page tests (SourceMapperPage.test.tsx): mock the RTK Query hooks.
// The request contract itself is asserted without mocks in financeOnboardingRequests.test.ts.
vi.mock('../financeOnboardingApi', () => ({
  financeOnboardingApi: {
    reducerPath: 'financeOnboardingApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useListOnboardingSourceSystemsQuery: vi.fn(),
  useGetSourceSystemQuery: vi.fn(),
  useGetSourceSystemReadinessQuery: vi.fn(),
  useRegisterSourceSystemMutation: vi.fn(),
  // FIN-140-B: the page now embeds the capability matrix, which reads these.
  useGetCapabilityCatalogueQuery: vi.fn(),
  useListCapabilityDeclarationsQuery: vi.fn(),
  useDeclareCapabilityMutation: vi.fn(),
  useUpdateCapabilityDeclarationMutation: vi.fn(),
  // FIN-140-C: and the source-of-truth panel, which reads these.
  useListSourceOfTruthQuery: vi.fn(),
  useDeclareSourceOfTruthMutation: vi.fn(),
  // FIN-140-D: and the activation panel.
  useSetSourceSystemActivationMutation: vi.fn(),
}))

import {
  useDeclareCapabilityMutation,
  useDeclareSourceOfTruthMutation,
  useGetCapabilityCatalogueQuery,
  useGetSourceSystemQuery,
  useGetSourceSystemReadinessQuery,
  useListCapabilityDeclarationsQuery,
  useListOnboardingSourceSystemsQuery,
  useListSourceOfTruthQuery,
  useRegisterSourceSystemMutation,
  useSetSourceSystemActivationMutation,
  useUpdateCapabilityDeclarationMutation,
} from '../financeOnboardingApi'

const SUMMARY: SourceSystemSummary = {
  id: 7,
  templeId: 300042,
  templeName: 'Sri Ranganatha, Mandya',
  systemCode: 'SRT_MANDYA',
  systemName: 'Sri Ranganatha accounts',
  active: true,
  activeRuleCount: 3,
}

const DETAIL: SourceSystemDetail = {
  id: 7,
  templeId: 300042,
  templeName: 'Sri Ranganatha, Mandya',
  systemCode: 'SRT_MANDYA',
  systemName: 'Sri Ranganatha accounts',
  sourceTechnology: 'POSTGRESQL',
  connectorType: 'PUSH_AGENT',
  connectorBean: 'templeBFinanceConnector',
  sourceTempleCode: null,
  sourceDatabaseName: 'srt_accounts',
  credentialRefSet: true,
  syncScheduleCron: null,
  syncEnabled: false,
  stalenessThresholdHours: 48,
  sourceTimezone: 'Asia/Kolkata',
  notes: null,
  version: 0,
  createdAt: null,
  updatedAt: null,
}

const READINESS: SourceSystemReadiness = {
  sourceSystemId: 7,
  templeId: 300042,
  templeName: 'Sri Ranganatha, Mandya',
  systemCode: 'SRT_MANDYA',
  status: 'READY',
  activationAllowed: true,
  syncEnabled: false,
  connectivityVerified: false,
  connectivityNote: 'Nothing here has contacted the source system.',
  blockingCount: 0,
  warningCount: 0,
  findings: [],
  evaluatedAt: '2026-09-23T10:00:00',
}

function mockDefaults() {
  vi.mocked(useListOnboardingSourceSystemsQuery).mockReturnValue({
    data: { success: true, data: [SUMMARY] },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useListOnboardingSourceSystemsQuery>)

  vi.mocked(useGetSourceSystemQuery).mockReturnValue({
    data: { success: true, data: DETAIL },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetSourceSystemQuery>)

  vi.mocked(useGetSourceSystemReadinessQuery).mockReturnValue({
    data: { success: true, data: READINESS },
    isFetching: false,
    isError: false,
  } as ReturnType<typeof useGetSourceSystemReadinessQuery>)

  vi.mocked(useRegisterSourceSystemMutation).mockReturnValue([
    vi.fn(),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useRegisterSourceSystemMutation>)

  vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
    data: {
      success: true,
      data: {
        capabilities: [{ capability: 'REVENUE', drivesRevenueRequirements: true }],
        availabilities: ['AVAILABLE', 'NOT_AVAILABLE'],
        metrics: [{ metric: 'REVENUE_AMOUNT', field: 'GROSS_AMOUNT', required: true }],
      },
    },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

  vi.mocked(useListCapabilityDeclarationsQuery).mockReturnValue({
    data: { success: true, data: [] },
    isFetching: false,
    isError: false,
  } as ReturnType<typeof useListCapabilityDeclarationsQuery>)

  vi.mocked(useDeclareCapabilityMutation).mockReturnValue([
    vi.fn(),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useDeclareCapabilityMutation>)

  vi.mocked(useUpdateCapabilityDeclarationMutation).mockReturnValue([
    vi.fn(),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useUpdateCapabilityDeclarationMutation>)

  vi.mocked(useListSourceOfTruthQuery).mockReturnValue({
    data: { success: true, data: [] },
    isFetching: false,
    isError: false,
  } as ReturnType<typeof useListSourceOfTruthQuery>)

  vi.mocked(useDeclareSourceOfTruthMutation).mockReturnValue([
    vi.fn(),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useDeclareSourceOfTruthMutation>)

  vi.mocked(useSetSourceSystemActivationMutation).mockReturnValue([
    vi.fn(),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useSetSourceSystemActivationMutation>)
}

describe('SourceSystemsPage (FIN-140 slice 140-A)', () => {
  it('works for any temple — no id is hard-coded', () => {
    mockDefaults()
    renderWithProviders(<SourceSystemsPage />)

    expect(useGetSourceSystemQuery).toHaveBeenCalledWith(7, { skip: false })
    expect(useGetSourceSystemReadinessQuery).toHaveBeenCalledWith(7, { skip: false })
    // Appears in the selector and again in the configuration summary.
    expect(screen.getAllByText(/Sri Ranganatha, Mandya/).length).toBeGreaterThan(0)
  })

  it('reports that a credential is configured, never which one', () => {
    mockDefaults()
    renderWithProviders(<SourceSystemsPage />)

    expect(screen.getByText('Configured')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('templeb-agent-token')
  })

  /**
   * FIN-140-D added the control. What is asserted now is its wording: "enable for sync" grants a
   * permission, whereas "activate", "connect" or "go live" would each imply the platform had
   * started doing something — and nothing reads the flag yet.
   */
  it('offers "Enable for sync", and never a label implying the source is live', () => {
    mockDefaults()
    renderWithProviders(<SourceSystemsPage />)

    expect(screen.getByRole('button', { name: /enable for sync/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^activate$|go live|start sync|^connect$/i }))
      .not.toBeInTheDocument()
  })

  it('states beside the activation control that it performs no connection', () => {
    mockDefaults()
    renderWithProviders(<SourceSystemsPage />)

    expect(screen.getByText(/performs no connection and starts no/i)).toBeInTheDocument()
  })

  it('offers no test-connection control — the runtime cannot reach a temple system', () => {
    mockDefaults()
    renderWithProviders(<SourceSystemsPage />)

    expect(screen.queryByRole('button', { name: /test connection|probe|check connection/i }))
      .not.toBeInTheDocument()
  })

  it('shows an empty state rather than a bare page when nothing is registered', () => {
    mockDefaults()
    vi.mocked(useListOnboardingSourceSystemsQuery).mockReturnValue({
      data: { success: true, data: [] },
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useListOnboardingSourceSystemsQuery>)

    renderWithProviders(<SourceSystemsPage />)

    expect(screen.getByText(/No source system is registered yet/i)).toBeInTheDocument()
  })

  it('shows a loading state, not an empty list, while sources load', () => {
    mockDefaults()
    vi.mocked(useListOnboardingSourceSystemsQuery).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as ReturnType<typeof useListOnboardingSourceSystemsQuery>)

    const { container } = renderWithProviders(<SourceSystemsPage />)

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
    expect(screen.queryByText(/No source system is registered yet/i)).not.toBeInTheDocument()
  })

  it('shows an error, not a blank screen, when the list fails', () => {
    mockDefaults()
    vi.mocked(useListOnboardingSourceSystemsQuery).mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: { status: 403, data: {} },
    } as ReturnType<typeof useListOnboardingSourceSystemsQuery>)

    renderWithProviders(<SourceSystemsPage />)

    expect(screen.getByText(/could not be loaded/i)).toBeInTheDocument()
    expect(screen.getByText(/platform administrator/i)).toBeInTheDocument()
  })

  it('surfaces a blocking readiness finding on the page', () => {
    mockDefaults()
    vi.mocked(useGetSourceSystemReadinessQuery).mockReturnValue({
      data: {
        success: true,
        data: {
          ...READINESS,
          status: 'BLOCKED',
          activationAllowed: false,
          blockingCount: 1,
          findings: [
            {
              code: 'NO_CAPABILITY_DECLARED',
              severity: 'BLOCKED' as const,
              subject: null,
              message: 'No capability has been declared.',
            },
          ],
        },
      },
      isFetching: false,
      isError: false,
    } as ReturnType<typeof useGetSourceSystemReadinessQuery>)

    renderWithProviders(<SourceSystemsPage />)

    expect(screen.getByText(/Configuration incomplete/i)).toBeInTheDocument()
    expect(screen.getByText('No capability has been declared.')).toBeInTheDocument()
  })
})

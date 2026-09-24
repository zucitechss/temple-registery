import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { SourceOfTruthPanel } from '../components/SourceOfTruthPanel'
import type { CapabilityCatalogue, SourceOfTruthDeclaration } from '../financeOnboardingTypes'

vi.mock('../financeOnboardingApi', () => ({
  financeOnboardingApi: {
    reducerPath: 'financeOnboardingApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useGetCapabilityCatalogueQuery: vi.fn(),
  useListSourceOfTruthQuery: vi.fn(),
  useDeclareSourceOfTruthMutation: vi.fn(),
}))

import {
  useDeclareSourceOfTruthMutation,
  useGetCapabilityCatalogueQuery,
  useListSourceOfTruthQuery,
} from '../financeOnboardingApi'

const CATALOGUE: CapabilityCatalogue = {
  capabilities: [],
  availabilities: ['AVAILABLE'],
  metrics: [
    { metric: 'REVENUE_AMOUNT', field: 'GROSS_AMOUNT', required: true },
    { metric: 'REVENUE_TRANSACTION_DATE', field: 'TRANSACTION_DATE', required: true },
    { metric: 'REVENUE_QUANTITY', field: 'QUANTITY', required: false },
  ],
}

const IN_FORCE: SourceOfTruthDeclaration = {
  id: 11,
  sourceSystemId: 7,
  metric: 'REVENUE_AMOUNT',
  version: 2,
  inForce: true,
  sourceObject: 'DailyReceipts',
  sourceField: 'Amount',
  filterPredicate: 'Cancelled = 0',
  rejectedAlternatives: [
    { object: 'ReceiptDetail', field: 'TotalAmount', measuredRows: '16982270', reason: 'Understates by 41%.' },
  ],
  rationale: 'The header amount is the recognised revenue.',
  approved: false,
  approvedBy: null,
  approvedAt: null,
  effectiveFrom: '2019-04-01',
  effectiveTo: null,
  createdAt: null,
  updatedAt: null,
}

const SUPERSEDED: SourceOfTruthDeclaration = {
  ...IN_FORCE,
  id: 10,
  version: 1,
  inForce: false,
  sourceObject: 'LegacyReceipts',
  rejectedAlternatives: [],
  effectiveTo: '2024-04-01',
}

function mockDefaults(declarations: SourceOfTruthDeclaration[] = [IN_FORCE, SUPERSEDED]) {
  vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
    data: { success: true, data: CATALOGUE },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

  vi.mocked(useListSourceOfTruthQuery).mockReturnValue({
    data: { success: true, data: declarations },
    isFetching: false,
    isError: false,
  } as ReturnType<typeof useListSourceOfTruthQuery>)

  vi.mocked(useDeclareSourceOfTruthMutation).mockReturnValue([
    vi.fn(),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useDeclareSourceOfTruthMutation>)
}

describe('SourceOfTruthPanel (FIN-140-C)', () => {
  it('lists every metric the server offers, not only the declared ones', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText('REVENUE_AMOUNT')).toBeInTheDocument()
    expect(screen.getByText('REVENUE_TRANSACTION_DATE')).toBeInTheDocument()
    expect(screen.getByText('REVENUE_QUANTITY')).toBeInTheDocument()
  })

  it('takes the metric list from the server rather than a copy of its own', () => {
    mockDefaults()
    vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
      data: {
        success: true,
        data: {
          ...CATALOGUE,
          metrics: [{ metric: 'A_BRAND_NEW_METRIC', field: 'NEW', required: false }],
        },
      },
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText('A_BRAND_NEW_METRIC')).toBeInTheDocument()
  })

  it('marks a metric nobody has declared, and says what its absence costs', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getAllByText('Not declared').length).toBeGreaterThan(0)
    expect(screen.getByText(/normalization refuses the batch/i)).toBeInTheDocument()
  })

  it('shows which version is in force and renders its field, filter and rationale', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText('In force · v2')).toBeInTheDocument()
    expect(screen.getByText('DailyReceipts.Amount')).toBeInTheDocument()
    // Both versions carry the same filter and rationale, and the superseded one stays rendered.
    expect(screen.getAllByText(/where Cancelled = 0/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/The header amount is the recognised revenue/).length)
      .toBeGreaterThan(0)
  })

  it('renders rejected alternatives with whatever measurement keys they carry', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText('measuredRows:')).toBeInTheDocument()
    expect(screen.getByText(/16982270/)).toBeInTheDocument()
    expect(screen.getByText(/Understates by 41%/)).toBeInTheDocument()
  })

  it('keeps superseded versions on the screen rather than hiding the history', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText('1 superseded version')).toBeInTheDocument()
    expect(screen.getByText(/v1 · 2019-04-01 to 2024-04-01/)).toBeInTheDocument()
  })

  it('says a declaration is awaiting sign-off rather than implying it is settled', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText('Awaiting sign-off')).toBeInTheDocument()
  })

  it('offers "New version" where one is in force and "Declare" where none is', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByRole('button', { name: /new version/i })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^declare$/i }).length).toBe(2)
  })

  it('warns that saving creates a new version rather than editing the old one', async () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    await userEvent.click(screen.getByRole('button', { name: /new version/i }))

    expect(await screen.findByText(/This creates version 3/)).toBeInTheDocument()
    expect(screen.getByText(/kept and closed/i)).toBeInTheDocument()
  })

  it('shows a skeleton while loading, not an empty list', () => {
    mockDefaults([])
    vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

    const { container } = renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
  })

  it('shows the API failure rather than an empty panel', () => {
    mockDefaults()
    vi.mocked(useListSourceOfTruthQuery).mockReturnValue({
      data: undefined,
      isFetching: false,
      isError: true,
      error: { status: 500, data: { message: 'Upstream exploded' } },
    } as ReturnType<typeof useListSourceOfTruthQuery>)

    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getByText(/could not be loaded/i)).toBeInTheDocument()
  })

  it('renders an empty panel state when nothing has been declared at all', () => {
    mockDefaults([])
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.getAllByText('Not declared').length).toBe(3)
    expect(screen.queryByText(/superseded version/)).not.toBeInTheDocument()
  })

  it('exposes no credential, connector bean or database name', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    const rendered = document.body.textContent ?? ''
    expect(rendered).not.toMatch(/credential/i)
    expect(rendered).not.toMatch(/connector/i)
    expect(rendered).not.toMatch(/KOLSOHAM/i)
  })

  it('offers no activate and no test-connection control', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.queryByRole('button', { name: /activate|test connection|probe/i }))
      .not.toBeInTheDocument()
  })

  it('has no control that deletes a declaration — history is never removed', () => {
    mockDefaults()
    renderWithProviders(<SourceOfTruthPanel sourceSystemId={7} />)

    expect(screen.queryByRole('button', { name: /delete|remove|withdraw/i }))
      .not.toBeInTheDocument()
  })
})

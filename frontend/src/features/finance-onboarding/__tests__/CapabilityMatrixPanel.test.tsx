import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { installRadixJsdomPolyfills } from '@/features/finance/__tests__/radixJsdomPolyfills'
import { CapabilityMatrixPanel } from '../components/CapabilityMatrixPanel'
import type { CapabilityCatalogue, CapabilityDeclaration } from '../financeOnboardingTypes'

installRadixJsdomPolyfills()

vi.mock('../financeOnboardingApi', () => ({
  financeOnboardingApi: {
    reducerPath: 'financeOnboardingApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useGetCapabilityCatalogueQuery: vi.fn(),
  useListCapabilityDeclarationsQuery: vi.fn(),
  useDeclareCapabilityMutation: vi.fn(),
  useUpdateCapabilityDeclarationMutation: vi.fn(),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

import {
  useDeclareCapabilityMutation,
  useGetCapabilityCatalogueQuery,
  useListCapabilityDeclarationsQuery,
  useUpdateCapabilityDeclarationMutation,
} from '../financeOnboardingApi'

const CATALOGUE: CapabilityCatalogue = {
  capabilities: [
    { capability: 'REVENUE', drivesRevenueRequirements: true },
    { capability: 'EXPENSE', drivesRevenueRequirements: false },
    { capability: 'NIRANTARA_SCHEDULE', drivesRevenueRequirements: false },
  ],
  availabilities: ['AVAILABLE', 'PARTIALLY_AVAILABLE', 'NOT_AVAILABLE', 'NOT_APPLICABLE'],
}

const REVENUE_DECLARATION: CapabilityDeclaration = {
  id: 11,
  sourceSystemId: 7,
  templeId: 300042,
  capability: 'REVENUE',
  availability: 'AVAILABLE',
  availabilityReason: 'Receipts are recorded in full.',
  coverageFrom: '2022-04-01',
  coverageTo: null,
  knownGaps: [],
  lastReviewedAt: '2026-09-23T10:00:00',
  version: 0,
  createdAt: null,
  updatedAt: null,
}

function mockDefaults(declarations: CapabilityDeclaration[] = [REVENUE_DECLARATION]) {
  vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
    data: { success: true, data: CATALOGUE },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

  vi.mocked(useListCapabilityDeclarationsQuery).mockReturnValue({
    data: { success: true, data: declarations },
    isFetching: false,
    isError: false,
  } as ReturnType<typeof useListCapabilityDeclarationsQuery>)

  vi.mocked(useDeclareCapabilityMutation).mockReturnValue([
    vi.fn(() => ({ unwrap: () => Promise.resolve({ data: REVENUE_DECLARATION }) })),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useDeclareCapabilityMutation>)

  vi.mocked(useUpdateCapabilityDeclarationMutation).mockReturnValue([
    vi.fn(() => ({ unwrap: () => Promise.resolve({ data: REVENUE_DECLARATION }) })),
    { isLoading: false },
  ] as unknown as ReturnType<typeof useUpdateCapabilityDeclarationMutation>)
}

describe('CapabilityMatrixPanel (FIN-140-B)', () => {
  it('lists the whole canonical catalogue, not only what is declared', () => {
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getByText('REVENUE')).toBeInTheDocument()
    expect(screen.getByText('EXPENSE')).toBeInTheDocument()
    expect(screen.getByText('NIRANTARA SCHEDULE')).toBeInTheDocument()
  })

  it('marks undeclared capabilities as such rather than leaving them blank', () => {
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getAllByText('Not declared')).toHaveLength(2)
    expect(screen.getByText(/nobody has checked/i)).toBeInTheDocument()
  })

  it('renders an existing declaration with its reason and coverage', () => {
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getByText('Receipts are recorded in full.')).toBeInTheDocument()
    expect(screen.getByText(/Coverage 2022-04-01 to present/)).toBeInTheDocument()
  })

  it('offers Declare for an undeclared capability and Revise for a declared one', () => {
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getAllByRole('button', { name: /declare/i })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: /revise/i })).toHaveLength(1)
  })

  it('takes its capability list from the server, never a local constant', () => {
    mockDefaults()
    vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
      data: {
        success: true,
        data: {
          capabilities: [{ capability: 'A_BRAND_NEW_CAPABILITY', drivesRevenueRequirements: false }],
          availabilities: ['AVAILABLE'],
        },
      },
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getByText('A BRAND NEW CAPABILITY'))
      .toBeInTheDocument()
    expect(screen.queryByText('EXPENSE')).not.toBeInTheDocument()
  })

  it('shows a skeleton while loading, not an empty matrix', () => {
    mockDefaults()
    vi.mocked(useGetCapabilityCatalogueQuery).mockReturnValue({
      data: undefined, isLoading: true, isError: false,
    } as ReturnType<typeof useGetCapabilityCatalogueQuery>)

    const { container } = renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
    expect(screen.queryByText('REVENUE')).not.toBeInTheDocument()
  })

  it('shows an empty-but-complete matrix when nothing has been declared yet', () => {
    mockDefaults([])
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getByText(/0 of 3 declared/)).toBeInTheDocument()
    expect(screen.getAllByText('Not declared')).toHaveLength(3)
  })

  it('shows an error rather than a blank panel when the API fails', () => {
    mockDefaults()
    vi.mocked(useListCapabilityDeclarationsQuery).mockReturnValue({
      data: undefined, isFetching: false, isError: true, error: { status: 403, data: {} },
    } as ReturnType<typeof useListCapabilityDeclarationsQuery>)

    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.getByText(/could not be loaded/i)).toBeInTheDocument()
    expect(screen.getByText(/platform administrator/i)).toBeInTheDocument()
  })

  it('opens the drawer for the capability that was clicked', async () => {
    const user = userEvent.setup()
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    await user.click(screen.getAllByRole('button', { name: /revise/i })[0])

    expect(await screen.findByText(/Revise declaration/i)).toBeInTheDocument()
    expect(screen.getByText(/pulls further configuration in/i))
      .toBeInTheDocument()
  })

  it('never renders a credential, connector bean or database name', () => {
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    const rendered = document.body.textContent ?? ''
    expect(rendered).not.toMatch(/credential/i)
    expect(rendered).not.toMatch(/connectorBean|jdbc|password/i)
  })

  it('offers no activate or test-connection control', () => {
    mockDefaults()
    renderWithProviders(<CapabilityMatrixPanel sourceSystemId={7} />)

    expect(screen.queryByRole('button', { name: /activate|switch on|test connection/i }))
      .not.toBeInTheDocument()
  })
})

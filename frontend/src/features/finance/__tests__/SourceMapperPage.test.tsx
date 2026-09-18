import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { installRadixJsdomPolyfills } from './radixJsdomPolyfills'
import { SourceMapperPage } from '../pages/SourceMapperPage/SourceMapperPage'
import type { MappingRule, SourceSystemSummary } from '../financeTypes'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

// The project's convention for page tests: mock the RTK Query hooks. The request contract itself
// is asserted separately and exactly in financeRequests.test.ts.
vi.mock('../financeApi', () => ({
  financeApi: {
    reducerPath: 'financeApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useListSourceSystemsQuery: vi.fn(),
  useListCanonicalValuesQuery: vi.fn(),
  useGetNamespacesQuery: vi.fn(),
  useListMappingRulesQuery: vi.fn(),
  useGetMappingRuleQuery: vi.fn(),
  useListUnresolvedValuesQuery: vi.fn(),
  useCreateMappingRuleMutation: vi.fn(),
  useUpdateMappingRuleMutation: vi.fn(),
  useSetMappingRuleStatusMutation: vi.fn(),
}))

import {
  useCreateMappingRuleMutation,
  useGetNamespacesQuery,
  useListCanonicalValuesQuery,
  useListMappingRulesQuery,
  useListSourceSystemsQuery,
  useListUnresolvedValuesQuery,
  useUpdateMappingRuleMutation,
  useSetMappingRuleStatusMutation,
} from '../financeApi'

const SOURCE: SourceSystemSummary = {
  id: 501,
  templeId: 300001,
  templeName: 'Kollur Sri Mookambika Devi Temple',
  systemCode: 'KOLSOHAM',
  systemName: 'Temple operational system',
  active: true,
  activeRuleCount: 9,
}

const rule = (over: Partial<MappingRule> = {}): MappingRule => ({
  id: 1,
  sourceSystemId: 501,
  templeId: 300001,
  mappingType: 'REVENUE_CATEGORY',
  namespace: 'SEVA_CODE',
  sourceValue: '430',
  storedValue: 'SEVA_CODE:430',
  wellFormed: true,
  sourceLabel: 'Archana',
  canonicalValue: 'SEVA',
  canonicalValueKnown: true,
  priority: 100,
  active: true,
  notes: null,
  version: 3,
  createdBy: 2,
  createdAt: '2026-09-01T09:00:00',
  updatedBy: 2,
  updatedAt: '2026-09-10T09:00:00',
  ...over,
})

const wrap = <T,>(data: T, over: Record<string, unknown> = {}) =>
  ({ data: { success: true, message: 'OK', data }, isLoading: false, isFetching: false,
     isError: false, error: undefined, refetch: vi.fn(), ...over }) as never

const asRole = (role: string) => ({
  preloadedState: {
    auth: {
      currentUser: { id: 1, username: 'u', email: 'u@x.com', fullName: 'U', role, aadhaarVerified: true },
      isAuthenticated: true,
    },
  } as never,
})

const rulesPage = (content: MappingRule[], totalElements = content.length) =>
  wrap({ content, page: 0, size: 20, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / 20)), last: true })

const unresolved = (over: Record<string, unknown> = {}) =>
  wrap({
    sourceSystemId: 501,
    syncBatchId: 77,
    outcome: 'UNMAPPED',
    observedAt: '2026-09-17T12:00:00',
    values: [
      { namespace: 'SEVA_CODE', sourceValue: '  431 ', affected: 120, lastSeenAt: '2026-09-17T12:00:00' },
      { namespace: 'SANNIDHI', sourceValue: 'KN', affected: 12, lastSeenAt: '2026-09-17T11:00:00' },
    ],
    ...over,
  })

beforeEach(() => {
  installRadixJsdomPolyfills()
  vi.clearAllMocks()
  vi.mocked(useListSourceSystemsQuery).mockReturnValue(wrap([SOURCE]))
  vi.mocked(useListCanonicalValuesQuery).mockReturnValue(
    wrap([{ categoryCode: 'SEVA', categoryName: 'Seva', description: null, displayOrder: 10 }]),
  )
  vi.mocked(useGetNamespacesQuery).mockReturnValue(
    wrap({ sourceSystemId: 501, observed: ['SEVA_CODE'], inUseByRules: ['SEVA_CODE'], sampledRows: 200 }),
  )
  vi.mocked(useListMappingRulesQuery).mockImplementation(((args: { size?: number; active?: boolean }) =>
    args?.size === 1 && args?.active === false
      ? rulesPage([], 4)
      : rulesPage([
          rule(),
          rule({ id: 2, namespace: null, sourceValue: null, storedValue: 'NO_FIELD_HERE', wellFormed: false }),
        ])) as never)
  vi.mocked(useListUnresolvedValuesQuery).mockImplementation(((args: { outcome: string }) =>
    args.outcome === 'AMBIGUOUS'
      ? unresolved({ outcome: 'AMBIGUOUS', values: [{ namespace: 'SEVA_CODE', sourceValue: '999', affected: 4, lastSeenAt: null }] })
      : unresolved()) as never)
  const noopMutation = [vi.fn().mockReturnValue({ unwrap: vi.fn().mockResolvedValue({}) }), { isLoading: false }]
  vi.mocked(useCreateMappingRuleMutation).mockReturnValue(noopMutation as never)
  vi.mocked(useUpdateMappingRuleMutation).mockReturnValue(noopMutation as never)
  vi.mocked(useSetMappingRuleStatusMutation).mockReturnValue(noopMutation as never)
})

describe('SourceMapperPage — what it tells the user', () => {
  it('states that saving does not correct already-processed figures', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(await screen.findByText(/Mapping changes apply to future pipeline runs/i)).toBeInTheDocument()
    expect(
      screen.getByText(/does not automatically correct previously processed financial figures/i),
    ).toBeInTheDocument()
    expect(screen.getByText(/separate approved process/i)).toBeInTheDocument()
  })

  it('shows a stored namespaced value as its two halves, not as one opaque string', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(await screen.findByText('SEVA_CODE')).toBeInTheDocument()
    expect(screen.getByText('430')).toBeInTheDocument()
    expect(screen.queryByText('SEVA_CODE:430')).not.toBeInTheDocument()
  })

  it('flags a stored rule that names no field rather than hiding it', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(
      await screen.findByText(/Names no staged field — this rule can never match a record/i),
    ).toBeInTheDocument()
  })
})

describe('SourceMapperPage — metrics are honest', () => {
  it('names the batch the unresolved counts came from instead of implying a live figure', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(await screen.findByText(/Distinct values in batch 77/i)).toBeInTheDocument()
  })

  it('says "not measured" rather than zero when nothing has been mapped yet', async () => {
    vi.mocked(useListUnresolvedValuesQuery).mockReturnValue(
      unresolved({ syncBatchId: null, observedAt: null, values: [] }),
    )
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(await screen.findAllByText(/not measured/i)).not.toHaveLength(0)
  })

  it('takes the inactive total from the server, not from the rows currently on screen', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    // Two rules are rendered; the server reports four inactive. Counting the page would say "0".
    const card = (await screen.findByText((t) => t.trim() === 'Inactive rules')).closest('div')?.parentElement as HTMLElement
    expect(within(card).getByText('4')).toBeInTheDocument()
  })

  it('takes the active total from the server-side count, not the page', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    const card = (await screen.findByText((t) => t.trim() === 'Active rules')).closest('div')?.parentElement as HTMLElement
    expect(within(card).getByText('9')).toBeInTheDocument()
  })
})

describe('SourceMapperPage — loading, empty and error states', () => {
  it('renders a loading skeleton while the first page of rules is in flight', () => {
    vi.mocked(useListMappingRulesQuery).mockReturnValue(
      { data: undefined, isLoading: true, isFetching: true, isError: false, refetch: vi.fn() } as never,
    )
    const { container } = renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(container.querySelector('.animate-pulse')).toBeTruthy()
  })

  it('shows an error with a retry when the source systems cannot be loaded', async () => {
    const refetch = vi.fn()
    vi.mocked(useListSourceSystemsQuery).mockReturnValue(
      { data: undefined, isLoading: false, isFetching: false, isError: true,
        error: { status: 500, data: { message: 'NullPointerException at Foo.java:42' } }, refetch } as never,
    )
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    const retry = await screen.findByRole('button', { name: /try again/i })
    expect(retry).toBeInTheDocument()
    // A 500's server text is never put on screen.
    expect(screen.queryByText(/NullPointerException/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Foo\.java/)).not.toBeInTheDocument()

    await userEvent.click(retry)
    expect(refetch).toHaveBeenCalled()
  })

  it('explains an empty source list rather than showing a blank screen', async () => {
    vi.mocked(useListSourceSystemsQuery).mockReturnValue(wrap([]))
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(await screen.findByText(/No source systems available/i)).toBeInTheDocument()
  })

  it('surfaces a rule-list 403 in the user’s own words', async () => {
    vi.mocked(useListMappingRulesQuery).mockReturnValue(
      { data: undefined, isLoading: false, isFetching: false, isError: true,
        error: { status: 403, data: {} }, refetch: vi.fn() } as never,
    )
    renderWithProviders(<SourceMapperPage />, asRole('DC_STAFF'))

    expect(await screen.findByText(/do not have permission to change mapping rules/i)).toBeInTheDocument()
  })

  it('shows an empty-result message that distinguishes filtering from having no rules', async () => {
    vi.mocked(useListMappingRulesQuery).mockReturnValue(rulesPage([], 0))
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    expect(await screen.findByText(/No mapping rules match/i)).toBeInTheDocument()
  })
})

describe('SourceMapperPage — permissions are UX; the server decides', () => {
  it.each(['SUPER_ADMIN', 'DISTRICT_COLLECTOR'])('offers writing to %s', async (role) => {
    renderWithProviders(<SourceMapperPage />, asRole(role))

    expect(await screen.findByRole('button', { name: /new mapping/i })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^Edit mapping for/i }).length).toBeGreaterThan(0)
  })

  it.each(['DC_STAFF', 'AUDITOR'])('offers %s no write control anywhere on the page', async (role) => {
    renderWithProviders(<SourceMapperPage />, asRole(role))

    await screen.findByText('SEVA_CODE')
    expect(screen.queryByRole('button', { name: /new mapping/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit mapping for/i })).not.toBeInTheDocument()
    expect(screen.getByText(/restricted to District Collectors/i)).toBeInTheDocument()
  })

  it('offers no "create rule" action on unresolved values to a read-only role', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('AUDITOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByRole('tab', { name: /needs attention/i }))

    await screen.findAllByText(/Records affected/i)
    expect(screen.queryByRole('button', { name: /create rule/i })).not.toBeInTheDocument()
  })
})

describe('SourceMapperPage — filtering and sorting are server-driven', () => {
  it('passes the search term to the query rather than filtering the page in the browser', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.type(screen.getByLabelText(/search mapping rules/i), '431')

    await waitFor(() =>
      expect(vi.mocked(useListMappingRulesQuery)).toHaveBeenCalledWith(
        expect.objectContaining({ q: '431', page: 0 }),
        expect.anything(),
      ),
    )
  })

  it('only sorts by keys the backend allow-lists, and toggles direction', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByText('Revenue category'))

    await waitFor(() =>
      expect(vi.mocked(useListMappingRulesQuery)).toHaveBeenCalledWith(
        expect.objectContaining({ sort: 'canonicalValue,asc' }),
        expect.anything(),
      ),
    )

    const allowed = ['sourceValue', 'canonicalValue', 'mappingType', 'priority', 'active', 'updatedAt', 'createdAt']
    vi.mocked(useListMappingRulesQuery).mock.calls.forEach(([args]) => {
      const sort = (args as { sort?: string })?.sort
      if (sort) expect(allowed).toContain(sort.split(',')[0])
    })
  })

  it('asks the server for the next page instead of slicing a full result set', async () => {
    vi.mocked(useListMappingRulesQuery).mockImplementation(((args: { size?: number; active?: boolean }) =>
      args?.size === 1 && args?.active === false
        ? rulesPage([], 4)
        : wrap({ content: [rule()], page: 0, size: 20, totalElements: 45, totalPages: 3, last: false })) as never)

    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() =>
      expect(vi.mocked(useListMappingRulesQuery)).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1, size: 20 }),
        expect.anything(),
      ),
    )
  })

  it('resets to the first page when a filter changes', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.type(screen.getByLabelText(/search mapping rules/i), 'x')

    await waitFor(() =>
      expect(vi.mocked(useListMappingRulesQuery)).toHaveBeenCalledWith(
        expect.objectContaining({ q: 'x', page: 0 }),
        expect.anything(),
      ),
    )
  })
})

describe('SourceMapperPage — unresolved values', () => {
  it('lists unmapped values worst first, with the field a new rule must key on', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByRole('tab', { name: /needs attention/i }))

    expect(await screen.findAllByText(/Records affected/i)).not.toHaveLength(0)
    expect(screen.getAllByText('120').length).toBeGreaterThan(0)
    expect(screen.getAllByText('SANNIDHI').length).toBeGreaterThan(0)
  })

  it('switches outcome, so ambiguous values can be reviewed too', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByRole('tab', { name: /needs attention/i }))
    await userEvent.click(await screen.findByLabelText(/outcome/i))
    await userEvent.click(await screen.findByRole('option', { name: /Ambiguous/i }))

    await waitFor(() =>
      expect(vi.mocked(useListUnresolvedValuesQuery)).toHaveBeenCalledWith(
        expect.objectContaining({ outcome: 'AMBIGUOUS' }),
        expect.anything(),
      ),
    )
  })

  it('distinguishes "not measured" from "nothing unresolved"', async () => {
    vi.mocked(useListUnresolvedValuesQuery).mockReturnValue(
      unresolved({ syncBatchId: null, observedAt: null, values: [] }),
    )
    renderWithProviders(<SourceMapperPage />, asRole('DISTRICT_COLLECTOR'))

    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByRole('tab', { name: /needs attention/i }))

    expect(await screen.findByText(/Nothing has been mapped for this source yet/i)).toBeInTheDocument()
    expect(screen.getByText(/not the same as having no unresolved values/i)).toBeInTheDocument()
  })
})

describe('SourceMapperPage — financial safety', () => {
  it('offers no control that claims to fix or re-run historical data', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('SUPER_ADMIN'))

    await screen.findByText('SEVA_CODE')
    expect(screen.queryByRole('button', { name: /re-?run/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /fix historical/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /recalculat/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reprocess/i })).not.toBeInTheDocument()
  })

  it('offers no structural source-to-staging mapping', async () => {
    renderWithProviders(<SourceMapperPage />, asRole('SUPER_ADMIN'))

    await screen.findByText('SEVA_CODE')
    expect(screen.queryByText(/source table/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/source column/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/sql/i)).not.toBeInTheDocument()
  })

  it('never calls a mutation merely from viewing the page', async () => {
    const create = vi.fn().mockReturnValue({ unwrap: vi.fn() })
    const update = vi.fn().mockReturnValue({ unwrap: vi.fn() })
    const status = vi.fn().mockReturnValue({ unwrap: vi.fn() })
    vi.mocked(useCreateMappingRuleMutation).mockReturnValue([create, { isLoading: false }] as never)
    vi.mocked(useUpdateMappingRuleMutation).mockReturnValue([update, { isLoading: false }] as never)
    vi.mocked(useSetMappingRuleStatusMutation).mockReturnValue([status, { isLoading: false }] as never)

    renderWithProviders(<SourceMapperPage />, asRole('SUPER_ADMIN'))
    await screen.findByText('SEVA_CODE')
    await userEvent.click(screen.getByRole('tab', { name: /needs attention/i }))
    await screen.findAllByText(/Records affected/i)

    expect(create).not.toHaveBeenCalled()
    expect(update).not.toHaveBeenCalled()
    expect(status).not.toHaveBeenCalled()
  })
})

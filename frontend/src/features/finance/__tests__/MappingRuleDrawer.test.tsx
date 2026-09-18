import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { installRadixJsdomPolyfills } from './radixJsdomPolyfills'
import { MappingRuleDrawer } from '../components/MappingRuleDrawer'
import type { MappingRule } from '../financeTypes'

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

import { toast } from 'sonner'

vi.mock('../financeApi', () => ({
  financeApi: {
    reducerPath: 'financeApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useListCanonicalValuesQuery: vi.fn(),
  useGetNamespacesQuery: vi.fn(),
  useCreateMappingRuleMutation: vi.fn(),
  useUpdateMappingRuleMutation: vi.fn(),
}))

import {
  useCreateMappingRuleMutation,
  useGetNamespacesQuery,
  useListCanonicalValuesQuery,
  useUpdateMappingRuleMutation,
} from '../financeApi'

const wrap = <T,>(data: T) =>
  ({ data: { success: true, message: 'OK', data }, isLoading: false, isFetching: false, refetch: vi.fn() }) as never

const rule = (over: Partial<MappingRule> = {}): MappingRule => ({
  id: 1, sourceSystemId: 501, templeId: 300001, mappingType: 'REVENUE_CATEGORY',
  namespace: 'SEVA_CODE', sourceValue: '430', storedValue: 'SEVA_CODE:430', wellFormed: true,
  sourceLabel: 'Archana', canonicalValue: 'SEVA', canonicalValueKnown: true, priority: 100,
  active: true, notes: null, version: 3, createdBy: 2, createdAt: '2026-09-01T09:00:00',
  updatedBy: 2, updatedAt: '2026-09-10T09:00:00', ...over,
})

const HISTORICAL_EFFECT =
  'This changes how future pipeline runs classify this value. Figures already published keep their current classification until the batch that produced them is re-run. Saving a rule does not re-process or correct historical data.'

let createFn: ReturnType<typeof vi.fn>
let updateFn: ReturnType<typeof vi.fn>

const succeedsWith = (warnings: string[] = []) =>
  vi.fn().mockReturnValue({
    unwrap: vi.fn().mockResolvedValue({
      success: true, message: 'OK',
      data: { rule: rule(), historicalEffect: HISTORICAL_EFFECT, warnings },
    }),
  })

const failsWith = (status: number, data: Record<string, unknown>) =>
  vi.fn().mockReturnValue({ unwrap: vi.fn().mockRejectedValue({ status, data }) })

beforeEach(() => {
  installRadixJsdomPolyfills()
  vi.clearAllMocks()
  createFn = succeedsWith()
  updateFn = succeedsWith()
  vi.mocked(useListCanonicalValuesQuery).mockReturnValue(
    wrap([
      { categoryCode: 'SEVA', categoryName: 'Seva', description: null, displayOrder: 10 },
      { categoryCode: 'DONATION', categoryName: 'Donation', description: null, displayOrder: 20 },
    ]),
  )
  vi.mocked(useGetNamespacesQuery).mockReturnValue(
    wrap({ sourceSystemId: 501, observed: ['SEVA_CODE', 'SANNIDHI'], inUseByRules: ['SEVA_CODE'], sampledRows: 200 }),
  )
  vi.mocked(useCreateMappingRuleMutation).mockImplementation((() => [createFn, { isLoading: false }]) as never)
  vi.mocked(useUpdateMappingRuleMutation).mockImplementation((() => [updateFn, { isLoading: false }]) as never)
})

const props = (over: Record<string, unknown> = {}) => ({
  open: true,
  onOpenChange: vi.fn(),
  sourceSystemId: 501,
  ...over,
})

const fillValidCreate = async () => {
  await userEvent.type(await screen.findByLabelText(/staged field/i), 'SEVA_CODE')
  await userEvent.type(screen.getByLabelText(/^source value$/i), '431')
  await userEvent.click(screen.getByLabelText('Revenue category'))
  await userEvent.click(await screen.findByRole('option', { name: /Seva \(SEVA\)/i }))
}

describe('MappingRuleDrawer — validation before anything is sent', () => {
  it('refuses to submit without the required fields', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await userEvent.click(await screen.findByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/names the staged field the rule reads/i)).toBeInTheDocument()
    expect(createFn).not.toHaveBeenCalled()
  })

  it('rejects a field name containing a colon, because the colon is the separator', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await userEvent.type(await screen.findByLabelText(/staged field/i), 'SEVA:CODE')
    await userEvent.type(screen.getByLabelText(/^source value$/i), '430')
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/cannot contain a colon/i)).toBeInTheDocument()
    expect(createFn).not.toHaveBeenCalled()
  })

  it('requires a canonical value to be chosen rather than defaulting to one', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await userEvent.type(await screen.findByLabelText(/staged field/i), 'SEVA_CODE')
    await userEvent.type(screen.getByLabelText(/^source value$/i), '430')
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/Select a revenue category/i)).toBeInTheDocument()
    expect(createFn).not.toHaveBeenCalled()
  })
})

describe('MappingRuleDrawer — the namespace footgun', () => {
  it('warns, but does not block, when the field name is not one the source has emitted', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await userEvent.type(await screen.findByLabelText(/staged field/i), 'SEVACODE')

    expect(
      await screen.findByText(/will not match anything until the source emits that field/i),
    ).toBeInTheDocument()
    // Not blocked: refusing would make a source unconfigurable before its first extraction.
    expect(screen.getByRole('button', { name: /create mapping/i })).toBeEnabled()
  })

  it('does not warn about a field name the source does emit', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await userEvent.type(await screen.findByLabelText(/staged field/i), 'SEVA_CODE')

    expect(screen.queryByText(/will not match anything/i)).not.toBeInTheDocument()
  })

  it('says field names cannot be confirmed when nothing has been staged', async () => {
    vi.mocked(useGetNamespacesQuery).mockReturnValue(
      wrap({ sourceSystemId: 501, observed: [], inUseByRules: [], sampledRows: 0 }),
    )
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    expect(await screen.findByText(/cannot be confirmed either way/i)).toBeInTheDocument()
  })
})

describe('MappingRuleDrawer — saving', () => {
  it('sends exactly what was typed and reports that records were not changed', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    await waitFor(() => expect(createFn).toHaveBeenCalled())
    expect(createFn).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceSystemId: 501,
        mappingType: 'REVENUE_CATEGORY',
        namespace: 'SEVA_CODE',
        sourceValue: '431',
        canonicalValue: 'SEVA',
      }),
    )
    await waitFor(() =>
      expect(vi.mocked(toast.success)).toHaveBeenCalledWith(
        expect.stringContaining('Existing financial records were not changed'),
        expect.objectContaining({ description: HISTORICAL_EFFECT }),
      ),
    )
    expect(p.onOpenChange).toHaveBeenCalledWith(false)
  })

  it('always sends REVENUE_CATEGORY — the only type the pipeline reads', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    await waitFor(() => expect(createFn).toHaveBeenCalled())
    expect(createFn.mock.calls[0][0]).toMatchObject({ mappingType: 'REVENUE_CATEGORY' })
    // The field is present but not editable, so a user cannot configure something inert.
    expect(screen.getByDisplayValue('Revenue category')).toBeDisabled()
  })

  it('surfaces a backend warning after a successful save', async () => {
    createFn = succeedsWith(['No staged record carries a field called [SEVACODE].'])
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    await waitFor(() =>
      expect(vi.mocked(toast.warning)).toHaveBeenCalledWith(
        'Saved, but check this',
        expect.objectContaining({ description: expect.stringContaining('SEVACODE') }),
      ),
    )
  })

  it('sends the loaded version on an edit so a concurrent change can be detected', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'edit', rule: rule({ version: 7 }) }} />)

    await screen.findByDisplayValue('SEVA_CODE')
    await userEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(updateFn).toHaveBeenCalled())
    expect(updateFn).toHaveBeenCalledWith(
      expect.objectContaining({ id: 1, body: expect.objectContaining({ version: 7 }) }),
    )
  })

  it('prefills an edit from the rule and never sends an immutable identifier', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'edit', rule: rule() }} />)

    expect(await screen.findByDisplayValue('SEVA_CODE')).toBeInTheDocument()
    expect(screen.getByDisplayValue('430')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(updateFn).toHaveBeenCalled())
    const body = updateFn.mock.calls[0][0].body
    expect(body).not.toHaveProperty('sourceSystemId')
    expect(body).not.toHaveProperty('mappingType')
    expect(body).not.toHaveProperty('id')
  })
})

describe('MappingRuleDrawer — failures keep the user’s work', () => {
  it('keeps the drawer open and the typed values when the server refuses', async () => {
    createFn = failsWith(422, { message: '[NOPE] is not an active revenue category.' })
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/is not an active revenue category/i)).toBeInTheDocument()
    expect(p.onOpenChange).not.toHaveBeenCalledWith(false)
    expect(screen.getByLabelText(/staged field/i)).toHaveValue('SEVA_CODE')
    expect(screen.getByLabelText(/^source value$/i)).toHaveValue('431')
  })

  it('names the existing rule when the source value is already mapped', async () => {
    createFn = failsWith(409, {
      message: 'Rule 12 already maps [SEVA_CODE:430] to [SEVA] for this source system.',
    })
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/Rule 12 already maps/i)).toBeInTheDocument()
  })

  it('blocks resubmission on an optimistic-lock conflict rather than overwriting', async () => {
    updateFn = failsWith(409, { message: 'stale', errorCode: 'OPTIMISTIC_LOCK_CONFLICT' })
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'edit', rule: rule() }} />)

    await screen.findByDisplayValue('SEVA_CODE')
    await userEvent.click(screen.getByRole('button', { name: /save changes/i }))

    expect((await screen.findAllByText(/changed by another user/i)).length).toBeGreaterThan(0)
    expect(screen.getByText(/Refresh the record/i)).toBeInTheDocument()

    // The button is disabled afterwards, so a second click cannot clobber the newer version.
    await waitFor(() => expect(screen.getByRole('button', { name: /save changes/i })).toBeDisabled())
    await userEvent.click(screen.getByRole('button', { name: /save changes/i }))
    expect(updateFn).toHaveBeenCalledTimes(1)
  })

  it('attaches 400 field errors to the fields they belong to', async () => {
    createFn = failsWith(400, {
      message: 'Request validation failed.',
      errorCode: 'VALIDATION_ERROR',
      errors: ['priority must be between 0 and 1000'],
    })
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/priority must be between 0 and 1000/i)).toBeInTheDocument()
  })

  it('never shows a 500’s server text', async () => {
    createFn = failsWith(500, { message: 'NullPointerException at Foo.java:42' })
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/Something went wrong/i)).toBeInTheDocument()
    expect(screen.queryByText(/NullPointerException/)).not.toBeInTheDocument()
  })

  it('reports an unreachable server without claiming anything was saved', async () => {
    createFn = vi.fn().mockReturnValue({
      unwrap: vi.fn().mockRejectedValue({ status: 'FETCH_ERROR', error: 'Failed to fetch' }),
    })
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    await fillValidCreate()
    await userEvent.click(screen.getByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/could not be reached/i)).toBeInTheDocument()
    expect(screen.getByText(/nothing was saved/i)).toBeInTheDocument()
  })

  it('disables the submit button while a save is in flight', async () => {
    vi.mocked(useCreateMappingRuleMutation).mockImplementation((() => [createFn, { isLoading: true }]) as never)
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'create' }} />)

    expect(await screen.findByRole('button', { name: /saving/i })).toBeDisabled()
  })
})

describe('MappingRuleDrawer — create from an unresolved value', () => {
  it('carries the observed value through exactly, without trimming it', async () => {
    const p = props()
    renderWithProviders(
      <MappingRuleDrawer {...p} intent={{ mode: 'create', namespace: 'SEVA_CODE', sourceValue: '  431 ' }} />,
    )

    // Matching is exact on the server, so silently trimming here would create a rule for a
    // different value than the one the operator saw. toHaveValue is used rather than
    // findByDisplayValue because the latter normalises whitespace, which is the very thing
    // being asserted about.
    await waitFor(() =>
      expect(screen.getByLabelText(/^source value$/i)).toHaveValue('  431 '),
    )
    expect(screen.getByLabelText(/staged field/i)).toHaveValue('SEVA_CODE')
  })

  it('still requires a canonical value to be chosen', async () => {
    const p = props()
    renderWithProviders(
      <MappingRuleDrawer {...p} intent={{ mode: 'create', namespace: 'SEVA_CODE', sourceValue: '431' }} />,
    )

    await userEvent.click(await screen.findByRole('button', { name: /create mapping/i }))

    expect(await screen.findByText(/Select a revenue category/i)).toBeInTheDocument()
    expect(createFn).not.toHaveBeenCalled()
  })

  it('shows the future-runs-only notice on every write path', async () => {
    const p = props()
    renderWithProviders(<MappingRuleDrawer {...p} intent={{ mode: 'edit', rule: rule() }} />)

    expect(await screen.findByText(/This applies to future runs only/i)).toBeInTheDocument()
    expect(screen.getByText(/Nothing here corrects historical financial data/i)).toBeInTheDocument()
  })
})

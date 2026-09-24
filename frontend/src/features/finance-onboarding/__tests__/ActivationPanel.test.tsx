import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { ActivationPanel } from '../components/ActivationPanel'
import type { SourceSystemActivation, SourceSystemReadiness } from '../financeOnboardingTypes'

vi.mock('../financeOnboardingApi', () => ({
  financeOnboardingApi: {
    reducerPath: 'financeOnboardingApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
    util: { resetApiState: () => ({ type: 'noop' }) },
  },
  useSetSourceSystemActivationMutation: vi.fn(),
}))

import { useSetSourceSystemActivationMutation } from '../financeOnboardingApi'

const READY: SourceSystemReadiness = {
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
  evaluatedAt: '2026-09-24T10:00:00',
}

const BLOCKED: SourceSystemReadiness = {
  ...READY,
  status: 'BLOCKED',
  activationAllowed: false,
  blockingCount: 2,
  findings: [
    {
      code: 'NO_CAPABILITY_DECLARED',
      severity: 'BLOCKED',
      subject: null,
      message: 'No capability has been declared.',
    },
  ],
}

const RESULT: SourceSystemActivation = {
  sourceSystemId: 7,
  templeId: 300042,
  templeName: 'Sri Ranganatha, Mandya',
  systemCode: 'SRT_MANDYA',
  enabledForSync: true,
  changed: true,
  readinessStatus: 'READY',
  blockingCount: 0,
  warningCount: 0,
  syncInfrastructureAvailable: false,
  connectorDeploymentVerified: false,
  activationNote: 'Enabled for future synchronisation.',
  warnings: [],
  evaluatedAt: '2026-09-24T10:00:00',
}

function mockMutation(impl?: () => unknown) {
  const trigger = vi.fn(() => ({
    unwrap: impl ?? (() => Promise.resolve({ success: true, data: RESULT })),
  }))
  vi.mocked(useSetSourceSystemActivationMutation).mockReturnValue([
    trigger,
    { isLoading: false },
  ] as unknown as ReturnType<typeof useSetSourceSystemActivationMutation>)
  return trigger
}

describe('ActivationPanel (FIN-140-D)', () => {
  it('shows "Not enabled" and offers to enable when readiness is clean', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={READY} />,
    )

    // The badge and the alert heading both say it — deliberately, since the badge alone is easy
    // to skim past.
    expect(screen.getAllByText('Not enabled').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /enable for sync/i })).toBeEnabled()
  })

  /** The whole point of the slice: "enabled" must never be read as "running". */
  it('says plainly that enabling performs no connection and starts no sync', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={true} readiness={READY} />,
    )

    expect(screen.getByText('Enabled for sync')).toBeInTheDocument()
    expect(screen.getByText(/no source connection is performed/i)).toBeInTheDocument()
    expect(screen.getByText(/no data is being synchronised/i)).toBeInTheDocument()
  })

  it('never claims the source is syncing, connected or last synced', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={true} readiness={READY} />,
    )

    const rendered = document.body.textContent ?? ''
    expect(rendered).not.toMatch(/syncing/i)
    expect(rendered).not.toMatch(/last sync/i)
    expect(rendered).not.toMatch(/go live/i)
    expect(rendered).not.toMatch(/\bconnected\b/i)
  })

  it('disables the button and explains why while readiness is blocked', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={BLOCKED} />,
    )

    expect(screen.getByRole('button', { name: /enable for sync/i })).toBeDisabled()
    expect(screen.getByText(/2 readiness checks are blocking/i)).toBeInTheDocument()
  })

  it('does not offer to enable before readiness has loaded', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={undefined} />,
    )

    expect(screen.getByRole('button', { name: /enable for sync/i })).toBeDisabled()
  })

  it('sends the desired end state, with no version and no reason, when enabling', async () => {
    const trigger = mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={READY} />,
    )

    await userEvent.click(screen.getByRole('button', { name: /enable for sync/i }))

    expect(trigger).toHaveBeenCalledWith({ sourceSystemId: 7, body: { enabled: true } })
  })

  it('requires a reason before it will disable, and sends it', async () => {
    const trigger = mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={true} readiness={READY} />,
    )

    await userEvent.click(screen.getByRole('button', { name: /^disable$/i }))
    expect(screen.getByRole('button', { name: /confirm disable/i })).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/why is this being disabled/i), 'Migrating.')
    expect(screen.getByRole('button', { name: /confirm disable/i })).toBeEnabled()

    await userEvent.click(screen.getByRole('button', { name: /confirm disable/i }))
    expect(trigger).toHaveBeenCalledWith({
      sourceSystemId: 7,
      body: { enabled: false, reason: 'Migrating.' },
    })
  })

  it('says disabling deletes nothing', async () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={true} readiness={READY} />,
    )

    await userEvent.click(screen.getByRole('button', { name: /^disable$/i }))

    expect(screen.getByText(/nothing is deleted/i)).toBeInTheDocument()
  })

  it('shows the server refusal and states that nothing was changed', async () => {
    mockMutation(() =>
      Promise.reject({ status: 422, data: { message: 'Blocking: NO_CAPABILITY_DECLARED.' } }),
    )
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={READY} />,
    )

    await userEvent.click(screen.getByRole('button', { name: /enable for sync/i }))

    expect(await screen.findByText(/Nothing was changed/i)).toBeInTheDocument()
    expect(screen.getByText(/NO_CAPABILITY_DECLARED/)).toBeInTheDocument()
  })

  /**
   * A stale clean verdict on this screen must not become an activation the backend would refuse.
   * The button is enabled, the request goes, and the server's refusal is what the user sees.
   */
  it('defers to the backend when this screen’s readiness is stale', async () => {
    const trigger = mockMutation(() =>
      Promise.reject({ status: 422, data: { message: 'Configuration changed since you looked.' } }),
    )
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={READY} />,
    )

    await userEvent.click(screen.getByRole('button', { name: /enable for sync/i }))

    expect(trigger).toHaveBeenCalled()
    expect(await screen.findByText(/Configuration changed since you looked/)).toBeInTheDocument()
  })

  it('prevents a duplicate submission while the request is in flight', () => {
    const trigger = vi.fn()
    vi.mocked(useSetSourceSystemActivationMutation).mockReturnValue([
      trigger,
      { isLoading: true },
    ] as unknown as ReturnType<typeof useSetSourceSystemActivationMutation>)

    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={false} readiness={READY} />,
    )

    expect(screen.getByRole('button', { name: /enable for sync/i })).toBeDisabled()
  })

  it('exposes no credential, connector bean or database name', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={true} readiness={READY} />,
    )

    const rendered = document.body.textContent ?? ''
    expect(rendered).not.toMatch(/credential/i)
    expect(rendered).not.toMatch(/KOLSOHAM/i)
    expect(rendered).not.toMatch(/templeBFinanceConnector/)
  })

  it('offers no test-connection or run-now control', () => {
    mockMutation()
    renderWithProviders(
      <ActivationPanel sourceSystemId={7} enabledForSync={true} readiness={READY} />,
    )

    expect(
      screen.queryByRole('button', { name: /test connection|probe|sync now|run now/i }),
    ).not.toBeInTheDocument()
  })
})

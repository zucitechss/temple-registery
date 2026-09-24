import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { ReadinessPanel } from '../components/ReadinessPanel'
import type { SourceSystemReadiness } from '../financeOnboardingTypes'

const NOTE =
  'These checks read platform configuration only. Nothing here has contacted the source system, '
  + 'resolved a credential or confirmed that the named connector is deployed.'

function readiness(overrides: Partial<SourceSystemReadiness> = {}): SourceSystemReadiness {
  return {
    sourceSystemId: 1,
    templeId: 300001,
    templeName: 'Test Temple',
    systemCode: 'TESTSRC',
    status: 'READY',
    activationAllowed: true,
    syncEnabled: false,
    connectivityVerified: false,
    connectivityNote: NOTE,
    blockingCount: 0,
    warningCount: 0,
    findings: [],
    evaluatedAt: '2026-09-23T10:00:00',
    ...overrides,
  }
}

describe('ReadinessPanel (FIN-140 slice 140-A)', () => {
  it('states what was not checked even when everything passed', () => {
    renderWithProviders(<ReadinessPanel readiness={readiness()} isLoading={false} isError={false} />)

    expect(screen.getByText(/Configuration complete/i)).toBeInTheDocument()
    expect(screen.getByText(NOTE)).toBeInTheDocument()
    expect(screen.getByText(/What has not been checked/i)).toBeInTheDocument()
  })

  it('never claims a clean configuration means the source is connected', () => {
    renderWithProviders(<ReadinessPanel readiness={readiness()} isLoading={false} isError={false} />)

    expect(screen.queryByText(/\bconnected\b/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/connection (successful|ok|verified)/i)).not.toBeInTheDocument()
  })

  it('shows each blocking finding with the backend sentence verbatim', () => {
    const message =
      'Revenue is reportable for this source but no declaration in force names the field carrying '
      + 'REVENUE_AMOUNT. Normalization refuses a batch without it, so every extracted row would be '
      + 'rejected.'

    renderWithProviders(
      <ReadinessPanel
        isLoading={false}
        isError={false}
        readiness={readiness({
          status: 'BLOCKED',
          activationAllowed: false,
          blockingCount: 1,
          findings: [
            {
              code: 'SOURCE_OF_TRUTH_MISSING',
              severity: 'BLOCKED',
              subject: 'REVENUE_AMOUNT',
              message,
            },
          ],
        })}
      />,
    )

    expect(screen.getByText(/Configuration incomplete/i)).toBeInTheDocument()
    expect(screen.getByText(message)).toBeInTheDocument()
    expect(screen.getByText('SOURCE_OF_TRUTH_MISSING')).toBeInTheDocument()
  })

  it('separates warnings from blockers so a warning does not read as a refusal', () => {
    renderWithProviders(
      <ReadinessPanel
        isLoading={false}
        isError={false}
        readiness={readiness({
          status: 'WARNING',
          activationAllowed: true,
          warningCount: 1,
          findings: [
            {
              code: 'CREDENTIAL_REF_MISSING',
              severity: 'WARNING',
              subject: null,
              message: 'No credential alias is set.',
            },
          ],
        })}
      />,
    )

    expect(screen.getByText(/Configuration complete, with notes/i)).toBeInTheDocument()
    expect(screen.getByText(/Worth checking/i)).toBeInTheDocument()
    expect(screen.queryByText(/Must be resolved/i)).not.toBeInTheDocument()
  })

  it('shows a skeleton while loading rather than an empty verdict', () => {
    const { container } = renderWithProviders(
      <ReadinessPanel isLoading isError={false} />,
    )

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
    expect(screen.queryByText(/Configuration complete/i)).not.toBeInTheDocument()
  })

  it('shows an error rather than a blank panel when the check could not run', () => {
    renderWithProviders(
      <ReadinessPanel isLoading={false} isError errorMessage="Server unavailable." />,
    )

    expect(screen.getByText(/could not be checked/i)).toBeInTheDocument()
    expect(screen.getByText('Server unavailable.')).toBeInTheDocument()
  })
})

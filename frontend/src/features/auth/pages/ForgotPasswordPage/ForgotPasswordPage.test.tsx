import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { ForgotPasswordPage } from './ForgotPasswordPage'

const requestReset = vi.fn()

vi.mock('../../authApi', async () => {
  const actual = await vi.importActual<typeof import('../../authApi')>('../../authApi')
  return {
    ...actual,
    usePasswordResetRequestMutation: () => [requestReset, { isLoading: false }],
  }
})

describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    requestReset.mockReturnValue({ unwrap: () => Promise.resolve({ success: true }) })
  })

  it('should_renderEmailFieldAndSubmit', () => {
    renderWithProviders(<ForgotPasswordPage />)
    expect(screen.getByPlaceholderText(/enter your registered email/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /send reset link/i })).toBeInTheDocument()
  })

  it('should_linkBackToLogin', () => {
    renderWithProviders(<ForgotPasswordPage />)
    expect(screen.getByRole('link', { name: /back to sign in/i })).toHaveAttribute('href', '/login')
  })

  it('should_blockSubmit_when_emailIsInvalid', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage />)

    await user.type(screen.getByPlaceholderText(/enter your registered email/i), 'not-an-email')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    expect(await screen.findByText(/invalid email address/i)).toBeInTheDocument()
    expect(requestReset).not.toHaveBeenCalled()
  })

  it('should_callApi_when_emailIsValid', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage />)

    await user.type(screen.getByPlaceholderText(/enter your registered email/i), 'dc@example.com')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    await waitFor(() => expect(requestReset).toHaveBeenCalledWith({ email: 'dc@example.com' }))
  })

  it('should_showGenericConfirmation_when_requestSucceeds', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ForgotPasswordPage />)

    await user.type(screen.getByPlaceholderText(/enter your registered email/i), 'dc@example.com')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    // Wording must not confirm or deny that the account exists.
    const confirmation = await screen.findByText(/if an account exists for the provided information/i)
    expect(confirmation).toBeInTheDocument()
    expect(screen.queryByText(/no account|not found|does not exist/i)).not.toBeInTheDocument()
  })

  it('should_showTheSameConfirmation_when_emailIsNotRegistered', async () => {
    const user = userEvent.setup()
    // The backend returns 200 for unknown addresses too — the UI must not differ.
    requestReset.mockReturnValue({ unwrap: () => Promise.resolve({ success: true }) })
    renderWithProviders(<ForgotPasswordPage />)

    await user.type(screen.getByPlaceholderText(/enter your registered email/i), 'nobody@example.com')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    expect(
      await screen.findByText(/if an account exists for the provided information/i),
    ).toBeInTheDocument()
  })

  it('should_stayOnForm_when_requestIsRateLimited', async () => {
    const user = userEvent.setup()
    requestReset.mockReturnValue({
      unwrap: () => Promise.reject({ data: { message: 'Rate limit exceeded.' } }),
    })
    renderWithProviders(<ForgotPasswordPage />)

    await user.type(screen.getByPlaceholderText(/enter your registered email/i), 'spam@example.com')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    await waitFor(() => expect(requestReset).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /send reset link/i })).toBeInTheDocument()
  })
})

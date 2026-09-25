import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { ResetPasswordPage } from './ResetPasswordPage'

const confirmReset = vi.fn()
const navigate = vi.fn()

vi.mock('../../authApi', async () => {
  const actual = await vi.importActual<typeof import('../../authApi')>('../../authApi')
  return {
    ...actual,
    usePasswordResetConfirmMutation: () => [confirmReset, { isLoading: false }],
  }
})

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const TOKEN_ROUTE = '/reset-password?token=raw-token-abc'

function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, next: string, confirm: string) {
  return (async () => {
    await user.type(screen.getByPlaceholderText(/enter a new password/i), next)
    await user.type(screen.getByPlaceholderText(/re-enter the new password/i), confirm)
    await user.click(screen.getByRole('button', { name: /^reset password$/i }))
  })()
}

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmReset.mockReturnValue({ unwrap: () => Promise.resolve({ success: true }) })
  })

  it('should_renderForm_when_tokenIsPresent', () => {
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })
    expect(screen.getByPlaceholderText(/enter a new password/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/re-enter the new password/i)).toBeInTheDocument()
    expect(screen.getByText(/at least 8 characters/i)).toBeInTheDocument()
  })

  it('should_showInvalidLinkMessage_when_tokenIsMissing', () => {
    renderWithProviders(<ResetPasswordPage />, { initialRoute: '/reset-password' })
    expect(screen.getByText(/this password reset link is not valid/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /request a new link/i })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
    expect(screen.queryByPlaceholderText(/enter a new password/i)).not.toBeInTheDocument()
  })

  it('should_blockSubmit_when_passwordsDoNotMatch', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })

    await fillAndSubmit(user, 'NewPass456', 'Different789')

    expect(await screen.findByText(/passwords do not match/i)).toBeInTheDocument()
    expect(confirmReset).not.toHaveBeenCalled()
  })

  it('should_blockSubmit_when_passwordIsTooShort', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })

    await fillAndSubmit(user, 'short', 'short')

    await waitFor(() => expect(confirmReset).not.toHaveBeenCalled())
  })

  it('should_sendTokenFromTheUrl_when_submissionIsValid', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })

    await fillAndSubmit(user, 'NewPass456', 'NewPass456')

    await waitFor(() =>
      expect(confirmReset).toHaveBeenCalledWith({
        token: 'raw-token-abc',
        newPassword: 'NewPass456',
        confirmPassword: 'NewPass456',
      }),
    )
  })

  it('should_redirectToLogin_when_resetSucceeds', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })

    await fillAndSubmit(user, 'NewPass456', 'NewPass456')

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login'))
  })

  it('should_stayOnPage_when_tokenIsExpiredOrUsed', async () => {
    const user = userEvent.setup()
    confirmReset.mockReturnValue({
      unwrap: () => Promise.reject({ data: { message: 'This password reset link has expired.' } }),
    })
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })

    await fillAndSubmit(user, 'NewPass456', 'NewPass456')

    await waitFor(() => expect(confirmReset).toHaveBeenCalled())
    expect(navigate).not.toHaveBeenCalled()
  })

  it('should_toggleVisibility_when_eyeButtonClicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ResetPasswordPage />, { initialRoute: TOKEN_ROUTE })
    const input = screen.getByPlaceholderText(/enter a new password/i)
    expect(input).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: /show password/i }))
    expect(input).toHaveAttribute('type', 'text')
  })
})

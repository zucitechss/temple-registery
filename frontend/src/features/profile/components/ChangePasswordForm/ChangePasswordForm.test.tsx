import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { ChangePasswordForm } from './ChangePasswordForm'

const changePassword = vi.fn()
const handleLogout = vi.fn()

vi.mock('@/features/auth/authApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/auth/authApi')>(
    '@/features/auth/authApi',
  )
  return {
    ...actual,
    useChangePasswordMutation: () => [changePassword, { isLoading: false }],
  }
})

vi.mock('@/features/auth/authHooks', async () => {
  const actual = await vi.importActual<typeof import('@/features/auth/authHooks')>(
    '@/features/auth/authHooks',
  )
  return { ...actual, useLogout: () => ({ handleLogout }) }
})

function ok() {
  changePassword.mockReturnValue({
    unwrap: () => Promise.resolve({ success: true, message: 'Your password has been changed successfully.' }),
  })
}

async function fill(user: ReturnType<typeof userEvent.setup>, current: string, next: string, confirm: string) {
  await user.type(screen.getByLabelText(/^current password$/i), current)
  await user.type(screen.getByLabelText(/^new password$/i), next)
  await user.type(screen.getByLabelText(/^confirm new password$/i), confirm)
  await user.click(screen.getByRole('button', { name: /change password/i }))
}

describe('ChangePasswordForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ok()
  })

  it('should_renderAllThreeFields', () => {
    renderWithProviders(<ChangePasswordForm />)
    expect(screen.getByLabelText(/^current password$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^new password$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^confirm new password$/i)).toBeInTheDocument()
  })

  it('should_toggleVisibility_when_eyeButtonClicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChangePasswordForm />)
    const input = screen.getByLabelText(/^new password$/i)
    expect(input).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: /show new password/i }))
    expect(input).toHaveAttribute('type', 'text')
  })

  it('should_blockSubmit_when_confirmationDoesNotMatch', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChangePasswordForm />)

    await fill(user, 'OldPass123', 'NewPass456', 'Different789')

    expect(await screen.findByText(/do not match/i)).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('should_blockSubmit_when_newPasswordIsTooShort', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChangePasswordForm />)

    await fill(user, 'OldPass123', 'short', 'short')

    // Match the validation message specifically — the field's hint text also mentions 8 characters.
    expect(await screen.findByText(/password must be at least 8 characters/i)).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('should_blockSubmit_when_newPasswordMatchesCurrent', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChangePasswordForm />)

    await fill(user, 'SamePass123', 'SamePass123', 'SamePass123')

    expect(await screen.findByText(/different from the current password/i)).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('should_blockSubmit_when_fieldsAreEmpty', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ChangePasswordForm />)

    await user.click(screen.getByRole('button', { name: /change password/i }))

    await waitFor(() => expect(changePassword).not.toHaveBeenCalled())
  })

  it('should_callApiAndSignOut_when_submissionIsValid', async () => {
    const user = userEvent.setup()
    const onSuccess = vi.fn()
    renderWithProviders(<ChangePasswordForm onSuccess={onSuccess} />)

    await fill(user, 'OldPass123', 'NewPass456', 'NewPass456')

    await waitFor(() => {
      expect(changePassword).toHaveBeenCalledWith({
        currentPassword: 'OldPass123',
        newPassword: 'NewPass456',
        confirmPassword: 'NewPass456',
      })
    })
    await waitFor(() => expect(onSuccess).toHaveBeenCalled())
    // All refresh tokens are revoked server-side, so the session must be torn down.
    await waitFor(() => expect(handleLogout).toHaveBeenCalled())
  })

  it('should_showFieldError_when_currentPasswordIsRejectedByServer', async () => {
    const user = userEvent.setup()
    changePassword.mockReturnValue({
      unwrap: () => Promise.reject({ data: { message: 'Current password is incorrect.' } }),
    })
    renderWithProviders(<ChangePasswordForm />)

    await fill(user, 'WrongPass1', 'NewPass456', 'NewPass456')

    expect(await screen.findByText('Current password is incorrect.')).toBeInTheDocument()
    expect(handleLogout).not.toHaveBeenCalled()
  })
})

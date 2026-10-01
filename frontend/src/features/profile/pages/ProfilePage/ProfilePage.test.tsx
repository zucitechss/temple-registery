import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { USER_ROLES } from '@/constants/roles'
import type { CurrentUser } from '@/features/auth/authTypes'
import { ProfilePage } from './ProfilePage'

vi.mock('@/features/auth/authApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/auth/authApi')>(
    '@/features/auth/authApi',
  )
  return { ...actual, useGetCurrentUserQuery: vi.fn() }
})

import { useGetCurrentUserQuery } from '@/features/auth/authApi'

const DC_USER: CurrentUser = {
  userId: 12,
  username: 'dc_mysuru',
  email: 'dc.mysuru@example.com',
  fullName: 'Ramesh Kumar',
  mobile: '9876543210',
  role: USER_ROLES.DISTRICT_COLLECTOR,
  active: true,
  aadhaarVerified: false,
  districtId: 4,
  districtName: 'Mysuru',
  lastLoginAt: '2026-09-24T09:30:00',
  passwordUpdatedAt: '2026-08-01T10:00:00',
  mustChangePassword: false,
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function mockProfile(overrides: Partial<Record<string, any>> = {}, user: CurrentUser | null = DC_USER) {
  vi.mocked(useGetCurrentUserQuery).mockReturnValue({
    data: user ? { success: true, message: 'OK', data: user } : undefined,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
    ...overrides,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
}

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockProfile()
  })

  it('should_showLoadingState_when_profileIsLoading', () => {
    mockProfile({ isLoading: true, data: undefined })
    const { container } = renderWithProviders(<ProfilePage />)
    expect(container.querySelector('.animate-spin')).toBeInTheDocument()
  })

  it('should_showErrorState_when_profileRequestFails', () => {
    mockProfile({ isError: true, data: undefined }, null)
    renderWithProviders(<ProfilePage />)
    expect(screen.getByText(/could not load your profile/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  it('should_displayPersonalInformation_when_profileLoads', () => {
    renderWithProviders(<ProfilePage />)
    expect(screen.getAllByText('Ramesh Kumar').length).toBeGreaterThan(0)
    expect(screen.getAllByText('dc.mysuru@example.com').length).toBeGreaterThan(0)
    expect(screen.getByText('dc_mysuru')).toBeInTheDocument()
    expect(screen.getByText('9876543210')).toBeInTheDocument()
  })

  it('should_displayRoleAndAssignment_when_profileLoads', () => {
    renderWithProviders(<ProfilePage />)
    expect(screen.getAllByText('District Collector').length).toBeGreaterThan(0)
    expect(screen.getByText('Mysuru')).toBeInTheDocument()
  })

  it('should_showTempleAssignment_when_userIsTempleAuthority', () => {
    mockProfile({}, {
      ...DC_USER,
      role: USER_ROLES.TEMPLE_AUTHORITY,
      designation: 'Trust Secretary',
      templeId: 7,
      templeName: 'Chamundeshwari Temple',
      accessType: 'EDIT',
    })
    renderWithProviders(<ProfilePage />)
    expect(screen.getByText('Chamundeshwari Temple')).toBeInTheDocument()
    expect(screen.getByText('Trust Secretary')).toBeInTheDocument()
    expect(screen.getByText('Full access')).toBeInTheDocument()
  })

  it('should_neverRenderAPasswordOrToken_when_profileLoads', () => {
    const { container } = renderWithProviders(<ProfilePage />)
    // The masked placeholder is the only password-ish thing on the page.
    expect(screen.getByText('••••••••')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/passwordHash|accessToken|refreshToken/i)
  })

  it('should_showNeverChanged_when_passwordWasNeverUpdated', () => {
    mockProfile({}, { ...DC_USER, passwordUpdatedAt: undefined })
    renderWithProviders(<ProfilePage />)
    expect(screen.getByText('Never changed')).toBeInTheDocument()
  })

  it('should_openChangePasswordDialog_when_changePasswordClicked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ProfilePage />)

    await user.click(screen.getByRole('button', { name: /change password/i }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
    // Anchored so the show/hide toggles' aria-labels ("Show new password") don't also match.
    expect(screen.getByLabelText(/^current password$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^new password$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^confirm new password$/i)).toBeInTheDocument()
  })

  // ── Authorization: admin controls must not render for non-admins ───────────

  it('should_showAdministrativeActions_when_userIsSuperAdmin', () => {
    mockProfile({}, { ...DC_USER, role: USER_ROLES.SUPER_ADMIN })
    renderWithProviders(<ProfilePage />)
    expect(screen.getByText(/administrative actions/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /reset user password/i })).toBeInTheDocument()
  })

  it.each([
    USER_ROLES.DISTRICT_COLLECTOR,
    USER_ROLES.DC_STAFF,
    USER_ROLES.TEMPLE_AUTHORITY,
    USER_ROLES.AUDITOR,
    USER_ROLES.VIEWER,
  ])('should_hideAdministrativeActions_when_roleIs_%s', (role) => {
    mockProfile({}, { ...DC_USER, role })
    renderWithProviders(<ProfilePage />)
    expect(screen.queryByText(/administrative actions/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /reset user password/i })).not.toBeInTheDocument()
  })
})

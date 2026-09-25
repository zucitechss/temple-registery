import { screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { USER_ROLES } from '@/constants/roles'
import { TopBar } from './TopBar'

// The bell pulls its own data; the profile link is what this suite is about.
vi.mock('@/features/notification/components/NotificationBell', () => ({
  NotificationBell: () => <div data-testid="notification-bell" />,
}))

const preloadedState = {
  auth: {
    currentUser: {
      userId: 1,
      username: 'dc_mysuru',
      fullName: 'Ramesh Kumar',
      role: USER_ROLES.DISTRICT_COLLECTOR,
      aadhaarVerified: false,
    },
    isAuthenticated: true,
  },
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
} as any

describe('TopBar', () => {
  it('should_navigateToProfile_when_profileAvatarClicked', () => {
    renderWithProviders(<TopBar title="Dashboard" />, { preloadedState })

    const profileLink = screen.getByRole('link', { name: /view your profile/i })
    expect(profileLink).toHaveAttribute('href', '/profile')
  })

  it('should_stillRenderTheNotificationBell', () => {
    renderWithProviders(<TopBar title="Dashboard" />, { preloadedState })
    expect(screen.getByTestId('notification-bell')).toBeInTheDocument()
  })

  it('should_stillRenderUserNameAndRole', () => {
    renderWithProviders(<TopBar title="Dashboard" />, { preloadedState })
    expect(screen.getByText('Ramesh Kumar')).toBeInTheDocument()
    expect(screen.getByText('DISTRICT COLLECTOR')).toBeInTheDocument()
  })

  it('should_notRenderProfileLink_when_noUserIsSignedIn', () => {
    renderWithProviders(<TopBar title="Dashboard" />, {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      preloadedState: { auth: { currentUser: null, isAuthenticated: false } } as any,
    })
    expect(screen.queryByRole('link', { name: /view your profile/i })).not.toBeInTheDocument()
  })

  it('should_stillRenderTheMenuButton', () => {
    renderWithProviders(<TopBar title="Dashboard" onMenuClick={vi.fn()} />, { preloadedState })
    expect(screen.getByRole('button', { name: /open sidebar/i })).toBeInTheDocument()
  })
})

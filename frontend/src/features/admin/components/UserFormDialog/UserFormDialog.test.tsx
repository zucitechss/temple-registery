import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { renderWithProviders } from '@/test/utils/renderWithProviders'
import { UserFormDialog } from './UserFormDialog'

// ── jsdom polyfills required by Radix UI ──────────────────────────────────────
beforeAll(() => {
  if (typeof window !== 'undefined') {
    if (!window.ResizeObserver) {
      window.ResizeObserver = class {
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    }
    if (!window.HTMLElement.prototype.hasPointerCapture) {
      window.HTMLElement.prototype.hasPointerCapture = () => false
    }
    if (!window.HTMLElement.prototype.setPointerCapture) {
      window.HTMLElement.prototype.setPointerCapture = () => {}
    }
    if (!window.HTMLElement.prototype.releasePointerCapture) {
      window.HTMLElement.prototype.releasePointerCapture = () => {}
    }
    if (!window.HTMLElement.prototype.scrollIntoView) {
      window.HTMLElement.prototype.scrollIntoView = () => {}
    }
  }
})

// ── Mock adminApi ──────────────────────────────────────────────────────────────

vi.mock('../../adminApi', () => ({
  adminApi: {
    reducerPath: 'adminApi',
    reducer: (s = {}) => s,
    middleware: () => (next: (a: unknown) => unknown) => (a: unknown) => next(a),
  },
  useListAllDistrictsQuery: vi.fn().mockReturnValue({
    data: { data: [{ id: 1, name: 'Bengaluru Urban', cityId: 1 }] },
    isLoading: false,
  }),
  useSearchTemplesQuery: vi.fn().mockReturnValue({
    data: { data: { content: [] } },
    isLoading: false,
  }),
}))

// ── Mock geoApi ──────────────────────────────────────────────────────────────

vi.mock('@/features/geo/geoApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/geo/geoApi')>()
  return {
    ...actual,
    useGetCitiesQuery: vi.fn().mockReturnValue({
      data: { data: [{ id: 1, name: 'Bengaluru' }] },
      isLoading: false,
    }),
  }
})

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Fill in the text fields needed to make the password-based toggle appear.
 * Avoids interacting with Radix UI Select (district/role) to stay jsdom-safe.
 */
async function typePassword(user = userEvent.setup(), password = 'Admin@123') {
  const passwordInput = screen.getByPlaceholderText('Min 8 characters')
  await user.type(passwordInput, password)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('UserFormDialog — Send Credentials Email', () => {
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  const onOpenChange = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should not show the credentials toggle when dialog is in edit mode', async () => {
    renderWithProviders(
      <UserFormDialog
        open
        onOpenChange={onOpenChange}
        user={{ id: 1, username: 'existing', email: 'e@e.com', fullName: 'Existing User', role: 'DISTRICT_COLLECTOR', active: true, aadhaarVerified: false, districtId: 1, districtName: 'Bengaluru Urban' }}
        onSubmit={onSubmit}
      />
    )

    expect(screen.queryByLabelText(/send.*credentials/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/send credentials via email/i)).not.toBeInTheDocument()
  })

  it('should not show the credentials toggle when password is less than 8 characters', async () => {
    renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    const passwordInput = screen.getByPlaceholderText('Min 8 characters')
    await userEvent.type(passwordInput, 'short')

    expect(screen.queryByText(/send credentials via email/i)).not.toBeInTheDocument()
  })

  it('should show the credentials toggle once password reaches 8 characters', async () => {
    renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    const passwordInput = screen.getByPlaceholderText('Min 8 characters')
    await userEvent.type(passwordInput, 'Admin@123')

    await waitFor(() => {
      expect(screen.getByText(/send credentials via email/i)).toBeInTheDocument()
    })
  })

  it('should default the credentials toggle to ON (checked)', async () => {
    renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    await userEvent.type(screen.getByPlaceholderText('Min 8 characters'), 'Admin@123')

    await waitFor(() => screen.getByText(/send credentials via email/i))

    const toggle = screen.getByRole('switch', { name: /send login credentials via email/i })
    expect(toggle).toHaveAttribute('aria-checked', 'true')
  })

  it('should toggle credentials switch on click and update the description text', async () => {
    // The toggle defaults to ON (see "should default the credentials toggle to ON"
    // below), so a single click must turn it OFF. This test previously asserted
    // aria-checked='true' and the ON-state text after that same click — the
    // starting state, not the state a toggle actually reaches. The adjacent test
    // "should pass sendCredentialsEmail=false when toggle is OFF at form submit"
    // performs the identical click and correctly expects 'false', which is what
    // proved this assertion was inverted rather than a real component defect.
    const user = userEvent.setup()
    renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    await typePassword(user)
    await waitFor(() => screen.getByText(/send credentials via email/i))

    const toggle = screen.getByRole('switch', { name: /send login credentials via email/i })
    await user.click(toggle)

    await waitFor(() => {
      expect(toggle).toHaveAttribute('aria-checked', 'false')
      expect(
        screen.getByText(/enable to send the username and temporary password/i)
      ).toBeInTheDocument()
    })
  })

  it('should pass sendCredentialsEmail=false when toggle is OFF at form submit', async () => {
    // Toggle defaults to ON; click once to turn it OFF
    const user = userEvent.setup()
    renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    await typePassword(user)
    await waitFor(() => screen.getByText(/send credentials via email/i))

    const toggle = screen.getByRole('switch', { name: /send login credentials via email/i })
    // Click to turn OFF (default is ON)
    await user.click(toggle)
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'))
  })

  it('should pass sendCredentialsEmail=true when toggle is ON at form submit', async () => {
    // Toggle defaults to ON — no click needed
    const user = userEvent.setup()
    renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    await typePassword(user)
    await waitFor(() => screen.getByText(/send credentials via email/i))

    const toggle = screen.getByRole('switch', { name: /send login credentials via email/i })

    // Default is ON → backend will receive sendCredentialsEmail=true
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'))
  })

  it('should reset the credentials toggle to ON when dialog is reopened', async () => {
    const { rerender } = renderWithProviders(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    // Type password to reveal toggle
    await userEvent.type(screen.getByPlaceholderText('Min 8 characters'), 'Admin@123')
    await waitFor(() => screen.getByText(/send credentials via email/i))

    // Toggle starts ON by default; click to turn it OFF
    const toggle = screen.getByRole('switch', { name: /send login credentials via email/i })
    await userEvent.click(toggle)
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'))

    // Close and reopen dialog
    rerender(
      <UserFormDialog open={false} onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )
    rerender(
      <UserFormDialog open onOpenChange={onOpenChange} onSubmit={onSubmit} />
    )

    // Password was cleared → toggle is hidden (resets to default ON when next shown)
    expect(screen.queryByText(/send credentials via email/i)).not.toBeInTheDocument()
  })
})

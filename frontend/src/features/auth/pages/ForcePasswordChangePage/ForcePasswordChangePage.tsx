import { Navigate } from 'react-router-dom'
import { ShieldAlert } from 'lucide-react'
import { useAppSelector } from '@/app/store'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { getDashboardPath } from '../../authHooks'
import { AuthCardLayout } from '../../components/AuthCardLayout/AuthCardLayout'
import { ChangePasswordForm } from '@/features/profile/components/ChangePasswordForm/ChangePasswordForm'

/**
 * Shown when a user signs in with an admin-issued temporary password. The backend blocks every
 * other endpoint until the password is replaced, so there is nothing useful to navigate to —
 * this screen deliberately offers no way out other than completing the change or signing out.
 */
export function ForcePasswordChangePage() {
  const currentUser = useAppSelector((s) => s.auth.currentUser)

  if (!currentUser) {
    return <Navigate to={ROUTE_PATHS.LOGIN} replace />
  }

  // Already done (e.g. the user hit this URL directly) — send them to their dashboard.
  if (!currentUser.mustChangePassword) {
    return <Navigate to={getDashboardPath(currentUser.role)} replace />
  }

  return (
    <AuthCardLayout
      title="Change Your Password"
      subtitle="You are signed in with a temporary password"
    >
      <div className="mb-5 flex gap-3 rounded-md border border-amber-200 bg-amber-50 p-3">
        <ShieldAlert className="h-5 w-5 shrink-0 text-amber-600" />
        <p className="text-xs text-amber-900">
          For your security, you must choose a new password before you can continue using the
          portal. Enter the temporary password from your email as the current password.
        </p>
      </div>

      <ChangePasswordForm submitLabel="Set New Password" />
    </AuthCardLayout>
  )
}

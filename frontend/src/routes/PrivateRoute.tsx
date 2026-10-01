import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAppSelector } from '@/app/store'
import { useGetCurrentUserQuery } from '@/features/auth/authApi'
import { setCurrentUser } from '@/features/auth/authSlice'
import { useAppDispatch } from '@/app/store'
import { ROUTE_PATHS } from '@/constants/routePaths'
import { PermissionsProvider } from '@/features/access-control/context/PermissionsContext'

/**
 * Redirects unauthenticated users to /login.
 * Calls GET /auth/me on mount to hydrate the Redux auth slice from the httpOnly cookie.
 */
export function PrivateRoute() {
  const dispatch = useAppDispatch()
  const { pathname } = useLocation()
  const isAuthenticated = useAppSelector((s) => s.auth.isAuthenticated)
  // refetchOnMountOrArgChange ensures we always validate the cookie with the server
  const { data, isLoading } = useGetCurrentUserQuery(undefined, {
    refetchOnMountOrArgChange: true,
  })

  useEffect(() => {
    if (data?.data) {
      dispatch(setCurrentUser(data.data))
    }
  }, [data, dispatch])

  if (isLoading && !isAuthenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    )
  }

  if (!isAuthenticated && !data?.data) {
    return <Navigate to={ROUTE_PATHS.LOGIN} replace />
  }

  // Signed in with an admin-issued temporary password: nothing else is reachable until it is
  // replaced. The backend enforces the same rule, so this is purely for a sane UX.
  if (data?.data?.mustChangePassword && pathname !== ROUTE_PATHS.CHANGE_PASSWORD) {
    return <Navigate to={ROUTE_PATHS.CHANGE_PASSWORD} replace />
  }

  return <PermissionsProvider><Outlet /></PermissionsProvider>
}

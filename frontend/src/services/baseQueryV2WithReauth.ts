import { fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query'
import { clearCurrentUser } from '../features/auth/authSlice'
import { getApiRootBaseUrl } from '@/lib/apiBase'
import { withCsrfProtection } from './csrfBaseQuery'

const apiRootBaseUrl = getApiRootBaseUrl()

const rawV2BaseQuery = fetchBaseQuery({
  baseUrl: apiRootBaseUrl,
  credentials: 'include',
  // No Authorization header: JWT stored exclusively in an httpOnly cookie.
})

// Every unsafe method carries the CSRF token and retries once on rejection (H-5).
const v2BaseQuery = withCsrfProtection(rawV2BaseQuery)

export const baseQueryV2WithReauth: BaseQueryFn<
  string | FetchArgs,
  unknown,
  FetchBaseQueryError
> = async (args, api, extraOptions) => {
  let result = await v2BaseQuery(args, api, extraOptions)

  if (result.error?.status === 401) {
    const url = typeof args === 'string' ? args : args.url
    if (url.includes('/api/v1/auth/refresh') || url.includes('/api/v1/auth/me') || url.includes('/api/v1/auth/mfa-verify')) {
      return result
    }

    const refreshResult = await v2BaseQuery(
      { url: '/api/v1/auth/refresh', method: 'POST' },
      api,
      extraOptions,
    )

    if (refreshResult.data) {
      // Refresh succeeded — server has issued a new httpOnly cookie.
      // No token is dispatched to Redux.
      result = await v2BaseQuery(args, api, extraOptions)
    } else {
      api.dispatch(clearCurrentUser())
      window.location.href = '/login'
    }
  }

  return result
}

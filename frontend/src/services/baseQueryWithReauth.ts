import { fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query'
import { clearCurrentUser } from '../features/auth/authSlice'
import { getApiV1BaseUrl } from '@/lib/apiBase'

// Store tokens in memory for cross-domain requests (not in Redux per security policy)
let currentAccessToken: string | null = null
let currentRefreshToken: string | null = null

const rawBaseQuery = fetchBaseQuery({
  baseUrl: getApiV1BaseUrl(),
  credentials: 'include',
  // Try to send cookies first (for same-domain deployments)
  // But also prepare Authorization header if token is in memory
  prepareHeaders: (headers) => {
    if (currentAccessToken) {
      headers.set('Authorization', `Bearer ${currentAccessToken}`)
    }
    return headers
  },
})

/**
 * RTK Query base query with automatic token refresh.
 * Supports both:
 * 1. httpOnly cookies (same-domain deployment)
 * 2. Authorization headers (cross-domain deployment where tokens are returned in response body)
 */
export const baseQueryWithReauth: BaseQueryFn<
  string | FetchArgs,
  unknown,
  FetchBaseQueryError
> = async (args, api, extraOptions) => {
  let result = await rawBaseQuery(args, api, extraOptions)

  // Intercept login/mfa-verify responses to extract tokens from body
  const url = typeof args === 'string' ? args : args.url
  if ((url.includes('/auth/login') || url.includes('/auth/mfa-verify')) && result.data) {
    const response = result.data as any
    if (response.data?.accessToken) {
      currentAccessToken = response.data.accessToken
    }
    if (response.data?.refreshToken) {
      currentRefreshToken = response.data.refreshToken
    }
  }

  if (result.error?.status === 401) {
    // Don't attempt refresh if this IS the refresh call, the initial /auth/me probe,
    // or the MFA verify step (user has no full JWT yet — only a tempToken)
    if (url.includes('/auth/refresh') || url.includes('/auth/me') || url.includes('/auth/mfa-verify')) {
      return result
    }

    // Attempt silent token refresh
    const refreshBody = currentRefreshToken ? { refreshToken: currentRefreshToken } : {}
    const refreshResult = await rawBaseQuery(
      { url: '/auth/refresh', method: 'POST', body: refreshBody },
      api,
      extraOptions,
    )

    if (refreshResult.data) {
      // Extract new tokens from response
      const refreshResponse = refreshResult.data as any
      if (refreshResponse.data?.accessToken) {
        currentAccessToken = refreshResponse.data.accessToken
      }
      if (refreshResponse.data?.refreshToken) {
        currentRefreshToken = refreshResponse.data.refreshToken
      }
      // Retry original request with new token
      result = await rawBaseQuery(args, api, extraOptions)
    } else {
      // Refresh failed — clear state and redirect to login
      currentAccessToken = null
      currentRefreshToken = null
      api.dispatch(clearCurrentUser())
      window.location.href = '/login'
    }
  }

  return result
}

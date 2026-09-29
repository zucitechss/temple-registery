import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query'
import {
  CSRF_HEADER_NAME,
  getCsrfToken,
  refreshCsrfToken,
  isCsrfRejection,
  isUnsafeMethod,
} from './csrf'

type RawBaseQuery = BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError>

function methodOf(args: string | FetchArgs): string | undefined {
  // A plain string arg is an RTK Query query, i.e. a GET.
  return typeof args === 'string' ? undefined : args.method
}

/** Normalises whichever header shape the caller used into a plain object. */
function mergeHeader(
  existing: FetchArgs['headers'],
  name: string,
  value: string,
): Record<string, string> {
  const merged: Record<string, string> = {}

  if (existing instanceof Headers) {
    existing.forEach((headerValue, headerName) => {
      merged[headerName] = headerValue
    })
  } else if (Array.isArray(existing)) {
    existing.forEach(([headerName, headerValue]) => {
      merged[headerName] = headerValue
    })
  } else if (existing) {
    Object.assign(merged, existing)
  }

  merged[name] = value
  return merged
}

function withCsrfHeader(args: string | FetchArgs, token: string | null): string | FetchArgs {
  if (!token) return args
  const fetchArgs: FetchArgs = typeof args === 'string' ? { url: args } : args
  return { ...fetchArgs, headers: mergeHeader(fetchArgs.headers, CSRF_HEADER_NAME, token) }
}

/**
 * Wraps a base query so unsafe methods carry the CSRF token (H-5).
 *
 * Shared by both base queries so the behaviour is defined exactly once.
 *
 * Safe methods pass straight through and never trigger a token fetch. On a CSRF rejection
 * the token is refreshed and the request is retried **once** — the second result is returned
 * whatever it is, so a persistently failing token cannot loop.
 */
export function withCsrfProtection(rawBaseQuery: RawBaseQuery): RawBaseQuery {
  return async (args, api, extraOptions) => {
    if (!isUnsafeMethod(methodOf(args))) {
      return rawBaseQuery(args, api, extraOptions)
    }

    const token = await getCsrfToken()
    const result = await rawBaseQuery(withCsrfHeader(args, token), api, extraOptions)

    if (!isCsrfRejection(result.error)) {
      return result
    }

    // The cached token was stale or the cookie was rotated. Refresh and retry exactly once.
    const freshToken = await refreshCsrfToken()
    return rawBaseQuery(withCsrfHeader(args, freshToken), api, extraOptions)
  }
}

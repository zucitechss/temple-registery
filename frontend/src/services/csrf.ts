import { getApiV1BaseUrl } from '@/lib/apiBase'

/**
 * CSRF token handling for the SPA (H-5).
 *
 * The API authenticates browsers with an httpOnly `access_token` cookie issued
 * `SameSite=None`, so the browser attaches it to cross-site requests too. The backend
 * therefore requires a stateless double-submit token on every unsafe method: the value of
 * the readable `XSRF-TOKEN` cookie, echoed back in the `X-XSRF-TOKEN` header.
 *
 * The token is read from `GET /api/v1/auth/csrf` rather than from `document.cookie`, because
 * when the SPA and the API are served from different origins the cookie belongs to the API
 * domain and JavaScript on the SPA origin cannot see it. Reading it from the response body
 * works in both same-domain and cross-domain deployments.
 */

export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
export const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

/** Error code the backend returns for a CSRF rejection, distinct from real 403s. */
export const CSRF_ERROR_CODE = 'CSRF_TOKEN_INVALID'

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let cachedToken: string | null = null
let inFlight: Promise<string | null> | null = null

/**
 * Reads the current token straight from the cookie.
 *
 * This is the authoritative source and must be preferred over any cached value: Spring
 * rotates the CSRF token whenever a request authenticates, and because authentication here
 * is a stateless per-request JWT check, that means it rotates on *every* authenticated
 * request. A value cached from an earlier response is therefore stale almost immediately.
 *
 * Returns null when the SPA is served from a different origin than the API — the cookie
 * then belongs to the API domain and is invisible to this script.
 */
export function readCsrfCookie(): string | null {
  try {
    const match = document.cookie.match(
      new RegExp('(?:^|;\\s*)' + CSRF_COOKIE_NAME + '=([^;]*)'),
    )
    return match && match[1] ? decodeURIComponent(match[1]) : null
  } catch {
    return null
  }
}

/** RTK Query omits `method` for plain queries, which are GETs. */
export function isUnsafeMethod(method?: string): boolean {
  return UNSAFE_METHODS.has((method ?? 'GET').toUpperCase())
}

export function clearCsrfToken(): void {
  cachedToken = null
  inFlight = null
}

async function fetchToken(): Promise<string | null> {
  try {
    const response = await fetch(`${getApiV1BaseUrl()}/auth/csrf`, {
      method: 'GET',
      credentials: 'include',
    })
    if (!response.ok) return null

    const body = await response.json()
    const token = body?.data?.token
    return typeof token === 'string' && token.length > 0 ? token : null
  } catch {
    // Offline or blocked: report "no token" and let the caller proceed. The request will
    // fail server-side with a clear CSRF error rather than a confusing client exception.
    return null
  }
}

/**
 * Returns the cached token, fetching it once if needed. Concurrent callers share a single
 * in-flight request, so a burst of mutations does not produce a burst of token fetches.
 */
export async function getCsrfToken(): Promise<string | null> {
  // Same-origin deployments: the cookie is readable and always current, so no request and
  // no staleness. Cross-origin deployments fall through to the endpoint below.
  const fromCookie = readCsrfCookie()
  if (fromCookie) return fromCookie

  if (cachedToken) return cachedToken

  if (!inFlight) {
    inFlight = fetchToken().then((token) => {
      // A failed fetch is deliberately not cached, so the next call retries.
      if (token) cachedToken = token
      inFlight = null
      return token
    })
  }
  return inFlight
}

/** Discards the cached token and obtains a new one. Used after a server-side rejection. */
export async function refreshCsrfToken(): Promise<string | null> {
  clearCsrfToken()
  return getCsrfToken()
}

/**
 * Narrowly identifies a CSRF rejection.
 *
 * The application has legitimate 403s of its own (`ACCESS_DENIED`, `JURISDICTION_DENIED`
 * from GlobalExceptionHandler). Those are real permission failures: retrying them would be
 * pointless and would mask the result, so only the dedicated error code counts.
 */
export function isCsrfRejection(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false

  const { status, data } = error as { status?: unknown; data?: unknown }
  if (status !== 403) return false
  if (!data || typeof data !== 'object') return false

  return (data as { errorCode?: unknown }).errorCode === CSRF_ERROR_CODE
}

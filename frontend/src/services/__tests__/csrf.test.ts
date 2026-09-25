/**
 * H-5 — CSRF token handling for the SPA.
 *
 * The backend protects every unsafe method with a stateless double-submit token
 * (see backend SecurityConfig). This module is the single place that knows how to
 * obtain, cache and invalidate that token; both base queries delegate to it.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import {
  CSRF_HEADER_NAME,
  CSRF_ERROR_CODE,
  getCsrfToken,
  refreshCsrfToken,
  clearCsrfToken,
  isUnsafeMethod,
  isCsrfRejection,
  readCsrfCookie,
} from '../csrf'

function csrfResponse(token: string) {
  return {
    ok: true,
    json: async () => ({
      success: true,
      message: 'CSRF token issued.',
      data: { token, headerName: CSRF_HEADER_NAME, parameterName: '_csrf' },
    }),
  } as unknown as Response
}

describe('csrf — token retrieval and caching', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    clearCsrfToken()
  })

  it('should_fetchTokenFromAuthCsrfEndpoint_when_noTokenIsCached', async () => {
    const fetchMock = vi.fn().mockResolvedValue(csrfResponse('token-one'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getCsrfToken()).resolves.toBe('token-one')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/auth/csrf')
    expect(init).toMatchObject({ method: 'GET', credentials: 'include' })
  })

  it('should_reuseCachedToken_when_calledRepeatedly', async () => {
    const fetchMock = vi.fn().mockResolvedValue(csrfResponse('token-one'))
    vi.stubGlobal('fetch', fetchMock)

    await getCsrfToken()
    await getCsrfToken()
    await getCsrfToken()

    // Cached: one network round trip, not one per API call.
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('should_issueOnlyOneRequest_when_concurrentCallersRaceForTheToken', async () => {
    const fetchMock = vi.fn().mockResolvedValue(csrfResponse('token-one'))
    vi.stubGlobal('fetch', fetchMock)

    const results = await Promise.all([getCsrfToken(), getCsrfToken(), getCsrfToken()])

    expect(results).toEqual(['token-one', 'token-one', 'token-one'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('should_fetchAFreshToken_when_refreshIsRequested', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrfResponse('stale-token'))
      .mockResolvedValueOnce(csrfResponse('fresh-token'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getCsrfToken()).resolves.toBe('stale-token')
    await expect(refreshCsrfToken()).resolves.toBe('fresh-token')
    await expect(getCsrfToken()).resolves.toBe('fresh-token')

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('should_returnNullWithoutCaching_when_theEndpointFails', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 500 } as unknown as Response)
      .mockResolvedValueOnce(csrfResponse('recovered-token'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getCsrfToken()).resolves.toBeNull()
    // A failure must not poison the cache — the next attempt retries.
    await expect(getCsrfToken()).resolves.toBe('recovered-token')
  })

  it('should_returnNull_when_fetchThrows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    await expect(getCsrfToken()).resolves.toBeNull()
  })
})

describe('csrf — the cookie is preferred over any cached value', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  afterEach(() => {
    clearCsrfToken()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  })

  it('should_readTokenFromCookieWithoutAnyRequest_when_cookieIsVisible', async () => {
    const fetchMock = vi.fn().mockResolvedValue(csrfResponse('from-endpoint'))
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=from-cookie; path=/'

    await expect(getCsrfToken()).resolves.toBe('from-cookie')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('should_alwaysReflectTheLatestCookie_when_serverRotatesTheToken', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(csrfResponse('stale')))

    document.cookie = 'XSRF-TOKEN=rotation-1; path=/'
    await expect(getCsrfToken()).resolves.toBe('rotation-1')

    // The backend rotates the CSRF token on every authenticated request, so a value read
    // a moment ago is already stale. Nothing may be cached across rotations.
    document.cookie = 'XSRF-TOKEN=rotation-2; path=/'
    await expect(getCsrfToken()).resolves.toBe('rotation-2')

    document.cookie = 'XSRF-TOKEN=rotation-3; path=/'
    await expect(getCsrfToken()).resolves.toBe('rotation-3')
  })

  it('should_fallBackToTheEndpoint_when_cookieIsNotVisibleToScript', async () => {
    // Cross-origin deployment: the cookie belongs to the API domain.
    const fetchMock = vi.fn().mockResolvedValue(csrfResponse('from-endpoint'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(getCsrfToken()).resolves.toBe('from-endpoint')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('should_exposeTheRawCookieReader_when_askedDirectly', () => {
    expect(readCsrfCookie()).toBeNull()
    document.cookie = 'XSRF-TOKEN=direct-read; path=/'
    expect(readCsrfCookie()).toBe('direct-read')
  })
})

describe('csrf — unsafe method classification', () => {
  it.each(['POST', 'PUT', 'PATCH', 'DELETE', 'post', 'delete'])(
    'should_treatAsUnsafe_when_methodIs_%s',
    (method) => {
      expect(isUnsafeMethod(method)).toBe(true)
    },
  )

  it.each(['GET', 'HEAD', 'OPTIONS', 'get'])(
    'should_treatAsSafe_when_methodIs_%s',
    (method) => {
      expect(isUnsafeMethod(method)).toBe(false)
    },
  )

  it('should_treatAsSafe_when_methodIsOmitted', () => {
    // RTK Query omits `method` for plain queries, which are GETs.
    expect(isUnsafeMethod(undefined)).toBe(false)
  })
})

describe('csrf — rejection detection', () => {
  it('should_detectCsrfRejection_when_403CarriesCsrfErrorCode', () => {
    expect(isCsrfRejection({ status: 403, data: { errorCode: CSRF_ERROR_CODE } })).toBe(true)
  })

  it('should_notDetectCsrfRejection_when_403IsARealAuthorizationDenial', () => {
    // The application returns these from GlobalExceptionHandler. Retrying them
    // would hide a genuine permission failure behind a pointless second request.
    expect(isCsrfRejection({ status: 403, data: { errorCode: 'ACCESS_DENIED' } })).toBe(false)
    expect(isCsrfRejection({ status: 403, data: { errorCode: 'JURISDICTION_DENIED' } })).toBe(false)
  })

  it('should_notDetectCsrfRejection_when_403HasNoErrorCode', () => {
    expect(isCsrfRejection({ status: 403, data: undefined })).toBe(false)
    expect(isCsrfRejection({ status: 403, data: 'Forbidden' })).toBe(false)
  })

  it('should_notDetectCsrfRejection_when_statusIsNot403', () => {
    expect(isCsrfRejection({ status: 401, data: { errorCode: CSRF_ERROR_CODE } })).toBe(false)
    expect(isCsrfRejection({ status: 500, data: { errorCode: CSRF_ERROR_CODE } })).toBe(false)
  })

  it('should_notDetectCsrfRejection_when_errorIsAbsent', () => {
    expect(isCsrfRejection(undefined)).toBe(false)
    expect(isCsrfRejection(null)).toBe(false)
  })
})

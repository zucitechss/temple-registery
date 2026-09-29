/**
 * H-5 — CSRF behaviour of both RTK Query base queries.
 *
 * Drives the real base queries against MSW, so the assertions cover what actually goes on
 * the wire: the header on unsafe methods, no header on safe ones, and a retry that is
 * bounded to exactly one extra attempt.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'

// MSW's Node interceptor cannot resolve relative request URLs the way a browser does, so the
// API origin is made explicit here. This only changes where requests point in the test; the
// CSRF logic under test is untouched.
vi.mock('@/lib/apiBase', () => ({
  getApiV1BaseUrl: () => 'http://localhost:8080/api/v1',
  getApiRootBaseUrl: () => 'http://localhost:8080',
}))

import { CSRF_HEADER_NAME, CSRF_ERROR_CODE, clearCsrfToken } from '../csrf'
import { baseQueryWithReauth } from '../baseQueryWithReauth'
import { baseQueryV2WithReauth } from '../baseQueryV2WithReauth'

type Call = { url: string; method: string; csrf: string | null }

let calls: Call[] = []

function record(request: Request): Call {
  const call = {
    url: request.url,
    method: request.method.toUpperCase(),
    csrf: request.headers.get(CSRF_HEADER_NAME),
  }
  calls.push(call)
  return call
}

const apiCalls = () => calls.filter((c) => !c.url.includes('/auth/csrf'))
const csrfCalls = () => calls.filter((c) => c.url.includes('/auth/csrf'))

/** Minimal stand-in for the RTK Query BaseQueryApi surface that fetchBaseQuery uses. */
function fakeApi() {
  return {
    signal: undefined as unknown as AbortSignal,
    abort: () => {},
    dispatch: () => {},
    getState: () => ({}),
    extra: undefined,
    endpoint: 'test',
    type: 'query' as const,
    forced: false,
  }
}

const csrfIssued = (token: string) =>
  HttpResponse.json({
    success: true,
    message: 'CSRF token issued.',
    data: { token, headerName: CSRF_HEADER_NAME, parameterName: '_csrf' },
  })

const csrfRejected = () =>
  HttpResponse.json(
    {
      success: false,
      message: 'Missing or invalid CSRF token. Retry after refreshing the token.',
      errorCode: CSRF_ERROR_CODE,
    },
    { status: 403 },
  )

const ok = () => HttpResponse.json({ success: true, message: 'ok', data: { id: 1 } })

/** Serves a fresh token on every call, numbered so tests can tell them apart. */
function csrfEndpoint(tokens: string[]) {
  let issued = 0
  return http.get('*/auth/csrf', () => {
    const token = tokens[Math.min(issued, tokens.length - 1)]
    issued += 1
    return csrfIssued(token)
  })
}

describe.each([
  ['baseQueryWithReauth', baseQueryWithReauth],
  ['baseQueryV2WithReauth', baseQueryV2WithReauth],
] as const)('%s — CSRF', (_name, baseQuery) => {
  beforeEach(() => {
    calls = []
    clearCsrfToken()
  })

  afterEach(() => {
    clearCsrfToken()
  })

  it('should_attachCsrfHeader_when_requestIsUnsafe', async () => {
    server.use(
      csrfEndpoint(['tok-abc']),
      http.post('*/temples', ({ request }) => {
        record(request)
        return ok()
      }),
    )

    await baseQuery({ url: '/temples', method: 'POST', body: { name: 'x' } }, fakeApi(), {})

    const mutation = apiCalls().at(-1)!
    expect(mutation.method).toBe('POST')
    expect(mutation.csrf).toBe('tok-abc')
  })

  it('should_notAttachCsrfHeader_when_requestIsASafeGet', async () => {
    server.use(
      csrfEndpoint(['tok-abc']),
      http.get('*/temples', ({ request }) => {
        record(request)
        return ok()
      }),
      http.get('*/auth/csrf', ({ request }) => {
        record(request)
        return csrfIssued('tok-abc')
      }),
    )

    await baseQuery({ url: '/temples', method: 'GET' }, fakeApi(), {})

    expect(apiCalls().at(-1)!.csrf).toBeNull()
    // A GET must not even pay for a token fetch.
    expect(csrfCalls()).toHaveLength(0)
  })

  it('should_notAttachCsrfHeader_when_requestIsAPlainStringQuery', async () => {
    server.use(
      csrfEndpoint(['tok-abc']),
      http.get('*/temples', ({ request }) => {
        record(request)
        return ok()
      }),
    )

    await baseQuery('/temples', fakeApi(), {})

    expect(apiCalls().at(-1)!.csrf).toBeNull()
  })

  it('should_refreshTokenAndRetryOnce_when_serverRejectsTheCsrfToken', async () => {
    server.use(
      csrfEndpoint(['stale-token', 'fresh-token']),
      http.post('*/temples', ({ request }) => {
        const call = record(request)
        // Only the refreshed token is accepted.
        return call.csrf === 'fresh-token' ? ok() : csrfRejected()
      }),
    )

    const result = await baseQuery({ url: '/temples', method: 'POST' }, fakeApi(), {})

    const mutations = apiCalls()
    expect(mutations).toHaveLength(2)
    expect(mutations[0].csrf).toBe('stale-token')
    expect(mutations[1].csrf).toBe('fresh-token')
    expect(result.error).toBeUndefined()
  })

  it('should_stopAfterOneRetry_when_csrfKeepsFailing', async () => {
    server.use(
      csrfEndpoint(['never-good']),
      http.post('*/temples', ({ request }) => {
        record(request)
        return csrfRejected()
      }),
    )

    const result = await baseQuery({ url: '/temples', method: 'POST' }, fakeApi(), {})

    // Bounded: original attempt + exactly one retry. No infinite loop.
    expect(apiCalls()).toHaveLength(2)
    expect((result.error as { status?: number }).status).toBe(403)
  })

  it('should_notRetry_when_403IsAGenuineAuthorizationDenial', async () => {
    server.use(
      csrfEndpoint(['tok-abc']),
      http.post('*/temples', ({ request }) => {
        record(request)
        return HttpResponse.json(
          {
            success: false,
            message: 'You do not have permission to perform this action.',
            errorCode: 'ACCESS_DENIED',
          },
          { status: 403 },
        )
      }),
    )

    const result = await baseQuery({ url: '/temples', method: 'POST' }, fakeApi(), {})

    expect(apiCalls()).toHaveLength(1)
    expect((result.error as { status?: number }).status).toBe(403)
  })

  it('should_reuseTheCachedToken_when_severalUnsafeRequestsAreMade', async () => {
    const handle = ({ request }: { request: Request }) => {
      record(request)
      return ok()
    }
    server.use(
      http.get('*/auth/csrf', ({ request }) => {
        record(request)
        return csrfIssued('tok-abc')
      }),
      http.post('*/temples', handle),
      http.put('*/temples/1', handle),
      http.delete('*/temples/1', handle),
    )

    await baseQuery({ url: '/temples', method: 'POST' }, fakeApi(), {})
    await baseQuery({ url: '/temples/1', method: 'PUT' }, fakeApi(), {})
    await baseQuery({ url: '/temples/1', method: 'DELETE' }, fakeApi(), {})

    expect(csrfCalls()).toHaveLength(1)
    expect(apiCalls()).toHaveLength(3)
  })
})

describe('baseQueryWithReauth — existing 401 refresh behaviour is preserved', () => {
  beforeEach(() => {
    calls = []
    clearCsrfToken()
  })

  afterEach(() => {
    clearCsrfToken()
  })

  it('should_refreshSessionAndRetryOriginalRequest_when_requestReturns401', async () => {
    let seen = 0
    server.use(
      csrfEndpoint(['tok-abc']),
      http.post('*/auth/refresh', ({ request }) => {
        record(request)
        return HttpResponse.json({
          success: true,
          message: 'Token refreshed.',
          data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
        })
      }),
      http.get('*/temples', ({ request }) => {
        record(request)
        seen += 1
        return seen === 1
          ? HttpResponse.json(
              { success: false, message: 'Unauthorized', errorCode: 'UNAUTHORIZED' },
              { status: 401 },
            )
          : ok()
      }),
    )

    const result = await baseQueryWithReauth({ url: '/temples', method: 'GET' }, fakeApi(), {})

    expect(calls.some((c) => c.url.includes('/auth/refresh'))).toBe(true)
    expect(result.error).toBeUndefined()
    expect(seen).toBe(2)
  })

  it('should_sendCsrfHeaderOnTheSessionRefreshCall_when_refreshingAfter401', async () => {
    // /auth/refresh is a POST and is deliberately not exempt from CSRF on the server,
    // so the silent re-auth path has to carry the token too or it would 403.
    let seen = 0
    server.use(
      csrfEndpoint(['tok-abc']),
      http.post('*/auth/refresh', ({ request }) => {
        record(request)
        return HttpResponse.json({ success: true, message: 'Token refreshed.', data: {} })
      }),
      http.get('*/temples', ({ request }) => {
        record(request)
        seen += 1
        return seen === 1
          ? HttpResponse.json({ success: false, message: 'Unauthorized' }, { status: 401 })
          : ok()
      }),
    )

    await baseQueryWithReauth({ url: '/temples', method: 'GET' }, fakeApi(), {})

    const refreshCall = calls.find((c) => c.url.includes('/auth/refresh'))!
    expect(refreshCall.csrf).toBe('tok-abc')
  })
})

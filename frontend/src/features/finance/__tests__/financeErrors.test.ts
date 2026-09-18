import { describe, expect, it } from 'vitest'
import { parseApiError } from '../financeErrors'

const withBody = (status: number, data: unknown) => ({ status, data })

describe('parseApiError', () => {
  it('names an optimistic-lock conflict as one, and says to refresh before saving again', () => {
    const parsed = parseApiError(
      withBody(409, { message: 'Rule 7 has changed', errorCode: 'OPTIMISTIC_LOCK_CONFLICT' }),
    )

    expect(parsed.isVersionConflict).toBe(true)
    expect(parsed.isDuplicate).toBe(false)
    expect(parsed.message).toContain('changed by another user')
    expect(parsed.message).toContain('Refresh')
  })

  it('treats a 409 without the lock code as a duplicate and keeps the backend wording', () => {
    const parsed = parseApiError(
      withBody(409, { message: 'Rule 12 already maps [SEVA_CODE:430] to [SEVA]' }),
    )

    expect(parsed.isDuplicate).toBe(true)
    expect(parsed.isVersionConflict).toBe(false)
    // The server names the conflicting rule; paraphrasing would lose the one useful detail.
    expect(parsed.message).toContain('Rule 12')
  })

  it('keeps a 422 message verbatim — those are written for the person reading them', () => {
    const message =
      '[SEVA_CODE] is not an active revenue category. Known values: [DONATION, SEVA]'
    expect(parseApiError(withBody(422, { message })).message).toBe(message)
  })

  it('collects 400 field errors separately so a form can attach them', () => {
    const parsed = parseApiError(
      withBody(400, {
        message: 'Request validation failed.',
        errorCode: 'VALIDATION_ERROR',
        errors: ['namespace is required', 'priority must be between 0 and 1000'],
      }),
    )

    expect(parsed.fieldErrors).toHaveLength(2)
    expect(parsed.fieldErrors[0]).toContain('namespace')
  })

  it('never shows a server message from a 500 — it may carry internal detail', () => {
    const parsed = parseApiError(
      withBody(500, { message: 'NullPointerException at com.templeregistry.Foo.bar(Foo.java:42)' }),
    )

    expect(parsed.message).not.toContain('NullPointerException')
    expect(parsed.message).not.toContain('Foo.java')
    expect(parsed.status).toBe(500)
  })

  it('reports a transport failure as unreachable rather than as a server refusal', () => {
    const parsed = parseApiError({ status: 'FETCH_ERROR', error: 'Failed to fetch' })

    expect(parsed.message).toContain('could not be reached')
    expect(parsed.message).toContain('nothing was saved')
  })

  it('does not leak whether a 404 means "missing" or "not yours"', () => {
    const parsed = parseApiError(withBody(404, { message: 'Source system not found with id: 99' }))

    expect(parsed.message).not.toMatch(/\b99\b/)
    expect(parsed.message).toContain('not available to you')
  })

  it('tells an unauthenticated user to sign in, and an unauthorized one that they cannot', () => {
    expect(parseApiError(withBody(401, {})).message).toContain('sign in')
    expect(parseApiError(withBody(403, {})).message).toContain('do not have permission')
  })
})

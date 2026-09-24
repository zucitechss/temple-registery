import { describe, expect, it } from 'vitest'
import { parseOnboardingError } from '../financeOnboardingErrors'

describe('parseOnboardingError (FIN-140 slice 140-A)', () => {
  it('reports a transport failure as unreachable, not as a server fault', () => {
    const parsed = parseOnboardingError({ status: 'FETCH_ERROR', error: 'Failed to fetch' })

    expect(parsed.message).toMatch(/could not be reached/i)
    expect(parsed.status).toBeUndefined()
  })

  it('keeps bean-validation messages separate so a form can attach them', () => {
    const parsed = parseOnboardingError({
      status: 400,
      data: { message: 'Validation failed.', errors: ['systemCode is required.'] },
    })

    expect(parsed.fieldErrors).toEqual(['systemCode is required.'])
    expect(parsed.message).toBe('Validation failed.')
  })

  it('names the platform administrator restriction on 403', () => {
    expect(parseOnboardingError({ status: 403, data: {} }).message)
      .toMatch(/platform administrator/i)
  })

  it('does not guess whether a 404 means absent or out of scope', () => {
    const parsed = parseOnboardingError({ status: 404, data: {} })

    expect(parsed.message).toMatch(/not available to you/i)
    expect(parsed.message).not.toMatch(/district|jurisdiction|does not exist/i)
  })

  it('distinguishes a stale version from a duplicate code, both of which are 409', () => {
    const stale = parseOnboardingError({
      status: 409,
      data: { errorCode: 'OPTIMISTIC_LOCK_CONFLICT', message: 'changed' },
    })
    const duplicate = parseOnboardingError({
      status: 409,
      data: { message: 'Source system 7 already uses code [KOLSOHAM] for this temple.' },
    })

    expect(stale.isVersionConflict).toBe(true)
    expect(stale.isDuplicateCode).toBe(false)
    expect(duplicate.isDuplicateCode).toBe(true)
    expect(duplicate.message).toContain('KOLSOHAM')
  })

  it('shows a 422 refusal verbatim — the backend explains why, and the why is the useful half', () => {
    const message =
      'Temple 300001 already has source system 5 [KOLSOHAM]. The platform supports one finance '
      + 'source per temple today (open decision D9).'

    const parsed = parseOnboardingError({ status: 422, data: { message } })

    expect(parsed.isRefused).toBe(true)
    expect(parsed.message).toBe(message)
  })

  it('shows nothing the server said on a 500', () => {
    const parsed = parseOnboardingError({
      status: 500,
      data: { message: 'NullPointerException at com.templeregistry.Foo.bar(Foo.java:42)' },
    })

    expect(parsed.message).not.toMatch(/NullPointerException|\.java/)
    expect(parsed.status).toBe(500)
  })
})

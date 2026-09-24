import { describe, expect, it } from 'vitest'
import { parseReportError } from '../financeReportErrors'

describe('parseReportError', () => {
  it('reports an offline/transport failure distinctly from a server error', () => {
    const result = parseReportError({ status: 'FETCH_ERROR' })
    expect(result.message).toMatch(/could not be reached/i)
  })

  it('flags an invalid financial year by its error code, not by guessing from the message', () => {
    const result = parseReportError({
      status: 400,
      data: { message: "'2025' is not a financial year in the form yyyy-yy, e.g. 2025-26.", errorCode: 'INVALID_FINANCIAL_YEAR' },
    })
    expect(result.isInvalidFinancialYear).toBe(true)
    expect(result.message).toContain('2025-26')
  })

  it('does not flag an ordinary 400 as an invalid financial year', () => {
    const result = parseReportError({ status: 400, data: { message: 'Bad request' } })
    expect(result.isInvalidFinancialYear).toBe(false)
  })

  it('never surfaces a raw 500 body — it may carry a stack trace', () => {
    const result = parseReportError({ status: 500, data: { message: 'NullPointerException at line 42' } })
    expect(result.message).not.toContain('NullPointerException')
  })

  it('does not guess whether a 404 means "no such temple" or "outside your jurisdiction"', () => {
    // JurisdictionGuard answers both cases identically and deliberately (never leaks district
    // existence). This message must not imply either specific cause.
    const result = parseReportError({ status: 404, data: {} })
    expect(result.message).not.toMatch(/district|jurisdiction/i)
  })

  it('gives a permission-specific message for 403', () => {
    const result = parseReportError({ status: 403, data: {} })
    expect(result.message).toMatch(/permission/i)
  })
})

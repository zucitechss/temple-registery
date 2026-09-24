/**
 * One reading of a finance reporting API failure (FIN-090).
 *
 * <p>The read-only surface has a narrower error set than the mapping admin screen's
 * (`financeErrors`), so this does not share that module: the messages are different because the
 * situations are different — there is no version conflict, no duplicate, nothing to retry with
 * different input except the financial year.
 */

export interface ParsedReportError {
  /** One sentence to show the user. Never a stack trace. */
  message: string
  status?: number
  errorCode?: string
  /** True for 400 `INVALID_FINANCIAL_YEAR` — the caller sent a malformed year, not a server fault. */
  isInvalidFinancialYear: boolean
}

interface BackendBody {
  message?: string
  errorCode?: string
}

const GENERIC =
  'Something went wrong loading these figures. Please try again, and contact support if it continues.'

const OFFLINE = 'The server could not be reached. Check your connection and try again.'

export function parseReportError(error: unknown): ParsedReportError {
  const empty: ParsedReportError = { message: GENERIC, isInvalidFinancialYear: false }

  if (!error || typeof error !== 'object') return empty

  const status = (error as { status?: number | string }).status
  const body = (error as { data?: BackendBody }).data

  // fetchBaseQuery reports transport problems with a non-numeric status.
  if (typeof status !== 'number') {
    return { ...empty, message: OFFLINE }
  }

  const backendMessage = typeof body?.message === 'string' ? body.message : undefined
  const errorCode = body?.errorCode

  switch (status) {
    case 400: {
      const isInvalidFinancialYear = errorCode === 'INVALID_FINANCIAL_YEAR'
      return {
        status,
        errorCode,
        isInvalidFinancialYear,
        message: backendMessage ?? 'The requested financial year is not valid.',
      }
    }

    case 401:
      return { status, errorCode, isInvalidFinancialYear: false,
        message: 'Your session has expired. Please sign in again.' }

    case 403:
      return { status, errorCode, isInvalidFinancialYear: false,
        message: 'You do not have permission to view this temple’s finance figures.' }

    case 404:
      return {
        status,
        errorCode,
        isInvalidFinancialYear: false,
        // The backend answers 404 both for "no such temple" and "not in your jurisdiction", on
        // purpose (JurisdictionGuard never leaks district existence). This wording does not guess
        // which.
        message: 'This temple’s finance figures are not available to you.',
      }

    default:
      if (status >= 500) return { ...empty, status, errorCode }
      return { status, errorCode, isInvalidFinancialYear: false, message: backendMessage ?? GENERIC }
  }
}

/**
 * One reading of an onboarding API failure (FIN-140 slice 140-A).
 *
 * Mirrors `financeErrors.parseApiError` in structure and differs only where the wording must
 * differ — this screen configures source systems, not mapping rules, and a message naming the
 * wrong thing sends an administrator to the wrong screen. Three behaviours are carried over
 * unchanged because the reasons are the same:
 *
 * - **Nothing from a 500 is shown.** A server message at that point may carry a stack trace.
 * - **404 does not guess** whether the row is absent or out of scope. The backend answers the same
 *   for both on purpose, and a client that guessed would leak the difference it hides.
 * - **Field errors stay separate** so a form can attach them instead of dumping them in a toast.
 */

export interface ParsedOnboardingError {
  message: string
  status?: number
  errorCode?: string
  fieldErrors: string[]
  /** 409 from the version check — reload before saving again. */
  isVersionConflict: boolean
  /** 409 from the unique key — this temple already uses that code, possibly on a retired source. */
  isDuplicateCode: boolean
  /** 422 — well formed, semantically refused. The backend's sentence is worth showing verbatim. */
  isRefused: boolean
}

interface BackendBody {
  message?: string
  errorCode?: string
  errors?: string[]
}

const GENERIC =
  'Something went wrong and nothing was saved. Please try again, and contact support if it continues.'

const OFFLINE =
  'The server could not be reached. Check your connection and try again — nothing was saved.'

export function parseOnboardingError(error: unknown): ParsedOnboardingError {
  const empty: ParsedOnboardingError = {
    message: GENERIC,
    fieldErrors: [],
    isVersionConflict: false,
    isDuplicateCode: false,
    isRefused: false,
  }

  if (!error || typeof error !== 'object') return empty

  const status = (error as { status?: number | string }).status
  const body = (error as { data?: BackendBody }).data

  if (typeof status !== 'number') {
    return { ...empty, message: OFFLINE }
  }

  const backendMessage = typeof body?.message === 'string' ? body.message : undefined
  const errorCode = body?.errorCode
  const fieldErrors = Array.isArray(body?.errors)
    ? body.errors.filter((e): e is string => typeof e === 'string')
    : []

  // `empty` first: the concrete status, code and field errors parsed above must win over its
  // defaults, not be overwritten by them.
  const base = { ...empty, status, errorCode, fieldErrors, message: GENERIC }

  switch (status) {
    case 400:
      return { ...base, message: backendMessage ?? 'Some of the values entered are not valid.' }

    case 401:
      return { ...base, message: 'Your session has expired. Please sign in again.' }

    case 403:
      return {
        ...base,
        message:
          'Only a platform administrator can register or change a finance source system.',
      }

    case 404:
      return {
        ...base,
        message:
          'This source system or temple is not available to you. It may have been removed.',
      }

    case 409: {
      const isVersionConflict = errorCode === 'OPTIMISTIC_LOCK_CONFLICT'
      return {
        ...base,
        isVersionConflict,
        isDuplicateCode: !isVersionConflict,
        message: isVersionConflict
          ? 'This source system was changed by another user. Reload it and review the latest version before saving again.'
          : backendMessage ?? 'This temple already has a source system with that code.',
      }
    }

    case 422:
      // Where the backend puts its semantic refusals — the one-source-per-temple rule among them.
      // Those sentences are written for the person reading them and explain why, so they are shown
      // exactly as sent rather than replaced with something shorter and less useful.
      return {
        ...base,
        isRefused: true,
        message: backendMessage ?? 'This source system cannot be saved as entered.',
      }

    default:
      if (status >= 500) return { ...empty, status, errorCode }
      return { ...base, message: backendMessage ?? GENERIC }
  }
}

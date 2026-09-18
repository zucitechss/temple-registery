/**
 * One reading of an API failure, used by every surface on this screen (FIN-054B).
 *
 * The backend answers with `ApiResponse` carrying `message`, `errorCode` and sometimes `errors`.
 * This turns that into something to show a person, and does three things deliberately:
 *
 * - **409 is never silently retried or overwritten.** An optimistic-lock conflict means somebody
 *   else's change is in the database, and the only correct action is to look at it.
 * - **Nothing from a 500 is shown.** A server message at that point may carry a stack trace or an
 *   internal identifier, so the generic sentence is used instead.
 * - **Field errors are kept separate** so a form can attach them rather than dumping them in a toast.
 */

export interface ParsedApiError {
  /** One sentence to show the user. Never a stack trace. */
  message: string
  /** HTTP status, when the request reached the server at all. */
  status?: number
  /** The backend's `errorCode`, for support and for tests. */
  errorCode?: string
  /** Validation messages from a 400, in the order the server returned them. */
  fieldErrors: string[]
  /** True for 409 from the version check — the caller must refresh before saving again. */
  isVersionConflict: boolean
  /** True for 409 from the unique constraint — a rule already claims this source value. */
  isDuplicate: boolean
}

interface BackendBody {
  message?: string
  errorCode?: string
  errors?: string[]
}

const GENERIC =
  'Something went wrong and the change was not saved. Please try again, and contact support if it continues.'

const OFFLINE =
  'The server could not be reached. Check your connection and try again — nothing was saved.'

export function parseApiError(error: unknown): ParsedApiError {
  const empty: ParsedApiError = {
    message: GENERIC,
    fieldErrors: [],
    isVersionConflict: false,
    isDuplicate: false,
  }

  if (!error || typeof error !== 'object') return empty

  const status = (error as { status?: number | string }).status
  const body = (error as { data?: BackendBody }).data

  // fetchBaseQuery reports transport problems with a non-numeric status.
  if (typeof status !== 'number') {
    return { ...empty, message: OFFLINE }
  }

  const backendMessage = typeof body?.message === 'string' ? body.message : undefined
  const errorCode = body?.errorCode
  const fieldErrors = Array.isArray(body?.errors) ? body.errors.filter((e) => typeof e === 'string') : []

  switch (status) {
    case 400:
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict: false,
        isDuplicate: false,
        message: backendMessage ?? 'Some of the values entered are not valid.',
      }

    case 401:
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict: false,
        isDuplicate: false,
        message: 'Your session has expired. Please sign in again.',
      }

    case 403:
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict: false,
        isDuplicate: false,
        message: 'You do not have permission to change mapping rules.',
      }

    case 404:
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict: false,
        isDuplicate: false,
        // The backend answers 404 both for "no such rule" and for "not in your jurisdiction", on
        // purpose. This wording does not guess which, because guessing would leak the difference.
        message: 'This mapping rule or source system is not available to you. It may have been removed.',
      }

    case 409: {
      const isVersionConflict = errorCode === 'OPTIMISTIC_LOCK_CONFLICT'
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict,
        isDuplicate: !isVersionConflict,
        message: isVersionConflict
          ? 'This mapping was changed by another user. Refresh the record and review the latest version before saving again.'
          : backendMessage ?? 'A mapping rule already exists for this source value.',
      }
    }

    case 422:
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict: false,
        isDuplicate: false,
        // 422 is where the backend puts its semantic refusals — a malformed namespace, an unknown
        // canonical value, a mapping type nothing reads. Those messages are written for the person
        // reading them and are worth showing exactly as sent.
        message: backendMessage ?? 'This mapping cannot be saved as entered.',
      }

    default:
      if (status >= 500) {
        return { ...empty, status, errorCode }
      }
      return {
        status,
        errorCode,
        fieldErrors,
        isVersionConflict: false,
        isDuplicate: false,
        message: backendMessage ?? GENERIC,
      }
  }
}

import { APIRequestContext } from '@playwright/test';

/**
 * CSRF support for E2E (H-5).
 *
 * The API requires a stateless double-submit token on every unsafe method: the value of the
 * `XSRF-TOKEN` cookie echoed back in the `X-XSRF-TOKEN` header. E2E obtains that token the
 * same way the SPA does — from `GET /api/v1/auth/csrf` — so these tests exercise the real
 * production security behaviour rather than a relaxed test-only configuration.
 *
 * Nothing here bypasses or disables CSRF.
 */

export const CSRF_HEADER = 'X-XSRF-TOKEN';
export const CSRF_COOKIE = 'XSRF-TOKEN';

/**
 * Fetches a CSRF token using the supplied context.
 *
 * The response also sets the `XSRF-TOKEN` cookie in that context's cookie jar, so the cookie
 * half of the double submit travels with any state captured from it via `storageState()`.
 */
export async function fetchCsrfToken(
  context: APIRequestContext,
  origin = '',
): Promise<string> {
  // `origin` is needed for contexts whose baseURL is not the API (the Playwright `request`
  // fixture uses the frontend baseURL from playwright.config.ts).
  const response = await context.get(`${origin}/api/v1/auth/csrf`);

  if (!response.ok()) {
    throw new Error(
      `Unable to obtain CSRF token: ${response.status()} - ${await response.text()}`,
    );
  }

  const payload = await response.json();
  const token = payload?.data?.token;

  if (typeof token !== 'string' || token.length === 0) {
    throw new Error(`CSRF endpoint returned no token: ${JSON.stringify(payload)}`);
  }

  return token;
}

/** Convenience for spreading into a request's `headers`. */
export function csrfHeader(token: string): Record<string, string> {
  return { [CSRF_HEADER]: token };
}

/**
 * Reads the CSRF token that is currently in the context's cookie jar.
 *
 * This must be re-read before every unsafe request rather than pinned once per context:
 * Spring rotates the CSRF token on each request that authenticates, and since the app
 * authenticates statelessly on every request, a token captured at login is stale by the
 * time the next call is made.
 */
export async function currentCsrfToken(context: APIRequestContext): Promise<string> {
  const state = await context.storageState();
  const cookie = state.cookies.find((candidate) => candidate.name === CSRF_COOKIE);

  if (!cookie || !cookie.value) {
    // No cookie yet (fresh context): fetch one, which also seeds the jar.
    return fetchCsrfToken(context);
  }
  return cookie.value;
}

/** Header carrying whatever token the context currently holds. */
export async function currentCsrfHeader(
  context: APIRequestContext,
): Promise<Record<string, string>> {
  return csrfHeader(await currentCsrfToken(context));
}

const UNSAFE_METHODS = new Set(['post', 'put', 'patch', 'delete', 'fetch']);

/**
 * Wraps a context so every unsafe request automatically carries the token that is current
 * at the moment of the call.
 *
 * Done as a wrapper rather than a fixed `extraHTTPHeaders` value because the token rotates
 * per request: a header pinned when the context was created is correct for exactly one
 * call. Wrapping also keeps the ~68 existing call sites across the specs unchanged.
 *
 * An explicit `headers` entry still wins, so tests can deliberately send a bad token.
 */
export function withCsrfAutoToken(context: APIRequestContext): APIRequestContext {
  return new Proxy(context, {
    get(target, prop, receiver) {
      const value = Reflect.get(target, prop, receiver);

      if (typeof value !== 'function') {
        return value;
      }
      if (typeof prop !== 'string' || !UNSAFE_METHODS.has(prop)) {
        return value.bind(target);
      }

      return async (url: string, options: Record<string, any> = {}) => {
        const token = await currentCsrfToken(target);
        return (value as (...args: any[]) => any).call(target, url, {
          ...options,
          headers: { ...csrfHeader(token), ...(options.headers ?? {}) },
        });
      };
    },
  });
}

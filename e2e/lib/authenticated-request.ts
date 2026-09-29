import { APIRequestContext, APIResponse, request } from '@playwright/test';
import { env, RoleKey } from '../setup/env';
import { csrfHeader, fetchCsrfToken, withCsrfAutoToken } from './csrf';

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data?: T;
  errorCode?: string;
  errors?: unknown[];
}

export function resolveApiPath(path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }

  const normalized = path.startsWith('/') ? path : `/${path}`;
  if (normalized.startsWith('/api/')) {
    return normalized;
  }

  return `/api/v1${normalized}`;
}

export async function createAuthenticatedApiContext(role: RoleKey): Promise<APIRequestContext> {
  const credential = env.roles[role];

  const loginContext = await request.newContext({
    baseURL: env.apiOrigin,
    extraHTTPHeaders: {
      'Content-Type': 'application/json',
    },
  });

  // Login is a POST and is deliberately not exempt from CSRF (H-5), so a token is needed
  // before authenticating. This also seeds the XSRF-TOKEN cookie into the context's jar.
  const csrfToken = await fetchCsrfToken(loginContext);

  const loginResponse = await loginContext.post('/api/v1/auth/login', {
    data: {
      username: credential.username,
      password: credential.password,
    },
    headers: csrfHeader(csrfToken),
  });

  if (!loginResponse.ok()) {
    const body = await loginResponse.text();
    await loginContext.dispose();
    throw new Error(`Unable to login as ${role}: ${loginResponse.status()} - ${body}`);
  }

  // storageState carries both the auth cookies and the XSRF-TOKEN cookie. The header half
  // cannot be pinned here: the server rotates the token on every authenticated request, so
  // the wrapper re-reads the cookie for each unsafe call.
  const storageState = await loginContext.storageState();
  await loginContext.dispose();

  return withCsrfAutoToken(await request.newContext({
    baseURL: env.apiOrigin,
    storageState,
  }));
}

export async function parseApiEnvelope<T>(response: APIResponse): Promise<ApiEnvelope<T>> {
  const payload = await response.json();
  if (!payload || typeof payload !== 'object') {
    throw new Error('API response is not a JSON object.');
  }
  return payload as ApiEnvelope<T>;
}

import { request } from '@playwright/test';
import { test, expect } from '../fixtures/data.fixture';
import { env } from '../setup/env';
import { CSRF_HEADER, csrfHeader, fetchCsrfToken } from '../lib/csrf';
import {
  createAuthenticatedApiContext,
  parseApiEnvelope,
  resolveApiPath,
} from '../lib/authenticated-request';

/**
 * H-5 — CSRF protection, exercised against the running application.
 *
 * These tests run against the same security configuration as production: CSRF is enabled and
 * nothing here disables or bypasses it. The point is to prove that the token is genuinely
 * required, not merely that the suite still passes once it is supplied.
 */

function uniqueFinancialYear(seed: number): string {
  const nanos = Number(process.hrtime.bigint() & 0xffffffn);
  const start = 1000 + ((seed ^ nanos ^ process.pid) % 8999);
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

function declarationPayload(financialYear: string) {
  return {
    financialYear,
    dueDate: '2026-03-31',
    annualIncome: 1000,
    annualExpenditure: 500,
    agriculturalLands: [],
    buildings: [],
    leasedProperties: [],
    otherLands: [],
    preciousMetals: [],
    artifacts: [],
    vehicles: [],
    equipment: [],
    financialAssets: [],
  };
}

/**
 * Authenticates and returns a context that carries the auth cookies but deliberately sends
 * no CSRF header — i.e. exactly what a cross-site forgery can produce, since the browser
 * replays cookies automatically but cannot set a custom header.
 */
async function createAuthenticatedContextWithoutCsrfHeader() {
  const loginContext = await request.newContext({
    baseURL: env.apiOrigin,
    extraHTTPHeaders: { 'Content-Type': 'application/json' },
  });

  const token = await fetchCsrfToken(loginContext);
  const loginResponse = await loginContext.post('/api/v1/auth/login', {
    data: { username: env.roles.TA.username, password: env.roles.TA.password },
    headers: csrfHeader(token),
  });
  expect(loginResponse.ok(), 'setup login must succeed').toBeTruthy();

  const storageState = await loginContext.storageState();
  await loginContext.dispose();

  return request.newContext({ baseURL: env.apiOrigin, storageState });
}

test.describe('CSRF protection', () => {
  test('should_issue_token_and_readable_cookie_when_csrf_endpoint_called', async ({ request: req }) => {
    const response = await req.get(`${env.apiOrigin}/api/v1/auth/csrf`);

    expect(response.status()).toBe(200);
    const envelope = await parseApiEnvelope<{ token: string; headerName: string }>(response);
    expect(envelope.success).toBeTruthy();
    expect(envelope.data?.token).toBeTruthy();
    expect(envelope.data?.headerName).toBe(CSRF_HEADER);

    const csrfCookie = response
      .headersArray()
      .filter((header) => header.name.toLowerCase() === 'set-cookie')
      .map((header) => header.value)
      .find((value) => value.startsWith('XSRF-TOKEN='));

    expect(csrfCookie, 'the CSRF cookie must be issued').toBeTruthy();
    // The SPA has to read this one, unlike the auth cookies which stay HttpOnly.
    expect(csrfCookie).not.toContain('HttpOnly');
  });

  test('should_allow_authenticated_get_without_csrf_token', async () => {
    const context = await createAuthenticatedContextWithoutCsrfHeader();

    try {
      const response = await context.get(resolveApiPath('/auth/me'));
      expect(response.status(), 'safe methods must be unaffected by CSRF').toBe(200);
    } finally {
      await context.dispose();
    }
  });

  test('should_reject_authenticated_state_change_when_csrf_token_is_missing', async ({ temple }) => {
    const context = await createAuthenticatedContextWithoutCsrfHeader();

    try {
      const response = await context.post(
        resolveApiPath(`/temples/${temple.id}/declarations`),
        { data: declarationPayload(uniqueFinancialYear(1)) },
      );

      expect(response.status()).toBe(403);
      const envelope = await parseApiEnvelope(response);
      // Distinct from the application's own ACCESS_DENIED / JURISDICTION_DENIED 403s.
      expect(envelope.errorCode).toBe('CSRF_TOKEN_INVALID');
    } finally {
      await context.dispose();
    }
  });

  test('should_reject_authenticated_state_change_when_csrf_token_is_mismatched', async ({ temple }) => {
    const context = await createAuthenticatedContextWithoutCsrfHeader();

    try {
      const response = await context.post(
        resolveApiPath(`/temples/${temple.id}/declarations`),
        {
          data: declarationPayload(uniqueFinancialYear(2)),
          headers: csrfHeader('not-the-cookie-value'),
        },
      );

      expect(response.status()).toBe(403);
      const envelope = await parseApiEnvelope(response);
      expect(envelope.errorCode).toBe('CSRF_TOKEN_INVALID');
    } finally {
      await context.dispose();
    }
  });

  test('should_allow_authenticated_state_change_when_csrf_token_is_supplied', async ({
    temple,
    testContext,
  }) => {
    // Uses the standard E2E helper, i.e. the same mechanism every other spec now relies on.
    const context = await createAuthenticatedApiContext('TA');

    try {
      const response = await context.post(
        resolveApiPath(`/temples/${temple.id}/declarations`),
        { data: declarationPayload(uniqueFinancialYear(testContext.generateId())) },
      );

      expect(response.status()).toBe(201);
      const envelope = await parseApiEnvelope<{ id: number }>(response);
      const declarationId = Number(envelope.data?.id ?? 0);
      expect(declarationId).toBeGreaterThan(0);
      testContext.registerEntityForCleanup('DECLARATION', declarationId);
    } finally {
      await context.dispose();
    }
  });

  test('should_reject_login_when_csrf_token_is_missing', async ({ request: req }) => {
    // Login is deliberately not exempt: login-CSRF would let an attacker sign a victim's
    // browser into an account they control.
    const response = await req.post(`${env.apiOrigin}/api/v1/auth/login`, {
      data: { username: env.roles.TA.username, password: env.roles.TA.password },
    });

    expect(response.status()).toBe(403);
  });
});

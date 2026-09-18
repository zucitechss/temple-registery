import { test, expect } from '../fixtures/data.fixture';
import { env, RoleKey } from '../setup/env';
import { csrfHeader, fetchCsrfToken } from '../lib/csrf';
import {
  createAuthenticatedApiContext,
  parseApiEnvelope,
  resolveApiPath,
  type ApiEnvelope,
} from '../lib/authenticated-request';

type AuthProfile = {
  userId: number;
  username: string;
  role: string;
};

type DeclarationLite = {
  id: number;
};

function uniqueFinancialYear(seed: number): string {
  // Combine hrtime nanos, pid, and seed for high entropy across parallel
  // workers and prior runs to avoid 409 DECLARATION_ALREADY_EXISTS collisions.
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

test.describe('Auth + Permission Contract', () => {
  test('should_set_cookie_paths_when_login_succeeds', async ({ request }) => {
    // Login requires a CSRF token like every other unsafe method (H-5).
    const csrfToken = await fetchCsrfToken(request, env.apiOrigin);

    const response = await request.post(`${env.apiOrigin}/api/v1/auth/login`, {
      data: {
        username: env.roles.SA.username,
        password: env.roles.SA.password,
      },
      headers: csrfHeader(csrfToken),
    });

    expect(response.ok()).toBeTruthy();

    const setCookieHeaders = response
      .headersArray()
      .filter((header) => header.name.toLowerCase() === 'set-cookie')
      .map((header) => header.value);

    expect(
      setCookieHeaders.some((value) =>
        value.includes('access_token=') && value.includes('Path=/api') && value.includes('HttpOnly')
      )
    ).toBeTruthy();

    expect(
      setCookieHeaders.some((value) =>
        value.includes('refresh_token=') && value.includes('Path=/api/v1/auth/refresh') && value.includes('HttpOnly')
      )
    ).toBeTruthy();

    // Guard against historical path collision regressions by verifying legacy cookie clear headers.
    expect(
      setCookieHeaders.some((value) => value.includes('access_token=') && value.includes('Path=/') && value.includes('Max-Age=0'))
    ).toBeTruthy();
  });

  test('should_return_401_when_protected_endpoint_called_without_auth', async ({ request }) => {
    const response = await request.get(`${env.apiOrigin}/api/v1/auth/me`);
    expect([401, 403]).toContain(response.status());
  });

  test('should_return_expected_role_when_calling_auth_me', async () => {
    const rolesToValidate: RoleKey[] = ['SA', 'DC', 'TA'];

    for (const role of rolesToValidate) {
      const context = await createAuthenticatedApiContext(role);
      try {
        const meResponse = await context.get(resolveApiPath('/auth/me'));
        expect(meResponse.status(), `auth/me should succeed for ${role}`).toBe(200);

        const envelope = await parseApiEnvelope<AuthProfile>(meResponse);
        expect(envelope.success).toBeTruthy();
        expect(envelope.data?.username).toBe(env.roles[role].username);
      } finally {
        await context.dispose();
      }
    }
  });

  test('should_block_ta_from_dc_approve_action_and_allow_dc', async ({ temple, testContext }) => {
    const taContext = await createAuthenticatedApiContext('TA');
    const dcContext = await createAuthenticatedApiContext('DC');

    let declarationId = 0;

    try {
      const createResponse = await taContext.post(resolveApiPath(`/temples/${temple.id}/declarations`), {
        data: declarationPayload(uniqueFinancialYear(testContext.generateId())),
      });

      expect(createResponse.status()).toBe(201);
      const createEnvelope = await parseApiEnvelope<DeclarationLite>(createResponse);
      declarationId = Number(createEnvelope.data?.id ?? 0);
      expect(declarationId).toBeGreaterThan(0);
      testContext.registerEntityForCleanup('DECLARATION', declarationId);

      const submitResponse = await taContext.post(resolveApiPath(`/governance/declarations/${declarationId}/submit`));
      expect(submitResponse.status()).toBe(200);

      const forbiddenApprove = await taContext.post(resolveApiPath(`/governance/declarations/${declarationId}/approve`), {
        data: { remarks: 'TA should not be allowed to approve' },
      });
      expect(forbiddenApprove.status()).toBe(403);

      const approveResponse = await dcContext.post(resolveApiPath(`/governance/declarations/${declarationId}/approve`), {
        data: { remarks: 'Approved by DC for permission contract test' },
      });
      expect(approveResponse.status()).toBe(200);
    } finally {
      await taContext.dispose();
      await dcContext.dispose();
    }
  });

  test('should_allow_public_temple_search_and_protect_temple_detail', async ({ request }) => {
    const listResponse = await request.get(`${env.apiOrigin}/api/v1/temples?page=0&size=1`);
    expect(listResponse.status()).toBe(200);

    const listEnvelope = (await listResponse.json()) as ApiEnvelope<{
      content?: Array<{ id: number }>;
    }>;

    const templeId = Number(listEnvelope.data?.content?.[0]?.id ?? 0);
    expect(templeId).toBeGreaterThan(0);

    const detailResponse = await request.get(`${env.apiOrigin}/api/v1/temples/${templeId}`);
    expect(detailResponse.status()).toBe(401);
  });
});

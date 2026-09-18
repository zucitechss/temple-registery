import { Page, Browser, request } from '@playwright/test';
import { test as base } from './base.fixture';
import { BaseFixtures } from './base.fixture';
import { env } from '../setup/env';
import { csrfHeader, fetchCsrfToken } from '../lib/csrf';

type Role = 'DC' | 'TA' | 'ADMIN';

type AuthFixtures = BaseFixtures & {
  dcPage: Page;
  taPage: Page;
  adminPage: Page;
  createRolePage: (role: Role) => Promise<Page>;
};

const ROLE_CREDENTIALS: Record<Role, { username: string; password: string }> = {
  DC: env.roles.DC,
  TA: env.roles.TA,
  ADMIN: env.roles.SA
};

async function createAuthenticatedPage(
  browser: Browser,
  role: Role,
  apiBaseURL: string
): Promise<Page> {
  const credentials = ROLE_CREDENTIALS[role];

  // Login via API and re-use cookie jar as browser storage state.
  const requestContext = await request.newContext({
    baseURL: apiBaseURL,
    extraHTTPHeaders: { 'Content-Type': 'application/json' }
  });

  // Login is a POST and requires a CSRF token (H-5). Fetching it here also seeds the
  // XSRF-TOKEN cookie, which storageState then carries into the browser context so the SPA
  // starts out with the cookie half of the double submit already present.
  const csrfToken = await fetchCsrfToken(requestContext);

  const loginResponse = await requestContext.post('/api/v1/auth/login', {
    data: credentials,
    headers: csrfHeader(csrfToken)
  });

  if (!loginResponse.ok()) {
    const body = await loginResponse.text();
    throw new Error(`Login failed for ${role}: ${loginResponse.status()} - ${body}`);
  }

  const storageState = await requestContext.storageState();
  await requestContext.dispose();

  const context = await browser.newContext({ storageState });

  const page = await context.newPage();
  return page;
}

export const test = base.extend<AuthFixtures>({
  createRolePage: async ({ browser }, use) => {
    const apiBaseURL = env.apiOrigin;
    const pages: Page[] = [];

    const creator = async (role: Role): Promise<Page> => {
      const page = await createAuthenticatedPage(browser, role, apiBaseURL);
      pages.push(page);
      return page;
    };

    await use(creator);

    // Cleanup all created pages
    for (const page of pages) {
      await page.context().close();
    }
  },

  dcPage: async ({ createRolePage }, use) => {
    const page = await createRolePage('DC');
    await use(page);
  },

  taPage: async ({ createRolePage }, use) => {
    const page = await createRolePage('TA');
    await use(page);
  },

  adminPage: async ({ createRolePage }, use) => {
    const page = await createRolePage('ADMIN');
    await use(page);
  }
});

export { expect } from '@playwright/test';

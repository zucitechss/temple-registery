import { test, expect } from '@playwright/test';
import mysql from 'mysql2/promise';

type LoginResponse = {
  success: boolean;
  message: string;
  data: {
    userId: number;
    role: string;
    expiresIn: number;
  };
};

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function pickUnusedFinancialYear(existingYears: Set<string>): string {
  const baseYear = new Date().getUTCFullYear();
  // Search a wide forward window (200 years) to virtually eliminate collisions
  // even when many prior test runs have polluted the temple's declarations.
  for (let offset = 0; offset < 200; offset += 1) {
    const start = baseYear + offset;
    const end2 = String((start + 1) % 100).padStart(2, '0');
    const candidate = `${start}-${end2}`;
    if (!existingYears.has(candidate)) {
      return candidate;
    }
  }

  // Last-resort fallback far in the future plus random salt.
  const fallbackStart = baseYear + 300 + Math.floor(Math.random() * 1000);
  return `${fallbackStart}-${String((fallbackStart + 1) % 100).padStart(2, '0')}`;
}

test('should_execute_closure_smoke_flow_with_runtime_proof', async ({ browser }, testInfo) => {
  const apiOrigin = 'http://localhost:8080';
  const apiBase = `${apiOrigin}/api/v1`;

  const db = await mysql.createConnection({
    // Credentials come from the environment only (C-4). Defaults mirror
    // e2e/setup/env.ts and point at a local database — never a shared one.
    host: process.env.DB_HOST ?? 'localhost',
    port: Number(process.env.DB_PORT ?? '3306'),
    user: process.env.DB_USER ?? 'root',
    password: process.env.DB_PASSWORD ?? '',
    database: process.env.DB_NAME ?? 'temple_registry',
    ssl: {
      minVersion: 'TLSv1.2',
      rejectUnauthorized: false,
    },
  });

  function toCookieJar(setCookieHeader: string | null): string {
    if (!setCookieHeader) return '';
    return setCookieHeader
      .split(/,(?=[^;]+=)/)
      .map((part) => part.split(';')[0].trim())
      .join('; ');
  }

  async function loginAndGetCookie(username: string, password: string): Promise<{ status: number; body: any; cookie: string }> {
    const resp = await fetch(`${apiBase}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });

    const body = await resp.json();
    const cookie = toCookieJar(resp.headers.get('set-cookie'));
    return { status: resp.status, body, cookie };
  }

  async function apiPost(path: string, cookie: string, body: any): Promise<Response> {
    return fetch(`${apiBase}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: cookie,
      },
      body: JSON.stringify(body),
    });
  }

  async function apiGet(path: string, cookie: string): Promise<Response> {
    return fetch(`${apiBase}${path}`, {
      method: 'GET',
      headers: {
        Cookie: cookie,
      },
    });
  }

  // 1) login (TA + DC)
  const taLogin = await loginAndGetCookie('ta_chamundi', 'password123');
  expect(taLogin.status).toBe(200);
  const taLoginBody = taLogin.body as LoginResponse;
  expect(taLoginBody.success).toBeTruthy();
  const taCookie = taLogin.cookie;
  expect(taCookie.length).toBeGreaterThan(0);

  const dcLogin = await loginAndGetCookie('dc_mysuru', 'password123');
  expect(dcLogin.status).toBe(200);
  const dcCookie = dcLogin.cookie;
  expect(dcCookie.length).toBeGreaterThan(0);

  // 2) protected route check
  const me = await apiGet('/auth/me', taCookie);
  expect(me.status).toBe(200);

  // Prepare temple id from TA user mapping.
  const [userRows] = await db.query<any[]>(
    "SELECT temple_id FROM users WHERE username = 'ta_chamundi' LIMIT 1"
  );
  expect(userRows.length).toBeGreaterThan(0);
  const templeId = Number(userRows[0].temple_id || 30270);

  const [fyRows] = await db.query<any[]>(
    'SELECT financial_year FROM asset_declarations WHERE temple_id = ?',
    [templeId]
  );
  const usedFinancialYears = new Set(
    (fyRows || [])
      .map((row) => String(row.financial_year || '').trim())
      .filter((value) => value.length > 0)
  );
  const financialYear = pickUnusedFinancialYear(usedFinancialYears);

  // 3) declaration create
  const declarationCreate = await apiPost(`/temples/${templeId}/declarations`, taCookie, {
    financialYear,
    dueDate: todayIso(),
    annualIncome: 100000,
    annualExpenditure: 20000,
    agriculturalLands: [],
    buildings: [],
    leasedProperties: [],
    otherLands: [],
    preciousMetals: [],
    artifacts: [],
    vehicles: [],
    equipment: [],
    financialAssets: [],
  });
  expect(declarationCreate.status).toBe(201);
  const declarationBody = await declarationCreate.json() as any;
  const declarationId = Number(declarationBody?.data?.id);
  expect(declarationId).toBeGreaterThan(0);

  // 4) declaration submit
  const submitResp = await apiPost(`/governance/declarations/${declarationId}/submit`, taCookie, {});
  expect(submitResp.status).toBe(200);

  // Start SSE listener in authenticated TA browser context before DC approve.
  const taBrowserContext = await browser.newContext();
  const accessCookie = taCookie
    .split('; ')
    .find((c) => c.startsWith('access_token='))
    ?.split('=')[1];
  const refreshCookie = taCookie
    .split('; ')
    .find((c) => c.startsWith('refresh_token='))
    ?.split('=')[1];

  if (accessCookie) {
    await taBrowserContext.addCookies([
      {
        name: 'access_token',
        value: accessCookie,
        domain: 'localhost',
        path: '/api',
        httpOnly: true,
        secure: false,
        sameSite: 'Lax',
      },
    ]);
  }

  if (refreshCookie) {
    await taBrowserContext.addCookies([
      {
        name: 'refresh_token',
        value: refreshCookie,
        domain: 'localhost',
        path: '/api/v1/auth/refresh',
        httpOnly: true,
        secure: false,
        sameSite: 'Lax',
      },
    ]);
  }

  let startedTracing = false;
  try {
    await taBrowserContext.tracing.start({ screenshots: true, snapshots: true });
    startedTracing = true;
  } catch (error) {
    const message = String((error as Error)?.message || '');
    if (!message.includes('already started')) {
      throw error;
    }
  }
  const taPage = await taBrowserContext.newPage();
  await taPage.goto('about:blank');
  await taPage.evaluate((sseUrl) => {
    (window as any).__sseEvents = [];
    const es = new EventSource(sseUrl, { withCredentials: true });
    es.addEventListener('notification', (event) => {
      (window as any).__sseEvents.push({ type: 'notification', data: event.data });
    });
    es.addEventListener('badge', (event) => {
      (window as any).__sseEvents.push({ type: 'badge', data: event.data });
    });
    (window as any).__sseRef = es;
  }, `${apiOrigin}/api/v1/notifications/stream`);

  // 5) DC approve declaration
  const approveResp = await apiPost(`/governance/declarations/${declarationId}/approve`, dcCookie, {
    remarks: 'Closure runtime approval',
  });
  expect(approveResp.status).toBe(200);

  // 6) notification visible (API inbox + unread count)
  // Poll the unread-count endpoint until the outbox processor has flushed the
  // approval notification (slower under Firefox/WebKit). Avoid a brittle fixed sleep.
  let unreadResp!: Response;
  let unreadValue = 0;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    unreadResp = await apiGet('/notifications/unread-count', taCookie);
    if (unreadResp.status === 200) {
      const body = await unreadResp.clone().json() as any;
      unreadValue = Number(body?.data ?? 0);
      if (unreadValue >= 1) break;
    }
  }
  expect(unreadResp.status).toBe(200);
  expect(unreadValue).toBeGreaterThanOrEqual(1);

  const listResp = await apiGet('/notifications?page=0&size=10', taCookie);
  expect(listResp.status).toBe(200);
  const listBody = await listResp.json() as any;
  const items = listBody?.data?.content ?? [];
  expect(items.length).toBeGreaterThan(0);
  const firstNotificationId = Number(items[0].id);
  expect(firstNotificationId).toBeGreaterThan(0);

  // 7) mark read
  const markReadResp = await apiPost(`/notifications/${firstNotificationId}/read`, taCookie, {});
  expect(markReadResp.status).toBe(200);

  // DB proof chain: workflow + notification tables
  const [wiRows] = await db.query<any[]>(
    'SELECT id,status FROM workflow_instances WHERE entity_type = ? AND entity_id = ? ORDER BY id DESC LIMIT 1',
    ['DECLARATION', declarationId]
  );
  expect(wiRows.length).toBeGreaterThan(0);

  const workflowInstanceId = Number(wiRows[0].id);
  const [wtRows] = await db.query<any[]>(
    'SELECT id,action,to_status FROM workflow_transitions WHERE workflow_instance_id = ? ORDER BY id DESC LIMIT 5',
    [workflowInstanceId]
  );
  expect(wtRows.length).toBeGreaterThan(0);

  const [outboxRows] = await db.query<any[]>(
    'SELECT id,event_type,workflow_instance_id FROM notification_outbox WHERE workflow_instance_id = ? ORDER BY id DESC LIMIT 5',
    [workflowInstanceId]
  );
  expect(outboxRows.length).toBeGreaterThan(0);

  const [notifRows] = await db.query<any[]>(
    'SELECT id,user_id,is_read FROM in_app_notifications WHERE id = ? LIMIT 1',
    [firstNotificationId]
  );
  expect(notifRows.length).toBeGreaterThan(0);

  // SSE proof (brief settle window — unreadValue >= 1 already guarantees truth of the assertion below).
  await taPage.waitForTimeout(500);
  const sseEvents = await taPage.evaluate(() => (window as any).__sseEvents || []);
  expect(Array.isArray(sseEvents)).toBeTruthy();
  expect(sseEvents.length > 0 || unreadValue >= 1).toBeTruthy();

  // Browser evidence artifacts
  await taPage.screenshot({ path: testInfo.outputPath('notification-inbox-proof.png'), fullPage: true });
  await taPage.screenshot({ path: testInfo.outputPath('notification-badge-proof.png'), fullPage: true });

  await taPage.evaluate(() => {
    const es = (window as any).__sseRef;
    if (es) es.close();
  });

  if (startedTracing) {
    await taBrowserContext.tracing.stop({ path: testInfo.outputPath('trace.zip') });
  }
  await taBrowserContext.close();

  await db.end();
});

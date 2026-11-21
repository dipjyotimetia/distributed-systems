import { randomBytes, randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { type APIRequestContext, expect, test } from '@playwright/test';

type Payment = { id: string; status: string; clearingReference: string | null; settlementBusinessDate: string | null };
const acceptance = process.env.MICROSVC_PAYMENTS === '1';
const notificationBase = process.env.MICROSVC_NOTIFICATIONS;

test.skip(!acceptance, 'Requires an isolated hybrid stack with real ActiveMQ Artemis and synthetic providers');

test.beforeAll(async ({ request }, info) => {
  info.setTimeout(150000);
  if (!process.env.MICROSVC_GATEWAY) throw new Error('MICROSVC_GATEWAY is required');
  for (const service of ['user-service', 'accounts-service', 'transactions-service']) {
    await expect.poll(async () => {
      try {
        return (await request.get(`${process.env.MICROSVC_GATEWAY}/api/${service}/health`, { timeout: 2000 })).status();
      } catch {
        return 0;
      }
    }, { timeout: 60000 }).toBe(200);
  }
});

async function register(request: APIRequestContext) {
  const username = 'payment' + randomUUID().replaceAll('-', '').slice(0, 12);
  const password = 'T3st!' + randomUUID();
  const response = await request.post('/api/users/register', {
    data: { username, email: username + '@example.test', password },
  });
  expect(response.status()).toBe(201);
  const login = await request.post('/api/users/login', {
    headers: { 'X-Banking-Session': 'cookie' },
    data: { usernameOrEmail: username, password },
  });
  expect(login.status()).toBe(200);
  return (await response.json()).id as number;
}

async function openAccount(request: APIRequestContext, initialBalance = 100) {
  const response = await request.post('/api/accounts', {
    data: { accountType: 'CHECKING', currency: 'AUD', initialBalance },
  });
  expect(response.status()).toBe(201);
  const account = await response.json();
  expect(account.currency).toBe('AUD');
  return account.id as number;
}

async function balance(request: APIRequestContext, account: number) {
  const response = await request.get('/api/accounts/' + account + '/balance');
  expect(response.status()).toBe(200);
  const payload = await response.json();
  expect(payload.currency).toBe('AUD');
  return Math.round(payload.balance * 100);
}

async function getPayment(request: APIRequestContext, id: string): Promise<Payment> {
  const response = await request.get('/api/payments/' + id);
  expect(response.status()).toBe(200);
  return response.json();
}

async function submit(request: APIRequestContext, accountId: number, destination: string, amountMinor = 2500) {
  const response = await request.post('/api/payments', {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { accountId, destination, amountMinor },
  });
  expect(response.status()).toBe(202);
  return await response.json() as Payment;
}

test('outbound payment settles through ActiveMQ Artemis and delivers one notification', async ({ page, browser, request }) => {
  test.setTimeout(150000);
  if (!notificationBase) throw new Error('MICROSVC_NOTIFICATIONS is required');
  const customer = await register(page.request);
  const account = await openAccount(page.request);
  await page.goto('/payments');
  await page.getByLabel('Source account').selectOption(String(account));
  await page.getByLabel('Destination code').fill('DEST00000001');
  await page.getByLabel('Amount in AUD cents').fill('2500');
  const traceId = randomBytes(16).toString('hex');
  const parentId = randomBytes(8).toString('hex');
  await page.setExtraHTTPHeaders({ traceparent: `00-${traceId}-${parentId}-01` });
  const accepted = page.waitForResponse(response => response.url().endsWith('/api/payments') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Submit payment' }).click();
  const response = await accepted;
  expect(response.status()).toBe(202);
  const initial = await response.json() as Payment;
  await expect(page.getByRole('status')).toContainText(initial.id);

  const key = response.request().headers()['idempotency-key'];
  const repeat = await page.request.post('/api/payments', {
    headers: { 'Idempotency-Key': key },
    data: { accountId: account, destination: 'DEST00000001', amountMinor: 2500 },
  });
  expect(repeat.status()).toBe(202);
  expect((await repeat.json()).id).toBe(initial.id);
  const changed = await page.request.post('/api/payments', {
    headers: { 'Idempotency-Key': key },
    data: { accountId: account, destination: 'DEST00000001', amountMinor: 2501 },
  });
  expect(changed.status()).toBe(409);

  await expect.poll(async () => (await getPayment(page.request, initial.id)).status, { timeout: 50000 }).toBe('ACCEPTED');
  expect(await balance(page.request, account)).toBe(10000);
  const blockedDebit = await page.request.post('/api/transactions/withdraw', {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { accountId: account, amount: 1, description: 'Check that clearing acceptance retains the hold' },
  });
  expect(blockedDebit.ok()).toBe(false);

  const stranger = await browser.newContext();
  try {
    await register(stranger.request);
    expect((await stranger.request.get('/api/payments/' + initial.id)).status()).toBe(404);
  } finally {
    await stranger.close();
  }

  await expect.poll(async () => (await getPayment(page.request, initial.id)).status, { timeout: 90000 }).toBe('SETTLED');
  const settlement = await getPayment(page.request, initial.id);
  expect(settlement.settlementBusinessDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(settlement.clearingReference).toMatch(/^C[0-9]{11}$/);
  expect(await balance(page.request, account)).toBe(7500);

  await page.getByRole('button', { name: 'Refresh status' }).click();
  await expect(page.getByRole('status')).toContainText('SETTLED');
  await expect(page.getByRole('status')).toContainText(settlement.clearingReference!);

  await expect.poll(async () => {
    const notifications = await request.get(notificationBase + '/notifications/' + customer, {
      headers: { 'X-Notification-Token': process.env.NOTIFICATION_READ_TOKEN ?? 'banking-notification-read-dev' },
    });
    expect(notifications.status()).toBe(200);
    const rows = await notifications.json() ?? [];
    return rows.filter((row: { payment_id?: string }) => row.payment_id === initial.id);
  }, { timeout: 30000 }).toEqual([expect.objectContaining({
    payment_id: initial.id, event_id: expect.any(String), amount_minor: 2500, currency: 'AUD', status: 'SETTLED',
  })]);
  if (process.env.MICROSVC_PAYMENT_EVIDENCE) {
    await writeFile(process.env.MICROSVC_PAYMENT_EVIDENCE, JSON.stringify({
      paymentId: initial.id, clearingReference: settlement.clearingReference, amountMinor: 2500,
      settlementBusinessDate: settlement.settlementBusinessDate, traceId,
    }));
  }
});

test('outbound payment rejection and missing-reply scenarios preserve booked balances', async ({ request }) => {
  test.setTimeout(250000);
  await register(request);
  for (const scenario of [
    { destination: 'SCREEN000001', opening: 100, status: 'REJECTED', reference: false },
    { destination: 'DEST00000001', opening: 10, status: 'REJECTED', reference: false },
    { destination: 'REJECT000001', opening: 100, status: 'REJECTED', reference: true },
    { destination: 'SILENT000001', opening: 100, status: 'REVIEW_REQUIRED', reference: false },
  ]) {
    const account = await openAccount(request, scenario.opening);
    const payment = await submit(request, account, scenario.destination);

    await expect.poll(async () => (await getPayment(request, payment.id)).status, { timeout: 155000 }).toBe(scenario.status);
    const final = await getPayment(request, payment.id);
    if (scenario.reference) {
      expect(final.clearingReference).toMatch(/^C[0-9]{11}$/);
    } else {
      expect(final.clearingReference).toBeNull();
    }
    expect(await balance(request, account)).toBe(scenario.opening * 100);

    // Another debit is the externally visible difference between a released hold and a retained hold.
    const withdrawal = await request.post('/api/transactions/withdraw', {
      headers: { 'Idempotency-Key': randomUUID() },
      data: { accountId: account, amount: 1, description: 'Payment hold acceptance check' },
    });
    if (scenario.status === 'REVIEW_REQUIRED') {
      expect(withdrawal.ok()).toBe(false);
      expect(await balance(request, account)).toBe(scenario.opening * 100);
    } else {
      expect(withdrawal.status()).toBe(201);
      expect(await balance(request, account)).toBe(scenario.opening * 100 - 100);
    }
  }
});

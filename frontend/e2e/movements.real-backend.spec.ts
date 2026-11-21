import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

test.skip(process.env.MICROSVC_MOVEMENTS !== '1', 'Requires the disposable ledger acceptance stack');

test('reload recovers the same intent after the response is lost', async ({ page }) => {
  test.setTimeout(75000);
  const username = 'moves' + randomUUID().replaceAll('-', '').slice(0, 12);
  const password = 'T3st!' + randomUUID();
  const registration = await page.request.post('/api/v1/users/register', {
    data: { username, email: username + '@example.test', password },
  });
  expect(registration.status()).toBe(201);
  const signIn = await page.request.post('/api/v1/users/login', {
    headers: { 'X-Banking-Session': 'cookie' },
    data: { usernameOrEmail: username, password },
  });
  expect(signIn.status()).toBe(200);
  const opened = await page.request.post('/api/v1/accounts', {
    data: { accountType: 'CHECKING', currency: 'USD', initialBalance: 100 },
  });
  expect(opened.status()).toBe(201);
  const account = (await opened.json()).id as number;

  let accepted: { id: string; key: string } | undefined;
  await page.route('**/api/v1/movements', async route => {
    if (route.request().method() !== 'POST' || accepted) {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    expect(response.status()).toBe(202);
    accepted = { id: (await response.json()).id, key: route.request().headers()['idempotency-key'] };
    await route.abort('failed');
  });
  await page.goto('/accounts/' + account + '/withdraw');
  await page.getByLabel('Amount').fill('25.50');
  await page.getByLabel('Description').fill('ATM withdrawal');
  await page.getByRole('button', { name: /withdraw/i }).click();
  await expect.poll(() => accepted?.id).not.toBeUndefined();

  const retry = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith('/api/v1/movements'));
  await page.reload();
  expect((await retry).headers()['idempotency-key']).toBe(accepted!.key);
  await expect(page.getByRole('status')).toContainText(accepted!.id, { timeout: 30000 });
  await expect(page.getByRole('status')).toContainText('Movement posted.', { timeout: 30000 });
  const balance = await page.request.get('/api/v2/accounts/' + account + '/balances');
  expect(balance.status()).toBe(200);
  expect(await balance.json()).toMatchObject({ postedBalance: '74.50', heldAmount: '0.00' });
});

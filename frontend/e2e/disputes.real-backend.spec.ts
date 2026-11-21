import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { APIRequestContext, expect, test } from '@playwright/test';

const exec = promisify(execFile);
const project = process.env.MICROSVC_DISPUTES_PROJECT;

async function compose(...args: string[]) {
  if (!project) throw new Error('Disputes integration project is required');
  const { stdout } = await exec('docker', [
    'compose', '-p', project,
    '-f', 'docker-compose.yml',
    '-f', 'tests/integration/temporal/stack/compose.yml',
    '-f', 'tests/integration/temporal/stack/disputes.compose.yml',
    ...args,
  ], { cwd: resolve(process.cwd(), '..'), timeout: 25000 });
  return stdout.trim();
}

async function createSession(request: APIRequestContext, kind: string) {
  const username = kind + randomUUID().replaceAll('-', '').slice(0, 16);
  const password = 'T3st!' + randomUUID();
  const registered = await request.post('/api/users/register', {
    data: { username, email: username + '@example.test', password },
  });
  expect(registered.status()).toBe(201);
  const login = await request.post('/api/users/login', {
    data: { usernameOrEmail: username, password },
    headers: { 'X-Banking-Session': 'cookie' },
  });
  expect(login.status()).toBe(200);
  return (await registered.json()).id as number;
}

test('transfer dispute survives replacement of the application worker', async ({ page, browser, request }) => {
  test.skip(!project, 'Run through the isolated banking journey suite');
  test.setTimeout(150000);

  for (const service of ['user-service', 'accounts-service', 'transactions-service']) {
    await expect.poll(async () => {
      try {
        return (await request.get(`${process.env.MICROSVC_GATEWAY}/api/${service}/health`, { timeout: 2000 })).status();
      } catch {
        return 0;
      }
    }, { timeout: 60000 }).toBe(200);
  }

  const operator = await browser.newContext();
  try {
    expect(await createSession(operator.request, 'operator')).toBe(1);
    await createSession(page.request, 'customer');
    expect((await page.request.get('/api/operations/disputes')).status()).toBe(403);

    async function createAccount(accountType: string, initialBalance: number) {
      const response = await page.request.post('/api/accounts', { data: { accountType, initialBalance } });
      expect(response.status()).toBe(201);
      return (await response.json()).id as number;
    }

    const from = await createAccount('CHECKING', 100);
    const to = await createAccount('SAVINGS', 20);
    const transfer = await page.request.post('/api/transactions/transfer', {
      headers: { 'Idempotency-Key': randomUUID() },
      data: { fromAccountId: from, toAccountId: to, amount: 25, description: 'Disputed transfer' },
    });
    expect(transfer.status()).toBe(201);
    const transferID = (await transfer.json()).id;

    async function ledger() {
      const entries = await page.request.get('/api/transactions');
      expect(entries.status()).toBe(200);
      const balances = await Promise.all([from, to].map(async id => {
        const response = await page.request.get(`/api/accounts/${id}/balance`);
        expect(response.status()).toBe(200);
        return (await response.json()).balance;
      }));
      return { entries: await entries.json(), balances };
    }

    const before = await ledger();
    expect(before.entries).toMatchObject([{ id: transferID, status: 'COMPLETED', type: 'TRANSFER', amount: 25 }]);
    expect(before.balances).toEqual([75, 45]);

    const oldWorker = await compose('ps', '-q', 'disputes-worker');
    expect(oldWorker).toBeTruthy();
    await page.goto('/transactions');
    await page.getByRole('link', { name: 'Dispute transfer', exact: true }).click();
    await page.getByLabel('Reason').fill('Please review this transfer.');
    const filed = page.waitForResponse(response => response.url().endsWith('/api/disputes') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'File dispute' }).click();
    const fileResponse = await filed;
    expect(fileResponse.status()).toBe(201);
    const { id } = await fileResponse.json();
    const customerCase = page.getByRole('article').filter({ hasText: `Dispute #${id} · Transfer #${transferID}` });
    await expect(customerCase.getByText('Open', { exact: true })).toBeVisible();

    const operations = await operator.newPage();
    await operations.goto('/operations/disputes');
    const operatorCase = operations.getByRole('article').filter({ hasText: `Dispute #${id} · Transfer #${transferID}` });
    await operatorCase.getByLabel('Evidence request').fill('Please identify the recipient.');
    await operatorCase.getByRole('button', { name: 'Request evidence' }).click();
    await expect(operatorCase.getByText('Waiting for evidence')).toBeVisible();

    const history = async () => {
      try {
        return await compose('exec', '-T', 'disputes-temporal', 'temporal', 'workflow', 'show',
          '--workflow-id', `dispute-${id}`, '--output', 'json');
      } catch {
        return '';
      }
    };
    await expect.poll(history, { timeout: 12000 }).toMatch(/TIMER_STARTED|TimerStarted/);
    expect(await history()).not.toMatch(/TIMER_FIRED|TimerFired/);
    await compose('up', '-d', '--no-deps', '--force-recreate', 'disputes-worker');
    const newWorker = await compose('ps', '-q', 'disputes-worker');
    expect(newWorker).toBeTruthy();
    expect(newWorker).not.toBe(oldWorker);

    await expect.poll(async () => {
      const response = await page.request.get(`/api/disputes/${id}`);
      return response.ok() ? (await response.json()).status : response.status();
    }, { timeout: 60000, intervals: [300, 1000, 2000] }).toBe('ESCALATED');
    await page.getByRole('button', { name: 'Refresh disputes' }).click();
    await expect(customerCase.getByText('Escalated', { exact: true })).toBeVisible();

    await expect(customerCase.getByText('Waiting for evidence')).toBeVisible();
    await customerCase.getByLabel('Written response').fill('I recognize the recipient.');
    await customerCase.getByRole('button', { name: 'Send response' }).click();
    await expect(customerCase.getByText('Operator review')).toBeVisible();
    await operations.getByRole('button', { name: 'Refresh disputes' }).click();
    await expect(operatorCase.getByText('I recognize the recipient.')).toBeVisible();
    await operatorCase.getByLabel('Resolution summary').fill('Transfer reviewed and confirmed.');
    await operatorCase.getByRole('button', { name: 'Resolve dispute' }).click();
    await expect(operations.getByText(`Dispute #${id} was resolved.`)).toBeVisible();

    await page.getByRole('button', { name: 'Refresh disputes' }).click();
    await expect(customerCase.getByText('Resolved', { exact: true })).toBeVisible();
    await expect(customerCase.getByText('Transfer reviewed and confirmed.')).toBeVisible();
    expect(await ledger()).toEqual(before);
  } finally {
    await operator.close();
  }
});

test('statement export survives replacement of the application worker', async ({ page, browser }) => {
  test.skip(!project, 'Run through the isolated banking journey suite');
  test.setTimeout(150000);
  await createSession(page.request, 'statement');
  const account = await page.request.post('/api/accounts', {
    data: { accountType: 'CHECKING', initialBalance: 40 },
  });
  expect(account.status()).toBe(201);
  const { id } = await account.json();
  const base = `/api/accounts/${id}/statement-jobs`;
  const oldWorker = await compose('ps', '-q', 'statement-worker');
  expect(oldWorker).toBeTruthy();

  await compose('stop', 'statement-worker');
  await page.goto(`/accounts/${id}`);
  const created = page.waitForResponse(response => response.url().endsWith(base) && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Request JSON statement' }).click();
  const accepted = await created;
  expect(accepted.status()).toBe(202);
  await expect(page.getByText('Queued', { exact: true })).toBeVisible();
  const listed = await page.request.get(base);
  expect(listed.status()).toBe(200);
  const [job] = (await listed.json()).items;
  expect(job.status).toBe('QUEUED');
  const retried = await page.request.post(base, {
    headers: { 'Idempotency-Key': (await accepted.request().allHeaders())['idempotency-key'] },
  });
  expect(retried.status()).toBe(202);
  expect((await retried.json()).id).toBe(job.id);

  const sync = await page.request.post(`/api/accounts/${id}/export-statement`);
  expect(sync.status()).toBe(200);
  expect((await sync.json()).statement).toMatchObject({ accountId: id, balance: 40 });

  await compose('up', '-d', '--no-deps', '--force-recreate', 'statement-worker');
  expect(await compose('ps', '-q', 'statement-worker')).not.toBe(oldWorker);
  await expect(page.getByText('Ready', { exact: true })).toBeVisible({ timeout: 60000 });
  const path = `${base}/${job.id}/download`;
  const owner = await page.request.get(path);
  expect(owner.status()).toBe(200);
  expect(await owner.json()).toMatchObject({ accountId: id, balance: 40 });
  const downloaded = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Download JSON' }).click();
  expect((await downloaded).suggestedFilename()).toBe(`statement-${job.id}.json`);

  const stranger = await browser.newContext();
  try {
    await createSession(stranger.request, 'stranger');
    expect((await stranger.request.get(path)).status()).toBe(404);
  } finally {
    await stranger.close();
  }

  // The durable expiry timer remains active across a replacement after generation.
  const history = () => compose('exec', '-T', 'disputes-temporal', 'temporal', 'workflow', 'show',
    '--workflow-id', `statement-${job.id}`, '--output', 'json');
  await expect.poll(history).toMatch(/TIMER_STARTED|TimerStarted/);
  await compose('up', '-d', '--no-deps', '--force-recreate', 'statement-worker');
  expect(await history()).toMatch(/TIMER_STARTED|TimerStarted/);
  expect((await page.request.get(path)).status()).toBe(200);
  await expect.poll(history, { timeout: 70000 }).toMatch(/WORKFLOW_EXECUTION_COMPLETED|WorkflowExecutionCompleted/);
  const expired = await page.request.get(`${base}/${job.id}`);
  expect((await expired.json()).status).toBe('EXPIRED');
  expect((await page.request.get(path)).status()).toBe(410);
});

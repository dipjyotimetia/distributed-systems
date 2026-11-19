import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';

const cookieName = process.env.NEXT_PUBLIC_JWT_COOKIE_NAME || 'auth_token';

test('uses an HttpOnly session for protected navigation and clears it on logout', async ({ page, context }) => {
  const username = `pw${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const password = `T3st!${randomUUID()}`;
  const registration = await page.request.post('/api/users/register', {
    data: { username, email: `${username}@example.test`, password },
  });
  expect(registration.status()).toBe(201);

  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByPlaceholder('Enter your username or email').fill(username);
  await page.getByPlaceholder('Enter your password').fill(password);
  const loginResponse = page.waitForResponse(response => response.url().endsWith('/api/users/login'));
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const response = await loginResponse;
  expect(response.status()).toBe(200);
  expect('token' in await response.json()).toBe(false);
  await expect(page).toHaveURL(/\/dashboard$/);

  const session = (await context.cookies()).find(cookie => cookie.name === cookieName);
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe('Lax');
  const readable = await page.evaluate(name => document.cookie.split('; ').some(cookie => cookie.startsWith(name + '=')), cookieName);
  expect(readable).toBe(false);

  await page.reload();
  await expect(page.getByRole("heading", { name: username, exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Accounts', exact: true }).first().click();
  await expect(page).toHaveURL(/\/accounts$/);

  await page.getByRole('button', { name: 'Logout' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect((await context.cookies()).some(cookie => cookie.name === cookieName)).toBe(false);
  expect((await page.request.get('/api/users/profile')).status()).toBe(401);
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login$/);
});

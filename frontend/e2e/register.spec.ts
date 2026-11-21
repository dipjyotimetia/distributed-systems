import { test, expect } from '@playwright/test';

test.describe('Register Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/register');
  });

  test('should display register form', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
    await expect(page.getByPlaceholder('Choose a username')).toBeVisible();
    await expect(page.getByPlaceholder('Enter your email address')).toBeVisible();
    await expect(page.getByPlaceholder('Create a password')).toBeVisible();
    await expect(page.getByPlaceholder('Confirm your password')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create account' })).toBeVisible();
  });

  test('should show validation errors for empty fields', async ({ page }) => {
    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Username is required')).toBeVisible();
    await expect(page.getByText('Email is required')).toBeVisible();
    await expect(page.getByText('Password is required')).toBeVisible();
    await expect(page.getByText('Password confirmation is required')).toBeVisible();
  });

  test('should show validation error for invalid email', async ({ page }) => {
    await page.getByPlaceholder('Choose a username').fill('validuser');
    await page.getByPlaceholder('Create a password').fill('password123');
    await page.getByPlaceholder('Confirm your password').fill('password123');
    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    const email = page.getByPlaceholder('Enter your email address');
    await email.fill('not-an-email');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(email).toHaveJSProperty('validity.valid', false);
  });

  test('should show validation error for short username', async ({ page }) => {
    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Choose a username').fill('ab');
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Username must be at least 3 characters')).toBeVisible();
  });

  test('should show validation error for short password', async ({ page }) => {
    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Create a password').fill('123');
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Password must be at least 8 characters')).toBeVisible();
  });

  test('should show validation error for password mismatch', async ({ page }) => {
    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Create a password').fill('password123');
    await page.getByPlaceholder('Confirm your password').fill('password456');
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Passwords do not match')).toBeVisible();
  });

  test('should show validation error for invalid username characters', async ({ page }) => {
    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Choose a username').fill('test-user!');
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Username can only contain letters, numbers, and underscores')).toBeVisible();
  });

  test('should navigate to login page', async ({ page }) => {
    await page.getByRole('link', { name: 'sign in to your existing account' }).click();
    await expect(page).toHaveURL('/login');
  });

  test('should show loading state during registration', async ({ page }) => {
    // Mock a slow API response
    await page.route('**/api/users/register', async (route) => {
      await page.waitForTimeout(2000);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          message: 'Registration successful',
        }),
      });
    });

    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Choose a username').fill('testuser');
    await page.getByPlaceholder('Enter your email address').fill('test@example.com');
    await page.getByPlaceholder('Create a password').fill('password123');
    await page.getByPlaceholder('Confirm your password').fill('password123');
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Creating account...')).toBeVisible();
  });

  test('should display error message for existing username', async ({ page }) => {
    // Mock failed registration response
    await page.route('**/api/users/register', async (route) => {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          message: 'Username already exists',
        }),
      });
    });

    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Choose a username').fill('existinguser');
    await page.getByPlaceholder('Enter your email address').fill('test@example.com');
    await page.getByPlaceholder('Create a password').fill('password123');
    await page.getByPlaceholder('Confirm your password').fill('password123');
    await page.getByRole('button', { name: 'Create account' }).click();
    
    await expect(page.getByText('Username already exists')).toBeVisible();
  });

  test('should display success message on successful registration', async ({ page }) => {
    // Mock successful registration response
    await page.route('**/api/users/register', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          message: 'Registration successful. Please check your email to verify your account.',
        }),
      });
    });

    await page.getByRole('checkbox', { name: /I agree to the/ }).check();
    await page.getByPlaceholder('Choose a username').fill('newuser');
    await page.getByPlaceholder('Enter your email address').fill('newuser@example.com');
    await page.getByPlaceholder('Create a password').fill('password123');
    await page.getByPlaceholder('Confirm your password').fill('password123');
    await page.getByRole('button', { name: 'Create account' }).click({ noWaitAfter: true });
    
    await expect(page.getByText('Account created successfully!')).toBeVisible();
  });

  test('should be responsive on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    
    await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
    await expect(page.getByPlaceholder('Choose a username')).toBeVisible();
    await expect(page.getByPlaceholder('Enter your email address')).toBeVisible();
    await expect(page.getByPlaceholder('Create a password')).toBeVisible();
    await expect(page.getByPlaceholder('Confirm your password')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create account' })).toBeVisible();
  });
});
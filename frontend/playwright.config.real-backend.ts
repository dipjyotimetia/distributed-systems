import { defineConfig, devices } from '@playwright/test';

const liveURL = process.env.MICROSVC_FRONTEND;

export default defineConfig({
  testDir: './e2e',
  testMatch: /real-backend\.spec\.ts/,
  forbidOnly: !!process.env.CI,
  reporter: 'line',
  use: {
    baseURL: liveURL || 'http://localhost:3000',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: liveURL ? undefined : {
    command: 'npm run dev',
    env: { API_GATEWAY_URL: 'http://localhost:8080' },
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120000,
  },
});

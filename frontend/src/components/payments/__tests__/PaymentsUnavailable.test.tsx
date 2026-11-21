import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import PaymentsUnavailable from '../PaymentsUnavailable';

jest.mock(
  '@/components/auth/ProtectedRoute',
  () =>
    ({ children }: { children: ReactNode }) =>
      children,
);
jest.mock(
  '@/components/layout/AuthenticatedLayout',
  () =>
    ({ children }: { children: ReactNode }) =>
      children,
);
jest.mock('next/server', () => ({
  connection: jest.fn(async () => undefined),
}));

const originalFlag = process.env.OUTBOUND_PAYMENTS_ENABLED;

afterAll(() => {
  if (originalFlag === undefined) delete process.env.OUTBOUND_PAYMENTS_ENABLED;
  else process.env.OUTBOUND_PAYMENTS_ENABLED = originalFlag;
});

it('explains that outbound payments are not enabled', () => {
  render(
    <PaymentsUnavailable
      title='Outbound payments'
      description='Send an AUD payment from an active AUD account when the payments service is running.'
    />,
  );
  expect(
    screen.getByRole('heading', { name: 'Outbound payments' }),
  ).toBeInTheDocument();
  expect(screen.getByText(/not enabled in this stack/)).toBeInTheDocument();
});

it('renders the payments page panel when the feature flag is off', async () => {
  process.env.OUTBOUND_PAYMENTS_ENABLED = 'false';
  const { default: PaymentsPage } = await import('@/app/payments/page');
  render(await PaymentsPage());
  expect(
    screen.getByRole('heading', { name: 'Outbound payments' }),
  ).toBeInTheDocument();
  expect(screen.getByText(/not enabled in this stack/)).toBeInTheDocument();
});

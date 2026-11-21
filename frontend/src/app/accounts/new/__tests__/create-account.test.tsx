import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import CreateAccountPage from '../page';
import { AccountsAPI } from '@/lib/api/accounts';

jest.mock('next/navigation', () => ({
  useRouter: jest.fn(),
  useSearchParams: () => ({ get: () => null }),
  usePathname: () => '/accounts/new',
}));

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
jest.mock('@/lib/api/accounts', () => ({
  AccountsAPI: { createAccount: jest.fn() },
}));

const createAccount = jest.mocked(AccountsAPI.createAccount);
const push = jest.fn();

beforeEach(() => {
  createAccount.mockReset();
  push.mockReset();
  (useRouter as jest.Mock).mockReturnValue({
    push,
    replace: jest.fn(),
    prefetch: jest.fn(),
    back: jest.fn(),
  });
});

it('shows validation errors and does not submit an invalid account', async () => {
  render(<CreateAccountPage />);
  fireEvent.change(screen.getByLabelText('Opening balance'), {
    target: { value: '-5' },
  });
  fireEvent.change(screen.getByLabelText('Currency'), {
    target: { value: 'US' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Open account' }));

  expect(
    await screen.findByText('Initial balance cannot be negative'),
  ).toBeInTheDocument();
  expect(
    screen.getByText('Currency code must be 3 characters'),
  ).toBeInTheDocument();
  expect(createAccount).not.toHaveBeenCalled();
});

it('creates an account and opens its detail page', async () => {
  createAccount.mockResolvedValue({
    success: true,
    data: {
      id: 42,
      userId: 1,
      accountNumber: '00000042',
      accountType: 'SAVINGS',
      balance: 25,
      currency: 'AUD',
      status: 'ACTIVE',
      createdAt: '2026-11-21T00:00:00Z',
      updatedAt: '2026-11-21T00:00:00Z',
    },
  });

  render(<CreateAccountPage />);
  fireEvent.change(screen.getByLabelText('Account type'), {
    target: { value: 'SAVINGS' },
  });
  fireEvent.change(screen.getByLabelText('Opening balance'), {
    target: { value: '25' },
  });
  fireEvent.change(screen.getByLabelText('Currency'), {
    target: { value: 'aud' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Open account' }));

  await waitFor(() =>
    expect(createAccount).toHaveBeenCalledWith({
      accountType: 'SAVINGS',
      initialBalance: 25,
      currency: 'AUD',
    }),
  );
  expect(push).toHaveBeenCalledWith('/accounts/42');
});

it('shows the service error when creation fails', async () => {
  createAccount.mockResolvedValue({
    success: false,
    message: 'Account service unavailable',
  });
  render(<CreateAccountPage />);
  fireEvent.click(screen.getByRole('button', { name: 'Open account' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Account service unavailable',
  );
});

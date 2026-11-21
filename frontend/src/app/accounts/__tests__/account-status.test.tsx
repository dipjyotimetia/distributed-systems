import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import AccountDetailsPage from '../[id]/page';
import { AccountsAPI } from '@/lib/api/accounts';
import { TransactionsAPI } from '@/lib/api/transactions';

jest.mock('next/navigation', () => ({
  useParams: () => ({ id: '4' }),
  useRouter: () => ({ push: jest.fn() }),
}));
jest.mock('next/link', () => ({ __esModule: true, default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a> }));
jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/accounts/StatementJobs', () => () => null);
jest.mock('@/lib/auth/context', () => ({ useAuth: () => ({ user: { id: 1 } }) }));
jest.mock('@/lib/api/accounts', () => ({
  AccountsAPI: {
    getAccount: jest.fn(),
    freezeAccount: jest.fn(),
    unfreezeAccount: jest.fn(),
    closeAccount: jest.fn(),
  },
}));
jest.mock('@/lib/api/transactions', () => ({ TransactionsAPI: { getAccountTransactions: jest.fn() } }));

const getAccount = jest.mocked(AccountsAPI.getAccount);
const freezeAccount = jest.mocked(AccountsAPI.freezeAccount);
const closeAccount = jest.mocked(AccountsAPI.closeAccount);
const getAccountTransactions = jest.mocked(TransactionsAPI.getAccountTransactions);

const account = {
  id: 4, userId: 1, accountNumber: '00000004', accountType: 'CHECKING' as const,
  balance: 10, currency: 'USD', status: 'ACTIVE' as const,
  createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
};

beforeEach(() => {
  getAccount.mockReset();
  freezeAccount.mockReset();
  closeAccount.mockReset();
  getAccountTransactions.mockResolvedValue({ success: true, data: [] });
  getAccount.mockResolvedValue({ success: true, data: account });
});

it('freezes an open account and asks before closing it', async () => {
  freezeAccount.mockResolvedValue({ success: true, data: { ...account, status: 'FROZEN' } });
  const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false);
  render(<AccountDetailsPage />);
  fireEvent.click(await screen.findByRole('button', { name: 'Freeze account' }));
  expect(await screen.findByText('FROZEN')).toBeInTheDocument();
  expect(freezeAccount).toHaveBeenCalledWith(4);
  fireEvent.click(screen.getByRole('button', { name: 'Close account' }));
  expect(closeAccount).not.toHaveBeenCalled();
  confirm.mockRestore();
});

import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import TransactionsPage from '../page';
import { TransactionsAPI } from '@/lib/api/transactions';

jest.mock('next/link', () => ({ __esModule: true, default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a> }));
jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/lib/api/transactions', () => ({
  TransactionsAPI: { getTransactions: jest.fn(), cancelTransaction: jest.fn() },
}));

const getTransactions = jest.mocked(TransactionsAPI.getTransactions);
const cancelTransaction = jest.mocked(TransactionsAPI.cancelTransaction);

const pending = {
  id: 3, userId: 1, fromAccountId: 9, type: 'WITHDRAWAL' as const, amount: 10,
  description: 'Cash', status: 'PENDING' as const, createdAt: '2026-11-21T00:00:00Z',
};
const completed = {
  id: 4, userId: 1, toAccountId: 9, type: 'DEPOSIT' as const, amount: 20,
  description: 'Pay', status: 'COMPLETED' as const, createdAt: '2026-11-21T00:00:00Z',
};

beforeEach(() => {
  getTransactions.mockReset();
  cancelTransaction.mockReset();
});

it('cancels only a pending transaction', async () => {
  getTransactions.mockResolvedValue({ success: true, data: [pending, completed] });
  cancelTransaction.mockResolvedValue({ success: true, data: { ...pending, status: 'CANCELLED' } });
  render(<TransactionsPage />);
  expect(await screen.findByRole('button', { name: 'Cancel transaction' })).toBeInTheDocument();
  expect(screen.getAllByRole('button', { name: 'Cancel transaction' })).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: 'Cancel transaction' }));
  expect(await screen.findByText('Cancelled')).toBeInTheDocument();
  expect(cancelTransaction).toHaveBeenCalledWith(3);
});

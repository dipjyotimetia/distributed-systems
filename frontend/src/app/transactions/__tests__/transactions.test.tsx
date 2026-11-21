import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import TransactionsPage from '../page';
import { TransactionsAPI, Transaction } from '@/lib/api/transactions';

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
jest.mock('@/lib/api/transactions', () => ({
  TransactionsAPI: {
    getFilteredTransactions: jest.fn(),
    getTransactionSummary: jest.fn(),
    getAccountTransactionSummary: jest.fn(),
    cancelTransaction: jest.fn(),
  },
}));

const getFilteredTransactions = jest.mocked(
  TransactionsAPI.getFilteredTransactions,
);
const getTransactionSummary = jest.mocked(
  TransactionsAPI.getTransactionSummary,
);
const cancelTransaction = jest.mocked(TransactionsAPI.cancelTransaction);

const pending: Transaction = {
  id: 7,
  userId: 1,
  fromAccountId: 11,
  toAccountId: 12,
  type: 'TRANSFER',
  amount: 40,
  description: 'Rent share',
  status: 'PENDING',
  createdAt: '2026-11-21T01:00:00Z',
};

const summary = {
  totalTransactions: 1,
  totalDeposits: 0,
  totalWithdrawals: 0,
  totalTransfers: 1,
  pendingTransactions: 1,
  completedTransactions: 0,
  failedTransactions: 0,
  transactionsByType: { DEPOSIT: 0, WITHDRAWAL: 0, TRANSFER: 1 },
  transactionsByStatus: { PENDING: 1, COMPLETED: 0, FAILED: 0, CANCELLED: 0 },
};

beforeEach(() => {
  getFilteredTransactions.mockReset();
  getTransactionSummary.mockReset();
  cancelTransaction.mockReset();
  getFilteredTransactions.mockResolvedValue({
    success: true,
    data: { content: [pending], totalPages: 1 } as Awaited<
      ReturnType<typeof getFilteredTransactions>
    >['data'],
  });
  getTransactionSummary.mockResolvedValue({ success: true, data: summary });
});

it('cancels a pending transaction and reports a conflict', async () => {
  cancelTransaction.mockResolvedValue({
    success: false,
    message: 'Conflict. The request could not be completed due to a conflict.',
  });

  render(<TransactionsPage />);
  expect(await screen.findByText('Rent share')).toBeInTheDocument();
  expect(screen.getByText(/From account 11/)).toBeInTheDocument();
  expect(screen.getByText(/To account 12/)).toBeInTheDocument();
  expect(screen.queryByText(/\*\*\*\*/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'could not be completed due to a conflict',
  );
  expect(cancelTransaction).toHaveBeenCalledWith(7);
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled();
});

it('removes the cancel action after a pending transaction is cancelled', async () => {
  cancelTransaction.mockResolvedValue({
    success: true,
    data: { ...pending, status: 'CANCELLED' },
  });

  render(<TransactionsPage />);
  fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
  expect(await screen.findByText('Cancelled')).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: 'Cancel' }),
  ).not.toBeInTheDocument();
});

it('asks the ledger for the selected type', async () => {
  render(<TransactionsPage />);
  await screen.findByText('Rent share');
  fireEvent.change(screen.getByLabelText('Transaction type'), {
    target: { value: 'DEPOSIT' },
  });
  await waitFor(() =>
    expect(getFilteredTransactions).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'DEPOSIT' }),
      0,
      20,
    ),
  );
});

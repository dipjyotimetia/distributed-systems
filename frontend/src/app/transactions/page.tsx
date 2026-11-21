'use client';

import Link from 'next/link';
import React, { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import Money from '@/components/ui/Money';
import PageHeader from '@/components/ui/PageHeader';
import StatusPill from '@/components/ui/StatusPill';
import { PaginatedResponse } from '@/lib/api/client';
import {
  TransactionsAPI,
  Transaction,
  TransactionFilters,
  TransactionSummary,
} from '@/lib/api/transactions';
import { formatAccountType } from '@/lib/format';

const pageSize = 20;
const selectClass =
  'block w-full rounded-xl border border-line bg-surface px-3 py-2 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass';

function accountReferences(transaction: Transaction) {
  const parts: string[] = [];
  if (transaction.fromAccountId)
    parts.push(`From account ${transaction.fromAccountId}`);
  if (transaction.toAccountId)
    parts.push(`To account ${transaction.toAccountId}`);
  return parts.join(' · ');
}

function rowsFrom(
  data: PaginatedResponse<Transaction> | Transaction[] | undefined,
) {
  if (!data) return { rows: [] as Transaction[], totalPages: 1 };
  if (Array.isArray(data)) return { rows: data, totalPages: 1 };
  return {
    rows: data.content ?? [],
    totalPages: Math.max(data.totalPages || 1, 1),
  };
}

const TransactionsPage: React.FC = () => {
  return (
    <Suspense
      fallback={
        <p className='px-6 py-12 text-ink/70' role='status'>
          Loading transactions...
        </p>
      }
    >
      <TransactionsView />
    </Suspense>
  );
};

const TransactionsView: React.FC = () => {
  const router = useRouter();
  const searchParams = useSearchParams();
  const accountParam = searchParams.get('accountId');
  const accountId =
    accountParam && /^\d+$/.test(accountParam)
      ? Number(accountParam)
      : undefined;

  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [summary, setSummary] = useState<TransactionSummary | null>(null);
  const [totalPages, setTotalPages] = useState(1);
  const [page, setPage] = useState(0);
  const [filterType, setFilterType] = useState('ALL');
  const [filterStatus, setFilterStatus] = useState('ALL');
  const [trackedAccountId, setTrackedAccountId] = useState(accountId);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  const [cancelError, setCancelError] = useState<{
    id: number;
    message: string;
  } | null>(null);

  if (trackedAccountId !== accountId) {
    setTrackedAccountId(accountId);
    setPage(0);
    setIsLoading(true);
  }

  useEffect(() => {
    let active = true;
    const filters: TransactionFilters = {};
    if (accountId) filters.accountId = accountId;
    if (filterType !== 'ALL') filters.type = filterType as Transaction['type'];
    if (filterStatus !== 'ALL')
      filters.status = filterStatus as Transaction['status'];

    void Promise.all([
      TransactionsAPI.getFilteredTransactions(filters, page, pageSize),
      accountId
        ? TransactionsAPI.getAccountTransactionSummary(accountId)
        : TransactionsAPI.getTransactionSummary(),
    ])
      .then(([listResponse, summaryResponse]) => {
        if (!active) return;
        if (listResponse.success) {
          const parsed = rowsFrom(listResponse.data);
          setTransactions(parsed.rows);
          setTotalPages(parsed.totalPages);
          setError(null);
        } else {
          setTransactions([]);
          setError(listResponse.message || 'Failed to load transactions');
        }
        setSummary(
          summaryResponse.success && summaryResponse.data
            ? summaryResponse.data
            : null,
        );
        setIsLoading(false);
      })
      .catch(() => {
        if (!active) return;
        setTransactions([]);
        setError('Failed to load transactions');
        setIsLoading(false);
      });

    return () => {
      active = false;
    };
  }, [accountId, filterStatus, filterType, page]);

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const cancel = async (transactionId: number) => {
    setCancellingId(transactionId);
    setCancelError(null);
    const response = await TransactionsAPI.cancelTransaction(transactionId);
    setCancellingId(null);
    if (response.success && response.data) {
      setTransactions(current =>
        current.map(item =>
          item.id === transactionId ? (response.data as Transaction) : item,
        ),
      );
      const summaryResponse = accountId
        ? await TransactionsAPI.getAccountTransactionSummary(accountId)
        : await TransactionsAPI.getTransactionSummary();
      if (summaryResponse.success && summaryResponse.data)
        setSummary(summaryResponse.data);
      return;
    }
    setCancelError({
      id: transactionId,
      message: response.message || 'This transaction could not be cancelled.',
    });
  };

  const filtersActive =
    filterType !== 'ALL' || filterStatus !== 'ALL' || accountId !== undefined;

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className='mx-auto max-w-6xl'>
          <PageHeader
            eyebrow='Ledger'
            title='Your transactions'
            description='Review activity, filter the ledger, and cancel a payment that is still pending.'
            actions={
              <Button onClick={() => router.push('/accounts')}>
                Move money
              </Button>
            }
          />

          <div className='mb-6 rounded-3xl border border-line bg-surface p-4'>
            <div className='flex flex-wrap gap-4'>
              <div>
                <label
                  htmlFor='transaction-type'
                  className='mb-1 block text-sm font-medium text-ink'
                >
                  Transaction type
                </label>
                <select
                  id='transaction-type'
                  value={filterType}
                  onChange={event => {
                    setFilterType(event.target.value);
                    setPage(0);
                    setIsLoading(true);
                  }}
                  className={selectClass}
                >
                  <option value='ALL'>All types</option>
                  <option value='DEPOSIT'>Deposits</option>
                  <option value='WITHDRAWAL'>Withdrawals</option>
                  <option value='TRANSFER'>Transfers</option>
                </select>
              </div>
              <div>
                <label
                  htmlFor='transaction-status'
                  className='mb-1 block text-sm font-medium text-ink'
                >
                  Status
                </label>
                <select
                  id='transaction-status'
                  value={filterStatus}
                  onChange={event => {
                    setFilterStatus(event.target.value);
                    setPage(0);
                    setIsLoading(true);
                  }}
                  className={selectClass}
                >
                  <option value='ALL'>All statuses</option>
                  <option value='COMPLETED'>Completed</option>
                  <option value='PENDING'>Pending</option>
                  <option value='FAILED'>Failed</option>
                  <option value='CANCELLED'>Cancelled</option>
                </select>
              </div>
            </div>
            {accountId && (
              <p className='mt-4 text-sm text-ink/70'>
                Showing account {accountId}.{' '}
                <Link
                  href='/transactions'
                  className='font-medium text-brass hover:text-ink'
                >
                  Clear account filter
                </Link>
              </p>
            )}
          </div>

          {isLoading && (
            <div
              className='flex items-center justify-center py-12'
              role='status'
            >
              <div className='h-12 w-12 animate-spin rounded-full border-b-2 border-ink'></div>
              <span className='ml-4 text-ink/70'>Loading transactions...</span>
            </div>
          )}

          {error && (
            <div
              className='mb-6 rounded-2xl border border-red-200 bg-red-50 p-4'
              role='alert'
            >
              <h2 className='text-sm font-medium text-red-800'>Error</h2>
              <p className='mt-2 text-sm text-red-700'>{error}</p>
            </div>
          )}

          {!isLoading && !error && transactions.length === 0 && (
            <div className='rounded-3xl border border-dashed border-line bg-surface px-6 py-16 text-center'>
              <h2 className='text-lg font-medium text-ink'>
                No transactions found
              </h2>
              <p className='mt-2 text-ink/70'>
                {filtersActive
                  ? 'No transactions match your current filters.'
                  : 'Move money from an account to get started.'}
              </p>
              <Button className='mt-4' onClick={() => router.push('/accounts')}>
                Move money
              </Button>
            </div>
          )}

          <Link
            className='mb-4 mt-6 inline-block text-sm font-medium text-brass underline'
            href='/disputes'
          >
            View transfer disputes
          </Link>

          {!isLoading && !error && transactions.length > 0 && (
            <ul className='overflow-hidden rounded-3xl border border-line bg-surface'>
              {transactions.map(transaction => (
                <li
                  key={transaction.id}
                  className='border-b border-line px-4 py-4 last:border-b-0 sm:px-6'
                >
                  <div className='flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between'>
                    <div>
                      <div className='flex flex-wrap items-center gap-2'>
                        <p className='text-sm font-medium text-ink'>
                          {transaction.description}
                        </p>
                        <StatusPill status={transaction.status} />
                      </div>
                      <p className='mt-1 text-sm text-ink/60'>
                        {formatAccountType(transaction.type)}
                        {accountReferences(transaction)
                          ? ` · ${accountReferences(transaction)}`
                          : ''}
                        {' · '}
                        {formatDate(transaction.createdAt)}
                      </p>
                      {cancelError?.id === transaction.id && (
                        <p role='alert' className='mt-2 text-sm text-red-700'>
                          {cancelError.message}
                        </p>
                      )}
                    </div>
                    <div className='text-left sm:text-right'>
                      <Money
                        amount={transaction.amount}
                        signed={
                          transaction.type === 'DEPOSIT'
                            ? 'credit'
                            : transaction.type === 'WITHDRAWAL'
                              ? 'debit'
                              : undefined
                        }
                        className='text-lg font-semibold'
                      />
                      {transaction.status === 'PENDING' && (
                        <div className='mt-2'>
                          <Button
                            size='sm'
                            variant='outline'
                            isLoading={cancellingId === transaction.id}
                            onClick={() => cancel(transaction.id)}
                          >
                            Cancel
                          </Button>
                        </div>
                      )}
                      {transaction.type === 'TRANSFER' &&
                        transaction.status === 'COMPLETED' && (
                          <Link
                            className='mt-2 block text-sm font-medium text-brass underline'
                            href={`/disputes?transferId=${transaction.id}`}
                          >
                            Dispute transfer
                          </Link>
                        )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}

          {!isLoading && !error && totalPages > 1 && (
            <nav
              className='mt-4 flex items-center gap-3'
              aria-label='Transaction pages'
            >
              <Button
                variant='outline'
                disabled={page === 0}
                onClick={() => {
                  setIsLoading(true);
                  setPage(current => current - 1);
                }}
              >
                Previous
              </Button>
              <span className='text-sm text-ink/70'>
                Page {page + 1} of {totalPages}
              </span>
              <Button
                variant='outline'
                disabled={page + 1 >= totalPages}
                onClick={() => {
                  setIsLoading(true);
                  setPage(current => current + 1);
                }}
              >
                Next
              </Button>
            </nav>
          )}

          {summary && (
            <div className='mt-6 rounded-3xl border border-line bg-surface p-6'>
              <h2 className='mb-4 text-lg font-medium text-ink'>
                {accountId
                  ? `Summary for account ${accountId}`
                  : 'Transaction summary'}
              </h2>
              <div className='grid grid-cols-1 gap-4 md:grid-cols-4'>
                <div className='rounded-2xl bg-[#f6f1e6] p-4'>
                  <p className='text-sm font-medium text-brass'>
                    Total transactions
                  </p>
                  <p className='money mt-1 text-2xl font-semibold text-ink'>
                    {summary.totalTransactions}
                  </p>
                </div>
                <div className='rounded-2xl bg-emerald-50 p-4'>
                  <p className='text-sm font-medium text-emerald-800'>
                    Deposits
                  </p>
                  <p className='money mt-1 text-2xl font-semibold text-ink'>
                    {summary.totalDeposits}
                  </p>
                </div>
                <div className='rounded-2xl bg-surface p-4 ring-1 ring-line'>
                  <p className='text-sm font-medium text-ink/70'>Withdrawals</p>
                  <p className='money mt-1 text-2xl font-semibold text-ink'>
                    {summary.totalWithdrawals}
                  </p>
                </div>
                <div className='rounded-2xl bg-[#efe8d8] p-4'>
                  <p className='text-sm font-medium text-ink'>Pending</p>
                  <p className='money mt-1 text-2xl font-semibold text-ink'>
                    {summary.pendingTransactions}
                  </p>
                </div>
              </div>
            </div>
          )}
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default TransactionsPage;

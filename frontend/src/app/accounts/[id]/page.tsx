'use client';

import StatementJobs from '@/components/accounts/StatementJobs';
import PeriodStatements from '@/components/accounts/PeriodStatements';
import React, { useState, useEffect } from 'react';
import { useParams } from 'next/navigation';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import Money from '@/components/ui/Money';
import PageHeader from '@/components/ui/PageHeader';
import StatusPill from '@/components/ui/StatusPill';
import Link from 'next/link';
import { AccountsAPI, Account } from '@/lib/api/accounts';
import { TransactionsAPI, Transaction } from '@/lib/api/transactions';
import { formatAccountType, formatMoney } from '@/lib/format';

const AccountDetailsPage: React.FC = () => {
  const params = useParams();
  const [account, setAccount] = useState<Account | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [transactionsLoading, setTransactionsLoading] = useState(true);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<
    'freeze' | 'unfreeze' | 'close' | null
  >(null);
  const [confirmClose, setConfirmClose] = useState(false);

  const accountId = params.id as string;

  useEffect(() => {
    const fetchAccountDetails = async () => {
      try {
        setIsLoading(true);
        setError(null);
        const response = await AccountsAPI.getAccount(parseInt(accountId, 10));
        if (response.success && response.data) {
          setAccount(response.data);
        } else {
          setError(response.message || 'Failed to load account details');
        }
      } catch {
        setError('Failed to load account details');
      } finally {
        setIsLoading(false);
      }
    };

    const fetchAccountTransactions = async () => {
      try {
        setTransactionsLoading(true);
        const response = await TransactionsAPI.getAccountTransactions(
          parseInt(accountId, 10),
          0,
          20,
        );
        if (response.success && response.data) {
          setTransactions(Array.isArray(response.data) ? response.data : []);
        }
      } catch {
        setTransactions([]);
      } finally {
        setTransactionsLoading(false);
      }
    };

    if (accountId) {
      fetchAccountDetails();
      fetchAccountTransactions();
    }
  }, [accountId]);

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const runStatusChange = async (action: 'freeze' | 'unfreeze' | 'close') => {
    if (!account) return;
    setPendingAction(action);
    setActionError(null);
    const request =
      action === 'freeze'
        ? AccountsAPI.freezeAccount(account.id)
        : action === 'unfreeze'
          ? AccountsAPI.unfreezeAccount(account.id)
          : AccountsAPI.closeAccount(account.id);
    const response = await request;
    setPendingAction(null);
    setConfirmClose(false);
    if (response.success && response.data) {
      setAccount(response.data);
      return;
    }
    setActionError(response.message || 'The account could not be updated.');
  };

  if (isLoading) {
    return (
      <ProtectedRoute requireAuth={true}>
        <AuthenticatedLayout>
          <div className='flex items-center justify-center py-24' role='status'>
            <div className='h-12 w-12 animate-spin rounded-full border-b-2 border-ink'></div>
            <p className='ml-4 text-ink/70'>Loading account details...</p>
          </div>
        </AuthenticatedLayout>
      </ProtectedRoute>
    );
  }

  if (error || !account) {
    return (
      <ProtectedRoute requireAuth={true}>
        <AuthenticatedLayout>
          <div className='mx-auto max-w-lg py-16 text-center'>
            <h1 className='text-lg font-medium text-ink'>
              {error || 'Account not found'}
            </h1>
            <p className='mt-2 text-ink/70'>
              The account you&apos;re looking for doesn&apos;t exist or you
              don&apos;t have permission to view it.
            </p>
            <Link
              href='/accounts'
              className='mt-6 inline-flex rounded-full bg-ink px-4 py-2 text-sm font-medium text-canvas'
            >
              Back to Accounts
            </Link>
          </div>
        </AuthenticatedLayout>
      </ProtectedRoute>
    );
  }

  const canMove = account.status === 'ACTIVE';
  const canClose = account.balance === 0 && account.status !== 'CLOSED';
  const recent = transactions.slice(0, 5);

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className='mx-auto max-w-6xl'>
          <PageHeader
            eyebrow='Accounts'
            title='Account details'
            description={`${formatAccountType(account.accountType)} account ${account.accountNumber}`}
            actions={
              <Link
                href='/accounts'
                className='inline-flex items-center justify-center rounded-full border border-line bg-surface px-4 py-2 text-sm font-medium text-ink hover:bg-[#f6f1e6]'
              >
                Back to Accounts
              </Link>
            }
          />

          <div className='mb-6 rounded-3xl border border-line bg-surface p-6'>
            <div className='grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-4'>
              <div>
                <p className='text-sm font-medium text-ink/70'>
                  Account number
                </p>
                <p className='font-mono text-lg font-semibold text-ink'>
                  {account.accountNumber}
                </p>
              </div>
              <div>
                <p className='text-sm font-medium text-ink/70'>
                  Current balance
                </p>
                <Money
                  amount={account.balance}
                  currency={account.currency}
                  className='text-4xl font-semibold'
                />
              </div>
              <div>
                <p className='text-sm font-medium text-ink/70'>Account type</p>
                <p className='text-lg font-semibold text-ink'>
                  {formatAccountType(account.accountType)}
                </p>
              </div>
              <div>
                <p className='text-sm font-medium text-ink/70'>Status</p>
                <div className='mt-1'>
                  <StatusPill status={account.status} />
                </div>
              </div>
            </div>
            <div className='mt-4 flex flex-col gap-1 border-t border-line pt-4 text-sm text-ink/70 sm:flex-row sm:justify-between'>
              <span>Opened {formatDate(account.createdAt)}</span>
              <span>Updated {formatDate(account.updatedAt)}</span>
            </div>
          </div>

          <div className='mb-6 rounded-3xl border border-line bg-surface p-6'>
            <h2 className='text-lg font-medium text-ink'>Account status</h2>
            <p className='mt-1 text-sm text-ink/70'>
              {canMove
                ? 'Deposits, withdrawals, and transfers are available while this account is active.'
                : `Money movement is unavailable while this account is ${account.status.toLowerCase()}.`}
            </p>
            {actionError && (
              <p
                role='alert'
                className='mt-4 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800'
              >
                {actionError}
              </p>
            )}
            <div className='mt-4 flex flex-wrap gap-3'>
              {account.status === 'ACTIVE' && (
                <Button
                  variant='outline'
                  isLoading={pendingAction === 'freeze'}
                  onClick={() => runStatusChange('freeze')}
                >
                  Freeze account
                </Button>
              )}
              {account.status === 'FROZEN' && (
                <Button
                  variant='outline'
                  isLoading={pendingAction === 'unfreeze'}
                  onClick={() => runStatusChange('unfreeze')}
                >
                  Unfreeze account
                </Button>
              )}
              {canClose && !confirmClose && (
                <Button variant='outline' onClick={() => setConfirmClose(true)}>
                  Close account
                </Button>
              )}
              {canClose && confirmClose && (
                <Button
                  variant='primary'
                  isLoading={pendingAction === 'close'}
                  onClick={() => runStatusChange('close')}
                >
                  Confirm close
                </Button>
              )}
            </div>
            {canClose && account.balance === 0 && (
              <p className='mt-3 text-sm text-ink/60'>
                Closing is available because the balance is{' '}
                {formatMoney(0, account.currency)}.
              </p>
            )}
          </div>

          <PeriodStatements key={`period-${account.id}`} accountId={account.id} />
          <StatementJobs key={account.id} accountId={account.id} />

          <div className='mb-6 rounded-3xl border border-line bg-surface p-6'>
            <h2 className='mb-4 text-lg font-medium text-ink'>Move money</h2>
            <div className='grid grid-cols-1 gap-4 md:grid-cols-3'>
              <Link
                href={`/accounts/${account.id}/deposit`}
                aria-disabled={!canMove}
                tabIndex={canMove ? undefined : -1}
                className={`inline-flex items-center justify-center rounded-full border border-line bg-surface px-4 py-2 text-base font-medium text-ink hover:bg-[#f6f1e6] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass ${canMove ? '' : 'pointer-events-none opacity-50'}`}
              >
                Make deposit
              </Link>
              <Link
                href={`/accounts/${account.id}/withdraw`}
                aria-disabled={!canMove}
                tabIndex={canMove ? undefined : -1}
                className={`inline-flex items-center justify-center rounded-full border border-line bg-surface px-4 py-2 text-base font-medium text-ink hover:bg-[#f6f1e6] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass ${canMove ? '' : 'pointer-events-none opacity-50'}`}
              >
                Withdraw funds
              </Link>
              <Link
                href={`/accounts/${account.id}/transfer`}
                aria-disabled={!canMove}
                tabIndex={canMove ? undefined : -1}
                className={`inline-flex items-center justify-center rounded-full border border-line bg-surface px-4 py-2 text-base font-medium text-ink hover:bg-[#f6f1e6] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass ${canMove ? '' : 'pointer-events-none opacity-50'}`}
              >
                Transfer money
              </Link>
            </div>
          </div>

          <div className='rounded-3xl border border-line bg-surface'>
            <div className='flex items-center justify-between border-b border-line px-6 py-4'>
              <h2 className='text-lg font-medium text-ink'>
                Recent transactions
              </h2>
              <Link
                href={`/transactions?accountId=${account.id}`}
                className='text-sm font-medium text-brass hover:text-ink'
              >
                View all
              </Link>
            </div>

            {transactionsLoading ? (
              <div className='px-6 py-12 text-center' role='status'>
                <div className='mx-auto h-8 w-8 animate-spin rounded-full border-b-2 border-ink'></div>
                <p className='mt-4 text-ink/70'>Loading transactions...</p>
              </div>
            ) : transactions.length === 0 ? (
              <div className='px-6 py-12 text-center'>
                <h3 className='text-lg font-medium text-ink'>
                  No transactions yet
                </h3>
                <p className='mt-2 text-ink/70'>
                  This account has no transaction history.
                </p>
                {canMove && (
                  <Link
                    href={`/accounts/${account.id}/deposit`}
                    className='mt-4 inline-flex rounded-full bg-ink px-4 py-2 text-sm font-medium text-canvas'
                  >
                    Make a deposit
                  </Link>
                )}
              </div>
            ) : (
              <ul className='divide-y divide-line'>
                {recent.map(transaction => (
                  <li key={transaction.id} className='px-6 py-4'>
                    <div className='flex items-center justify-between gap-4'>
                      <div>
                        <div className='flex flex-wrap items-center gap-2'>
                          <p className='text-sm font-medium text-ink'>
                            {transaction.description}
                          </p>
                          <StatusPill status={transaction.status} />
                        </div>
                        <p className='mt-1 text-sm text-ink/60'>
                          {formatAccountType(transaction.type)} ·{' '}
                          {formatDate(transaction.createdAt)}
                        </p>
                      </div>
                      <Money
                        amount={transaction.amount}
                        currency={account.currency}
                        signed={
                          transaction.type === 'DEPOSIT'
                            ? 'credit'
                            : transaction.type === 'WITHDRAWAL'
                              ? 'debit'
                              : undefined
                        }
                        className='text-lg font-semibold'
                      />
                    </div>
                  </li>
                ))}
                {transactions.length > 5 && (
                  <li className='bg-[#f6f1e6] px-6 py-4 text-center'>
                    <Link
                      href={`/transactions?accountId=${account.id}`}
                      className='text-sm font-medium text-brass hover:text-ink'
                    >
                      View {transactions.length - 5} more transactions
                    </Link>
                  </li>
                )}
              </ul>
            )}
          </div>
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default AccountDetailsPage;

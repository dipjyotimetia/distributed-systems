'use client';

import React, { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import { useAuth } from '@/lib/auth/context';
import Button from '@/components/ui/Button';
import Money from '@/components/ui/Money';
import StatusPill from '@/components/ui/StatusPill';
import { AccountsAPI, Account } from '@/lib/api/accounts';
import { TransactionsAPI, Transaction } from '@/lib/api/transactions';
import { demoTransfer, demoUser } from '@/lib/demo';
import { formatAccountType, formatMoney, maskAccount } from '@/lib/format';

const DashboardPage: React.FC = () => {
  const { user } = useAuth();
  const router = useRouter();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [transactionsError, setTransactionsError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    const load = async () => {
      setIsLoading(true);
      setError(null);
      setTransactionsError(null);

      const [accountsResponse, transactionsResponse] = await Promise.all([
        AccountsAPI.getAccounts(),
        TransactionsAPI.getRecentTransactions(8),
      ]);

      if (!active) return;

      if (accountsResponse.success && accountsResponse.data) {
        setAccounts(accountsResponse.data);
      } else {
        setError(accountsResponse.message || 'Failed to load accounts');
      }

      if (transactionsResponse.success && transactionsResponse.data) {
        setTransactions(transactionsResponse.data);
      } else {
        setTransactionsError(transactionsResponse.message || 'Failed to load recent transactions');
      }

      setIsLoading(false);
    };

    load();
    return () => {
      active = false;
    };
  }, []);

  const checkingAccount = useMemo(
    () => accounts.find((account) => account.accountType === 'CHECKING'),
    [accounts]
  );

  const displayName = user?.username === demoUser.username ? demoUser.name : user?.username;
  const totalBalance = accounts.reduce((sum, account) => sum + account.balance, 0);
  const currency = accounts[0]?.currency || 'USD';

  const transferHref = checkingAccount
    ? `/accounts/${checkingAccount.id}/transfer?demo=${demoTransfer.queryValue}`
    : '/accounts';

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className="mx-auto max-w-6xl">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h1 className="text-3xl font-semibold tracking-tight text-ink">Dashboard</h1>
              {displayName && (
                <h2 className="mt-2 text-xl font-medium text-ink">{displayName}</h2>
              )}
              {user && (
                <p className="mt-2 text-sm text-ink/70">Welcome back, {user.username}!</p>
              )}
            </div>
            <div className="flex flex-wrap gap-3">
              <Button variant="outline" onClick={() => router.push('/accounts')}>
                Create Account
              </Button>
              <Button variant="primary" onClick={() => router.push('/transactions')}>
                New Transaction
              </Button>
            </div>
          </div>

          {isLoading && (
            <p className="mt-10 text-sm text-ink/70" role="status">Loading...</p>
          )}

          {error && (
            <p role="alert" className="mt-8 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
              {error}
            </p>
          )}

          {!isLoading && !error && (
            <>
              <section className="mt-8 overflow-hidden rounded-3xl bg-ink text-canvas">
                <div className="grid gap-8 p-6 sm:p-8 lg:grid-cols-[1.4fr_0.8fr] lg:items-end">
                  <div>
                    <p className="text-xs font-medium uppercase tracking-[0.18em] text-[#d7b56a]">Total Balance</p>
                    <p className="money mt-3 text-4xl font-semibold tracking-tight sm:text-5xl">
                      {formatMoney(totalBalance, currency)}
                    </p>
                    <p className="mt-3 text-sm text-canvas/70">
                      Available across your Apex accounts.
                    </p>
                  </div>
                  <dl className="grid grid-cols-2 gap-4 rounded-2xl bg-white/5 p-4">
                    <div>
                      <dt className="text-xs uppercase tracking-[0.14em] text-canvas/60">Total Accounts</dt>
                      <dd className="money mt-2 text-2xl font-semibold">{accounts.length}</dd>
                    </div>
                    <div>
                      <dt className="text-xs uppercase tracking-[0.14em] text-canvas/60">Ready to move</dt>
                      <dd className="mt-2 text-sm text-canvas/80">
                        {checkingAccount ? 'Checking is available' : 'Open an account to transfer'}
                      </dd>
                    </div>
                  </dl>
                </div>
              </section>

              <div className="mt-8 grid gap-8 lg:grid-cols-[1.15fr_0.85fr]">
                <section>
                  <div className="mb-4 flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-ink">Your Accounts</h2>
                    <Link href="/accounts" className="text-sm font-medium text-brass hover:text-ink">
                      View all accounts
                    </Link>
                  </div>

                  {accounts.length === 0 ? (
                    <div className="rounded-3xl border border-dashed border-line bg-surface px-6 py-12 text-center">
                      <h3 className="text-lg font-medium text-ink">No accounts found</h3>
                      <p className="mt-2 text-sm text-ink/70">Create your first account to get started</p>
                      <Link href="/accounts" className="mt-5 inline-block">
                        <Button>Create Account</Button>
                      </Link>
                    </div>
                  ) : (
                    <ul className="grid gap-4">
                      {accounts.map((account) => (
                        <li key={account.id}>
                          <Link
                            href={`/accounts/${account.id}`}
                            className="block rounded-3xl border border-line bg-surface p-5 transition-colors hover:border-brass focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass"
                          >
                            <div className="flex items-start justify-between gap-4">
                              <div>
                                <p className="text-xs font-medium uppercase tracking-[0.16em] text-brass">
                                  {account.accountType}
                                </p>
                                <p className="mt-2 text-sm text-ink/70">{formatAccountType(account.accountType)} account</p>
                                <p className="money mt-1 font-mono text-sm text-ink">{maskAccount(account.accountNumber)}</p>
                              </div>
                              <div className="text-right">
                                <Money amount={account.balance} currency={account.currency || 'USD'} className="text-xl font-semibold" />
                                <div className="mt-2">
                                  <StatusPill status={account.status || 'ACTIVE'} />
                                </div>
                              </div>
                            </div>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>

                <section className="rounded-3xl border border-line bg-surface p-5">
                  <div className="flex items-center justify-between gap-3">
                    <h2 className="text-lg font-semibold text-ink">Recent Transactions</h2>
                    <Link href="/transactions" className="text-sm font-medium text-brass hover:text-ink">
                      View all transactions
                    </Link>
                  </div>

                  {transactionsError && (
                    <p role="alert" className="mt-4 text-sm text-red-700">{transactionsError}</p>
                  )}

                  {!transactionsError && transactions.length === 0 && (
                    <p className="mt-6 text-sm text-ink/70">No recent transactions</p>
                  )}

                  <ul className="mt-4 divide-y divide-line">
                    {transactions.map((transaction) => (
                      <li key={transaction.id} className="flex items-center justify-between gap-4 py-3">
                        <div>
                          <p className="text-sm font-medium text-ink">{transaction.description}</p>
                          <p className="mt-1 text-xs uppercase tracking-[0.12em] text-ink/50">{transaction.type}</p>
                        </div>
                        <Money
                          amount={transaction.amount}
                          signed={transaction.type === 'DEPOSIT' ? 'credit' : 'debit'}
                          className="text-sm font-semibold"
                        />
                      </li>
                    ))}
                  </ul>

                  <Link href={transferHref} className="mt-5 inline-flex">
                    <Button variant="secondary" disabled={!checkingAccount && accounts.length > 0}>
                      Start Transfer Review
                    </Button>
                  </Link>
                </section>
              </div>
            </>
          )}
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default DashboardPage;

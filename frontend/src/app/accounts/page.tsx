'use client';

import React, { useState, useEffect } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import PageHeader from '@/components/ui/PageHeader';
import Money from '@/components/ui/Money';
import StatusPill from '@/components/ui/StatusPill';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { AccountsAPI, Account } from '@/lib/api/accounts';
import { formatAccountType, formatMoney } from '@/lib/format';

const AccountsPage: React.FC = () => {
  const router = useRouter();
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchAccounts = async () => {
      try {
        const response = await AccountsAPI.getAccounts();
        
        if (response.success && response.data) {
          console.log('Accounts data:', response.data); // Debug log
          setAccounts(response.data);
        } else {
          console.error('Failed to fetch accounts:', response.message);
          setError(response.message || 'Failed to load accounts');
        }
      } catch (err) {
        console.error('Error fetching accounts:', err);
        setError('Failed to load accounts');
      } finally {
        setIsLoading(false);
      }
    };

    fetchAccounts();
  }, []);

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  const totalBalance = accounts.reduce((sum, account) => sum + account.balance, 0);

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className="mx-auto max-w-6xl">
          <PageHeader
            eyebrow="Accounts"
            title="Your Accounts"
            description="Manage your banking accounts and view balances"
            actions={
              <Button variant="primary" onClick={() => router.push('/accounts/new')}>
                Create New Account
              </Button>
            }
          />

          {isLoading && (
            <div className="flex items-center justify-center py-12">
              <div className="h-12 w-12 animate-spin rounded-full border-b-2 border-ink"></div>
              <span className="ml-4 text-ink/70">Loading accounts...</span>
            </div>
          )}

          {error && (
            <div className="mb-6 rounded-2xl border border-red-200 bg-red-50 p-4" role="alert">
              <h3 className="text-sm font-medium text-red-800">Error</h3>
              <p className="mt-2 text-sm text-red-700">{error}</p>
            </div>
          )}

          {!isLoading && !error && accounts.length === 0 && (
            <div className="rounded-3xl border border-dashed border-line bg-surface px-6 py-16 text-center">
              <h3 className="mb-2 text-lg font-medium text-ink">No accounts found</h3>
              <p className="mb-4 text-ink/70">Create your first account to get started</p>
              <Button variant="primary" onClick={() => router.push('/accounts/new')}>
                Create Account
              </Button>
            </div>
          )}

          {!isLoading && !error && accounts.length > 0 && (
            <ul className="grid gap-4">
              {accounts.map((account) => (
                <li key={account.id}>
                  <Link
                    href={`/accounts/${account.id}`}
                    className="block rounded-3xl border border-line bg-surface p-5 transition-colors hover:border-brass focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass"
                  >
                    <div className="flex items-start justify-between gap-4">
                      <div>
                        <p className="text-sm font-medium text-ink">
                          {formatAccountType(account.accountType)} Account
                        </p>
                        <p className="mt-1 font-mono text-sm text-ink/60">
                          Account #: {account.accountNumber}
                        </p>
                        <p className="mt-3 text-xs text-ink/50">
                          Created: {formatDate(account.createdAt)} · Updated: {formatDate(account.updatedAt)}
                        </p>
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

          {!isLoading && !error && accounts.length > 0 && (
            <div className="mt-6 rounded-3xl border border-line bg-surface p-6">
              <h3 className="mb-4 text-lg font-medium text-ink">Account Summary</h3>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
                <div className="rounded-2xl bg-[#f6f1e6] p-4">
                  <p className="text-sm font-medium text-brass">Total Accounts</p>
                  <p className="money mt-1 text-2xl font-semibold text-ink">{accounts.length}</p>
                </div>
                <div className="rounded-2xl bg-emerald-50 p-4">
                  <p className="text-sm font-medium text-emerald-800">Total Balance</p>
                  <p className="money mt-1 text-2xl font-semibold text-ink">{formatMoney(totalBalance, 'USD')}</p>
                </div>
                <div className="rounded-2xl bg-surface p-4 ring-1 ring-line">
                  <p className="text-sm font-medium text-ink/70">Active Accounts</p>
                  <p className="money mt-1 text-2xl font-semibold text-ink">
                    {accounts.filter(account => account.status === 'ACTIVE').length}
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

export default AccountsPage;
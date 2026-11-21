'use client';

import React, { useState, useEffect } from 'react';
import { useParams } from 'next/navigation';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import Input from '@/components/ui/Input';
import Link from 'next/link';
import { useMovement } from '@/lib/api/movements';
import MovementStatus from '@/components/accounts/MovementStatus';
import { AccountsAPI, Account } from '@/lib/api/accounts';

const WithdrawPage: React.FC = () => {
  const params = useParams();
  const [account, setAccount] = useState<Account | null>(null);
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const accountId = params.id as string;
  const movement = useMovement('WITHDRAWAL', accountId);
  const submitted = movement.submission;

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

    if (accountId) {
      fetchAccountDetails();
    }
  }, [accountId]);

  const movementLocked = account != null && account.status !== 'ACTIVE';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (movementLocked) {
      setError(`This account is ${account?.status.toLowerCase()} and cannot move money.`);
      return;
    }
    
    if (!/^(0|[1-9][0-9]*)([.][0-9]+)?$/.test(amount) || !/[1-9]/.test(amount)) {
      setError('Please enter a valid amount');
      return;
    }
    if (!account) return;
    setError(null);
    movement.submit({
      type: 'WITHDRAWAL', fromAccountId: Number(accountId),
      money: { currency: account.currency, amount },
      description: description.trim() || undefined,
    });
  };

  const formatCurrency = (amount: number, currency: string) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency,
    }).format(amount);
  };

  if (isLoading) {
    return (
      <ProtectedRoute requireAuth={true}>
        <AuthenticatedLayout>
          <div className="min-h-screen flex items-center justify-center">
            <div className="text-center">
              <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-ink mx-auto"></div>
              <p className="mt-4 text-ink/70">Loading account details...</p>
            </div>
          </div>
        </AuthenticatedLayout>
      </ProtectedRoute>
    );
  }

  if (error && !account) {
    return (
      <ProtectedRoute requireAuth={true}>
        <AuthenticatedLayout>
          <div className="min-h-screen flex items-center justify-center">
            <div className="text-center">
              <div className="w-24 h-24 mx-auto mb-4 text-gray-400">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <h3 className="text-lg font-medium text-ink mb-2">Account not found</h3>
              <p className="text-ink/70 mb-4">
                The account you&apos;re looking for doesn&apos;t exist or you don&apos;t have permission to view it.
              </p>
              <Link href="/accounts">
                <Button variant="primary">
                  Back to Accounts
                </Button>
              </Link>
            </div>
          </div>
        </AuthenticatedLayout>
      </ProtectedRoute>
    );
  }

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className="mx-auto max-w-2xl">
          <div className="px-4 py-6 sm:px-0">
            {/* Header */}
            <div className="mb-6">
              <div className="flex items-center justify-between">
                <div>
                  <h1 className="text-3xl font-bold text-ink">Withdraw Funds</h1>
                  <p className="mt-1 text-sm text-ink/70">
                    Withdraw funds from your {account?.accountType?.toLowerCase()} account
                  </p>
                </div>
                <Link href={`/accounts/${accountId}`}>
                  <Button variant="outline">
                    Back to Account
                  </Button>
                </Link>
              </div>
            </div>

            {/* Account Summary */}
            {account && (
              <div className="rounded-3xl border border-line bg-surface p-6 mb-6">
                <h3 className="text-lg font-medium text-ink mb-4">Account Information</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-sm font-medium text-ink/70">Account Number</p>
                    <p className="text-lg font-semibold text-ink">{account.accountNumber}</p>
                  </div>
                  <div>
                    <p className="text-sm font-medium text-ink/70">Available Balance</p>
                    <p className="text-xl font-bold text-green-600">
                      {formatCurrency(account.balance, account.currency)}
                    </p>
                  </div>
                </div>
              </div>
            )}

            <MovementStatus movement={movement} />

            {/* Withdrawal Form */}
            <div className="rounded-3xl border border-line bg-surface">
              <div className="px-6 py-4 border-b border-gray-200">
                <h3 className="text-lg font-medium text-ink">Withdrawal Details</h3>
              </div>
              <form onSubmit={handleSubmit} className="p-6 space-y-6">
                {movementLocked && (
                  <p role="alert" className="rounded-2xl border border-line bg-[#f6f1e6] px-4 py-3 text-sm text-ink">
                    This account is {account?.status.toLowerCase()} and cannot accept a withdrawal.
                  </p>
                )}
                {error && (
                  <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                    <div className="flex">
                      <div className="flex-shrink-0">
                        <svg className="h-5 w-5 text-red-400" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
                        </svg>
                      </div>
                      <div className="ml-3">
                        <p className="text-sm text-red-800">{error}</p>
                      </div>
                    </div>
                  </div>
                )}

                <div>
                  <label htmlFor="amount" className="block text-sm font-medium text-gray-700 mb-2">
                    Amount ({account?.currency || 'USD'})
                  </label>
                  <Input
                    id="amount"
                    type="number"
                    step="0.01"
                    min="0.01"
                    max={account?.balance || undefined}
                    value={submitted?.intent.money.amount ?? amount}
                    disabled={Boolean(submitted)}
                    onChange={(e) => setAmount(e.target.value)}
                    placeholder="0.00"
                    required
                    className="w-full"
                  />
                  <p className="mt-1 text-sm text-gray-500">
                    Maximum withdrawal: {account ? formatCurrency(account.balance, account.currency) : 'N/A'}
                  </p>
                </div>

                <div>
                  <label htmlFor="description" className="block text-sm font-medium text-gray-700 mb-2">
                    Description
                  </label>
                  <Input
                    id="description"
                    type="text"
                    value={submitted ? submitted.intent.description ?? '' : description}
                    disabled={Boolean(submitted)}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="e.g., ATM withdrawal, Bill payment"
                    required
                    className="w-full"
                  />
                  <p className="mt-1 text-sm text-gray-500">
                    Brief description of the withdrawal
                  </p>
                </div>

                <div className="flex justify-end space-x-3 pt-4">
                  <Link href={`/accounts/${accountId}`}>
                    <Button variant="outline" type="button">
                      Cancel
                    </Button>
                  </Link>
                  <Button
                    variant="primary"
                    type="submit"
                    disabled={!movement.ready || Boolean(submitted) || movementLocked}
                    className="min-w-[120px]"
                  >
                    {submitted && !submitted.outcome ? (
                      <>
                        <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></div>
                        Processing...
                      </>
                    ) : (
                      'Process Withdrawal'
                    )}
                  </Button>
                </div>
              </form>
            </div>
          </div>
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default WithdrawPage; 
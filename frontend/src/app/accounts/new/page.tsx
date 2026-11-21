'use client';

import React, { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import Input from '@/components/ui/Input';
import PageHeader from '@/components/ui/PageHeader';
import { AccountsAPI } from '@/lib/api/accounts';
import { createAccountSchema } from '@/lib/utils/validation';

const accountTypes = ['CHECKING', 'SAVINGS', 'CREDIT', 'INVESTMENT'] as const;

const CreateAccountPage: React.FC = () => {
  const router = useRouter();
  const [submitError, setSubmitError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(createAccountSchema),
    defaultValues: {
      accountType: 'CHECKING',
      currency: 'USD',
    },
  });

  const onSubmit = async (data: {
    accountType: 'CHECKING' | 'SAVINGS' | 'CREDIT' | 'INVESTMENT';
    currency: string;
    initialBalance?: number;
  }) => {
    setSubmitError(null);
    const response = await AccountsAPI.createAccount({
      accountType: data.accountType,
      currency: data.currency,
      ...(data.initialBalance === undefined
        ? {}
        : { initialBalance: data.initialBalance }),
    });

    if (response.success && response.data) {
      router.push(`/accounts/${response.data.id}`);
      return;
    }

    setSubmitError(response.message || 'The account could not be created.');
  };

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className='mx-auto max-w-lg'>
          <PageHeader
            eyebrow='Accounts'
            title='Open an account'
            description='Choose a type, an opening balance, and a currency. Use AUD if you plan to send outbound payments.'
          />

          <form
            onSubmit={handleSubmit(onSubmit)}
            className='space-y-5 rounded-3xl border border-line bg-surface p-6'
            noValidate
          >
            {submitError && (
              <p
                role='alert'
                className='rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800'
              >
                {submitError}
              </p>
            )}

            <div className='space-y-1'>
              <label
                htmlFor='accountType'
                className='block text-sm font-medium text-ink'
              >
                Account type
              </label>
              <select
                id='accountType'
                className='block w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass'
                {...register('accountType')}
              >
                {accountTypes.map(type => (
                  <option key={type} value={type}>
                    {type.charAt(0) + type.slice(1).toLowerCase()}
                  </option>
                ))}
              </select>
              {errors.accountType && (
                <p className='text-sm text-red-600'>
                  {errors.accountType.message}
                </p>
              )}
            </div>

            <Input
              id='initialBalance'
              label='Opening balance'
              type='number'
              step='0.01'
              min='0'
              placeholder='0.00'
              helperText='Optional. Leave blank to open the account at zero.'
              error={errors.initialBalance?.message}
              {...register('initialBalance', {
                setValueAs: (value: string) =>
                  value === '' || value == null ? undefined : Number(value),
              })}
            />

            <Input
              id='currency'
              label='Currency'
              maxLength={3}
              autoCapitalize='characters'
              helperText='Three-letter code. USD is the default. AUD is used for outbound payments.'
              error={errors.currency?.message}
              {...register('currency', {
                setValueAs: (value: string) => (value ?? '').toUpperCase(),
              })}
            />

            <div className='flex flex-wrap gap-3 pt-2'>
              <Button type='submit' isLoading={isSubmitting}>
                {isSubmitting ? 'Opening account' : 'Open account'}
              </Button>
              <Link
                href='/accounts'
                className='inline-flex items-center justify-center rounded-full border border-line bg-surface px-4 py-2 text-base font-medium text-ink hover:bg-[#f6f1e6] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass'
              >
                Cancel
              </Link>
            </div>
          </form>
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default CreateAccountPage;

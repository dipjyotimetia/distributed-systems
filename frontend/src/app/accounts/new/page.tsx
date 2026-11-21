'use client';

import { FormEvent, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import PageHeader from '@/components/ui/PageHeader';
import Button from '@/components/ui/Button';
import { AccountsAPI, type CreateAccountRequest } from '@/lib/api/accounts';

const accountTypes = ['CHECKING', 'SAVINGS', 'CREDIT', 'INVESTMENT'] as const;
const fieldClass = 'mt-1 w-full rounded-xl border border-line bg-surface p-2.5 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass';

export default function NewAccountPage() {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout>
        <CreateAccountForm />
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
}

function CreateAccountForm() {
  const router = useRouter();
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const accountType = String(data.get('accountType')) as CreateAccountRequest['accountType'];
    const balanceText = String(data.get('initialBalance') || '').trim();
    const currency = String(data.get('currency') || '').trim();
    const initialBalance = balanceText === '' ? undefined : Number(balanceText);
    if (initialBalance != null && (!Number.isFinite(initialBalance) || initialBalance < 0)) {
      setError('Opening balance cannot be negative.');
      return;
    }
    if (currency && !/^[A-Z]{3}$/.test(currency)) {
      setError('Currency must be a three-letter uppercase code.');
      return;
    }
    setPending(true);
    setError('');
    const response = await AccountsAPI.createAccount({
      accountType,
      ...(initialBalance != null ? { initialBalance } : {}),
      ...(currency ? { currency } : {}),
    });
    setPending(false);
    if (!response.success || !response.data) {
      setError(response.message || 'Account was not created');
      return;
    }
    router.push(`/accounts/${response.data.id}`);
  }

  return (
    <div className="mx-auto max-w-xl">
      <PageHeader
        eyebrow="Accounts"
        title="Create an account"
        description="Open a checking, savings, credit, or investment account."
      />
      <form onSubmit={submit} className="space-y-4 rounded-3xl border border-line bg-surface p-5 sm:p-8">
        {error && <p role="alert" className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}</p>}
        <label className="block text-sm font-medium text-ink">
          Account type
          <select name="accountType" className={fieldClass} defaultValue="CHECKING" required>
            {accountTypes.map((type) => <option key={type} value={type}>{type}</option>)}
          </select>
        </label>
        <label className="block text-sm font-medium text-ink">
          Opening balance
          <input name="initialBalance" type="number" min="0" step="0.01" className={fieldClass} />
        </label>
        <label className="block text-sm font-medium text-ink">
          Currency
          <input name="currency" maxLength={3} placeholder="USD" className={fieldClass} />
        </label>
        <div className="flex flex-wrap gap-3">
          <Button type="submit" disabled={pending}>{pending ? 'Creating account' : 'Create account'}</Button>
          <Link href="/accounts" className="inline-flex items-center rounded-full border border-line px-4 py-2 text-sm font-medium text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  );
}

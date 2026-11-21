import { connection } from 'next/server';
import ReconciliationQueue from './ReconciliationQueue';
import PaymentsUnavailable from '@/components/payments/PaymentsUnavailable';

export default async function PaymentsOperationsPage() {
  await connection();
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true') {
    return (
      <PaymentsUnavailable
        title='Payment reconciliation'
        description='Review settlement discrepancies when the payments service is running.'
      />
    );
  }
  return <ReconciliationQueue />;
}

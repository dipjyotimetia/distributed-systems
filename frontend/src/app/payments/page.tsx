import { connection } from 'next/server';
import PaymentForm from './PaymentForm';
import PaymentsUnavailable from '@/components/payments/PaymentsUnavailable';

export default async function PaymentsPage() {
  await connection();
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true') {
    return (
      <PaymentsUnavailable
        title='Outbound payments'
        description='Send an AUD payment from an active AUD account when the payments service is running.'
      />
    );
  }
  return <PaymentForm />;
}

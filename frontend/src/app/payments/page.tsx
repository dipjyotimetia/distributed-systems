import { connection } from 'next/server';
import { notFound } from 'next/navigation';
import PaymentForm from './PaymentForm';

export default async function PaymentsPage() {
  await connection();
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true') notFound();
  return <PaymentForm />;
}

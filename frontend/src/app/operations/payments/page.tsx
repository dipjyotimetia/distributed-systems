import { connection } from 'next/server';
import { notFound } from 'next/navigation';
import ReconciliationQueue from './ReconciliationQueue';

export default async function PaymentsOperationsPage() {
  await connection();
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true') notFound();
  return <ReconciliationQueue />;
}

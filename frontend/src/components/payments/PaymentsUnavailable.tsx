import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import PageHeader from '@/components/ui/PageHeader';

export default function PaymentsUnavailable({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout>
        <div className='mx-auto max-w-3xl'>
          <PageHeader
            eyebrow='Payments'
            title={title}
            description={description}
          />
          <p className='rounded-3xl border border-line bg-surface p-6 text-sm text-ink/80'>
            Outbound payments are not enabled in this stack. Start the payments
            overlay to submit AUD payments and review reconciliation.
          </p>
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
}

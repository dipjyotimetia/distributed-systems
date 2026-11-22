import KnowledgePage from '@/components/knowledge/KnowledgePage';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

export default function Page() {
  return (
    <ProtectedRoute>
      <KnowledgePage />
    </ProtectedRoute>
  );
}

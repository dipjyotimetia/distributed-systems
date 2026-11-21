import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import ProfilePage from '../page';

jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/lib/auth/context', () => ({
  useAuth: () => ({
    user: {
      id: 7, username: 'ada', email: 'ada@example.com', roles: 'USER',
      createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-02T00:00:00Z',
    },
  }),
}));

it('shows the identity fields the user service returns', () => {
  render(<ProfilePage />);
  expect(screen.getByText('ada')).toBeInTheDocument();
  expect(screen.getByText('ada@example.com')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Change Password' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Update Profile' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Security Settings' })).not.toBeInTheDocument();
});

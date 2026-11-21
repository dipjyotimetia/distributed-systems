import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import NotificationsPage from '../page';

jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);

const fetchMock = jest.fn();
const originalFetch = global.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock;
});
afterAll(() => { global.fetch = originalFetch; });

it('shows an empty inbox', async () => {
  fetchMock.mockResolvedValue({ ok: true, json: async () => [] });
  render(<NotificationsPage />);
  expect(await screen.findByText('No notifications yet.')).toBeInTheDocument();
});

it('reports an inbox failure', async () => {
  fetchMock.mockRejectedValue(new Error('offline'));
  render(<NotificationsPage />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Notifications could not be loaded.');
});

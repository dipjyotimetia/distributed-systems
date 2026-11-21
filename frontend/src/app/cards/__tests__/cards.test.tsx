import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import CardsPage from '../page';

jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);

const fetchMock = jest.fn();
const originalFetch = global.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock;
});
afterAll(() => { global.fetch = originalFetch; });

it('loads the signed-in customer cards', async () => {
  fetchMock.mockResolvedValue({
    ok: true,
    json: async () => ({ cards: [{ token: 'card-1', accountId: '4', lastFour: '1111' }] }),
  });
  render(<CardsPage />);
  expect(await screen.findByRole('button', { name: /Card ending 1111/ })).toBeInTheDocument();
  expect(screen.getByText('Account 4')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledWith('/api/cards');
});

it('reports a card list failure', async () => {
  fetchMock.mockResolvedValue({ ok: false, json: async () => ({ message: 'Cards could not be loaded' }) });
  render(<CardsPage />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Cards could not be loaded');
});

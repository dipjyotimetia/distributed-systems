import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import CardsPage from '../page';
import { AccountsAPI } from '@/lib/api/accounts';

jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/lib/api/accounts', () => ({ AccountsAPI: { getAccounts: jest.fn() } }));

const fetchMock = jest.fn();
const originalFetch = global.fetch;

const getAccounts = jest.mocked(AccountsAPI.getAccounts);

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock;
  getAccounts.mockReset();
  getAccounts.mockResolvedValue({ success: true, data: [] });
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
  expect(fetchMock).toHaveBeenCalledWith('/api/v1/cards');
});

it('reports a card list failure', async () => {
  fetchMock.mockResolvedValue({ ok: false, json: async () => ({ message: 'Cards could not be loaded' }) });
  render(<CardsPage />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Cards could not be loaded');
});

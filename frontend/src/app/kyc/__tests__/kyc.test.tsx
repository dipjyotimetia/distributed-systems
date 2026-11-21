import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import KycPage from '../page';

jest.mock(
  '@/components/auth/ProtectedRoute',
  () =>
    ({ children }: { children: ReactNode }) =>
      children,
);
jest.mock(
  '@/components/layout/AuthenticatedLayout',
  () =>
    ({ children }: { children: ReactNode }) =>
      children,
);

const fetchMock = jest.fn();
const originalFetch = global.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock;
});

afterAll(() => {
  global.fetch = originalFetch;
});

it('loads KYC status when the page opens', async () => {
  fetchMock.mockResolvedValue({
    ok: true,
    json: async () => ({
      status: 'APPROVED',
      latest: { fullName: 'Ada Lovelace', documentReference: 'DOC-1' },
    }),
  });

  render(<KycPage />);

  expect(await screen.findByText('Approved')).toBeInTheDocument();
  expect(screen.getByText('Ada Lovelace · DOC-1')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledWith('/api/v1/kyc');
  expect(screen.queryByText('not loaded')).not.toBeInTheDocument();
});

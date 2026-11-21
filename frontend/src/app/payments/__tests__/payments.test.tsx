import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import PaymentForm from '../PaymentForm';
import ReconciliationQueue from '../../operations/payments/ReconciliationQueue';
import { AccountsAPI } from '@/lib/api/accounts';

jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/lib/api/accounts', () => ({ AccountsAPI: { getAccounts: jest.fn() } }));

const accounts = jest.mocked(AccountsAPI.getAccounts);
const fetchMock = jest.fn();
const originalFetch = global.fetch;
const paymentId = '597a4c24-019f-4ed6-884f-bf7cf8486f65';

beforeEach(() => {
  accounts.mockReset();
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(response(200, []));
  global.fetch = fetchMock;
});
afterAll(() => { global.fetch = originalFetch; });

function response(status: number, payload?: unknown) {
  return { status, ok: status >= 200 && status < 300, json: async () => payload };
}

it('submits from an AUD account and refreshes the settlement reference', async () => {
  accounts.mockResolvedValue({ success: true, data: [
    { id: 11, currency: 'AUD', status: 'ACTIVE', accountNumber: '00000111', accountType: 'CHECKING' },
    { id: 12, currency: 'USD', status: 'ACTIVE' },
    { id: 13, currency: 'AUD', status: 'FROZEN' },
  ] } as Awaited<ReturnType<typeof accounts>>);
  fetchMock.mockResolvedValueOnce(response(200, []))
    .mockResolvedValueOnce(response(202, { id: paymentId, status: 'PENDING' }))
    .mockResolvedValueOnce(response(200, {
      id: paymentId, status: 'SETTLED', clearingReference: 'C00000000001', settlementBusinessDate: '2026-11-20',
    }));
  render(<PaymentForm />);
  const source = await screen.findByRole('option', { name: /ending 0111/ });
  expect(screen.getAllByRole('option')).toHaveLength(2);
  fireEvent.change(screen.getByLabelText('Source account'), { target: { value: source.getAttribute('value') } });
  fireEvent.change(screen.getByLabelText('Destination code'), { target: { value: 'DEST00000001' } });
  fireEvent.change(screen.getByLabelText('Amount in AUD cents'), { target: { value: '2500' } });
  fireEvent.click(screen.getByRole('button', { name: 'Submit payment' }));
  expect(await screen.findAllByText(paymentId)).toHaveLength(2);
  expect(fetchMock).toHaveBeenCalledWith('/api/payments', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({ accountId: 11, destination: 'DEST00000001', amountMinor: 2500 }),
  }));
  fireEvent.click(screen.getByRole('button', { name: 'Refresh status' }));
  expect(await screen.findByText('Clearing reference C00000000001')).toBeInTheDocument();
  expect(screen.getByText('Settlement date 2026-11-20')).toBeInTheDocument();
});

it('lists the customer payments and reports a history failure', async () => {
  accounts.mockResolvedValue({ success: true, data: [] });
  fetchMock.mockResolvedValueOnce(response(200, [{
    id: paymentId, status: 'PENDING', instruction: { destination: 'DEST00000001', amountMinor: 2500 },
  }]));
  const { unmount } = render(<PaymentForm />);
  expect(await screen.findByText(paymentId)).toBeInTheDocument();
  expect(screen.getByText('To DEST00000001')).toBeInTheDocument();
  unmount();

  fetchMock.mockResolvedValueOnce(response(503, { message: 'unavailable' }));
  render(<PaymentForm />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Payments could not be loaded.');
});

it('disables submission when no active AUD account exists', async () => {
  accounts.mockResolvedValue({ success: true, data: [] });
  render(<PaymentForm />);
  expect(await screen.findByText(/need an active AUD account/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Submit payment' })).toBeDisabled();
});

it('reports an account query failure', async () => {
  accounts.mockRejectedValue(new Error('unavailable'));
  render(<PaymentForm />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Accounts could not be loaded');
});

it('renders discrepancies and pages through the read-only queue', async () => {
  const record = { businessDate: '2026-11-20', paymentId, outcome: 'MISSING_RECORD',
    expectedAmountMinor: 2500, observedAmountMinor: null, observedReference: null };
  fetchMock.mockResolvedValueOnce(response(200, Array.from({ length: 100 }, (_, recordNumber) => ({ ...record, recordNumber }))))
    .mockResolvedValueOnce(response(200, []));
  render(<ReconciliationQueue />);
  expect(await screen.findAllByText('MISSING RECORD')).toHaveLength(100);
  expect(screen.getAllByText(/\$25\.00 \/ Unavailable/)).toHaveLength(100);
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  expect(await screen.findByText('No discrepancies on this page.')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenLastCalledWith('/api/operations/payments/reconciliation?offset=100', expect.anything());
});

it.each([
  [403, 'Your account does not have access'],
  [502, 'The reconciliation queue could not be loaded'],
])('explains operator query status %d', async (status, message) => {
  fetchMock.mockResolvedValueOnce(response(status));
  render(<ReconciliationQueue />);
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
});

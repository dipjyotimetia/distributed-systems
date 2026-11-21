import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import NewAccountPage from '../new/page';
import { AccountsAPI } from '@/lib/api/accounts';

const push = jest.fn();

jest.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
jest.mock('next/link', () => ({ __esModule: true, default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a> }));
jest.mock('@/components/auth/ProtectedRoute', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/components/layout/AuthenticatedLayout', () => ({ children }: { children: ReactNode }) => children);
jest.mock('@/lib/api/accounts', () => ({ AccountsAPI: { createAccount: jest.fn() } }));

const createAccount = jest.mocked(AccountsAPI.createAccount);

beforeEach(() => {
  push.mockReset();
  createAccount.mockReset();
});

it('creates an account from the selected type', async () => {
  createAccount.mockResolvedValue({ success: true, data: { id: 44 } } as Awaited<ReturnType<typeof createAccount>>);
  render(<NewAccountPage />);
  fireEvent.change(screen.getByLabelText('Account type'), { target: { value: 'SAVINGS' } });
  fireEvent.change(screen.getByLabelText('Opening balance'), { target: { value: '25' } });
  fireEvent.change(screen.getByLabelText('Currency'), { target: { value: 'AUD' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
  await waitFor(() => expect(push).toHaveBeenCalledWith('/accounts/44'));
  expect(createAccount).toHaveBeenCalledWith({ accountType: 'SAVINGS', initialBalance: 25, currency: 'AUD' });
});

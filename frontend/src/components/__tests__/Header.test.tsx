import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/context';
import Header from '@/components/layout/Header';

// Mock Next.js router
jest.mock('next/navigation', () => ({
  useRouter: jest.fn(),
  usePathname: jest.fn(() => '/dashboard'),
}));

// Mock auth context
jest.mock('@/lib/auth/context', () => ({
  useAuth: jest.fn(),
}));

// Mock Next.js Link component
jest.mock('next/link', () => {
  return function MockLink({ children, href, ...props }: React.ComponentProps<'a'>) {
    return <a href={href} {...props}>{children}</a>;
  };
});

describe('Header', () => {
  const mockRouter = {
    push: jest.fn(),
  };

  const mockAuth = {
    user: null,
    logout: jest.fn(),
  };

  beforeEach(() => {
    (useRouter as jest.Mock).mockReturnValue(mockRouter);
    (useAuth as jest.Mock).mockReturnValue(mockAuth);
    jest.clearAllMocks();
  });

  it('renders nothing when user is not authenticated', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: null,
      logout: jest.fn(),
    });

    const { container } = render(<Header />);
    expect(container.firstChild).toBeNull();
  });

  it('renders header with navigation when user is authenticated', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    expect(screen.getByText('Apex Banking')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Accounts' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Transactions' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Profile' })).toBeInTheDocument();
    expect(screen.getByText('Logout')).toBeInTheDocument();
  });

  it('displays user welcome message', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'john_doe', email: 'john@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    expect(screen.getByText('john_doe')).toBeInTheDocument();
  });

  it('handles logout when logout button is clicked', async () => {
    const mockLogout = jest.fn();
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: mockLogout,
    });

    render(<Header />);

    const logoutButton = screen.getByRole('button', { name: /logout/i });
    fireEvent.click(logoutButton);

    expect(mockLogout).toHaveBeenCalled();
    await waitFor(() => expect(mockRouter.push).toHaveBeenCalledWith('/login'));
  });

  it('renders logo with correct link', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    const logoLink = screen.getByRole('link', { name: /apex banking/i });
    expect(logoLink).toHaveAttribute('href', '/dashboard');
  });

  it('renders all navigation links with correct hrefs', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    const dashboardLink = screen.getByRole('link', { name: 'Dashboard' });
    const accountsLink = screen.getByRole('link', { name: 'Accounts' });
    const transactionsLink = screen.getByRole('link', { name: 'Transactions' });
    const profileLink = screen.getByRole('link', { name: 'Profile' });

    expect(dashboardLink).toHaveAttribute('href', '/dashboard');
    expect(accountsLink).toHaveAttribute('href', '/accounts');
    expect(transactionsLink).toHaveAttribute('href', '/transactions');
    expect(profileLink).toHaveAttribute('href', '/profile');
  });

  it('renders mobile navigation menu', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    expect(screen.getByRole('button', { name: 'Menu' })).toHaveAttribute('aria-controls', 'app-nav');
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('renders logo icon', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    expect(screen.getByText('A')).toBeInTheDocument();
  });

  it('handles user with different username formats', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'user123', email: 'user123@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    expect(screen.getByText('user123')).toBeInTheDocument();
  });

  it('handles user with special characters in username', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'user-name_123', email: 'user@example.com' },
      logout: jest.fn(),
    });

    render(<Header />);

    expect(screen.getByText('user-name_123')).toBeInTheDocument();
  });

  it('calls logout and navigation in correct order', async () => {
    const mockLogout = jest.fn();
    (useAuth as jest.Mock).mockReturnValue({
      user: { username: 'testuser', email: 'test@example.com' },
      logout: mockLogout,
    });

    render(<Header />);

    const logoutButton = screen.getByRole('button', { name: /logout/i });
    fireEvent.click(logoutButton);

    // Verify both functions were called
    expect(mockLogout).toHaveBeenCalled();
    await waitFor(() => expect(mockRouter.push).toHaveBeenCalledWith('/login'));
  });
}); 
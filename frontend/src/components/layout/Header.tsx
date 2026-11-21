'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/context';

const links = [
  { href: '/dashboard', label: 'Dashboard' },
  { href: '/accounts', label: 'Accounts' },
  { href: '/cards', label: 'Cards' },
  { href: '/kyc', label: 'KYC' },
  { href: '/transactions', label: 'Transactions' },
  { href: '/payments', label: 'Payments' },
  { href: '/disputes', label: 'Disputes' },
  { href: '/ai-assistant', label: 'AI Assistant' },
  { href: '/profile', label: 'Profile' },
];

const operations = [
  { href: '/operations/kyc', label: 'KYC review' },
  { href: '/operations/disputes', label: 'Dispute review' },
  { href: '/operations/payments', label: 'Payment reconciliation' },
];

function isCurrent(pathname: string, href: string) {
  if (href === '/dashboard') return pathname === href;
  return pathname === href || pathname.startsWith(`${href}/`);
}

const Header: React.FC = () => {
  const { user, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname() || '';
  const [open, setOpen] = useState(false);
  const [logoutFailed, setLogoutFailed] = useState(false);

  const handleLogout = async () => {
    try {
      await logout();
      router.push('/login');
    } catch {
      setLogoutFailed(true);
    }
  };

  if (!user) {
    return null;
  }

  return (
    <>
      <div className="fixed inset-x-0 top-0 z-30 flex h-16 items-center justify-between border-b border-line bg-surface px-4 lg:hidden">
        <button
          type="button"
          className="rounded-full border border-line px-3 py-1.5 text-sm font-medium text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass"
          aria-expanded={open}
          aria-controls="app-nav"
          onClick={() => setOpen((value) => !value)}
        >
          {open ? 'Close menu' : 'Menu'}
        </button>
        <Link href="/dashboard" className="text-sm font-semibold tracking-tight text-ink">
          Apex
        </Link>
        <span className="w-10" aria-hidden="true" />
      </div>

      {open && (
        <button
          type="button"
          aria-label="Close menu"
          className="fixed inset-0 z-30 bg-ink/40 lg:hidden"
          onClick={() => setOpen(false)}
        />
      )}

      <aside
        id="app-nav"
        className={`${open ? 'translate-x-0' : '-translate-x-full'} fixed inset-y-0 left-0 z-40 flex w-72 flex-col border-r border-line bg-ink text-canvas transition-transform lg:translate-x-0`}
      >
        <div className="px-6 pb-6 pt-7">
          <Link href="/dashboard" className="flex items-center gap-3" onClick={() => setOpen(false)}>
            <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#d7b56a] text-sm font-semibold text-ink">
              A
            </span>
            <span className="text-lg font-semibold tracking-tight">Apex Banking</span>
          </Link>
        </div>

        {logoutFailed && (
          <p role="alert" className="mx-6 mb-4 text-sm text-red-200">
            Logout failed. Please try again.
          </p>
        )}

        <nav aria-label="Primary" className="flex-1 space-y-1 overflow-y-auto px-3">
          {links.map((link) => {
            const current = isCurrent(pathname, link.href);
            return (
              <Link
                key={link.href}
                href={link.href}
                aria-current={current ? 'page' : undefined}
                onClick={() => setOpen(false)}
                className={`block rounded-xl px-3 py-2.5 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass ${
                  current ? 'bg-white/10 text-white' : 'text-canvas/75 hover:bg-white/5 hover:text-white'
                }`}
              >
                {link.label}
              </Link>
            );
          })}
          <p className="px-3 pb-1 pt-6 text-xs font-medium uppercase tracking-[0.16em] text-[#d7b56a]">Operations</p>
          {operations.map((link) => {
            const current = isCurrent(pathname, link.href);
            return (
              <Link
                key={link.href}
                href={link.href}
                aria-current={current ? 'page' : undefined}
                onClick={() => setOpen(false)}
                className={`block rounded-xl px-3 py-2.5 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass ${
                  current ? 'bg-white/10 text-white' : 'text-canvas/75 hover:bg-white/5 hover:text-white'
                }`}
              >
                {link.label}
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-white/10 p-4">
          <p className="px-2 text-xs uppercase tracking-[0.16em] text-[#d7b56a]">Signed in</p>
          <p className="mt-1 truncate px-2 text-sm font-medium">{user.username}</p>
          <button
            type="button"
            onClick={handleLogout}
            className="mt-4 w-full rounded-full border border-white/20 px-4 py-2 text-sm font-medium text-canvas hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brass"
          >
            Logout
          </button>
        </div>
      </aside>
    </>
  );
};

export default Header;

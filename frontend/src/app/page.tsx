'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/context';
import { demoUser } from '@/lib/demo';

export default function Home() {
  const { isAuthenticated, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && isAuthenticated) {
      router.push('/dashboard');
    }
  }, [isAuthenticated, isLoading, router]);

  if (isLoading || isAuthenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-canvas">
        <div className="text-center">
          <div className="mx-auto h-12 w-12 animate-spin rounded-full border-b-2 border-ink"></div>
          <p className="mt-4 text-ink/70">Loading...</p>
        </div>
      </div>
    );
  }

  return (
    <main className="min-h-screen bg-canvas text-ink">
      <div className="mx-auto grid min-h-screen max-w-6xl items-center gap-10 px-4 py-12 sm:px-6 lg:grid-cols-[1.15fr_0.85fr] lg:px-8">
        <section>
          <div className="mb-8 flex h-12 w-12 items-center justify-center rounded-full bg-ink text-lg font-semibold text-canvas">
            A
          </div>
          <p className="text-xs font-medium uppercase tracking-[0.2em] text-brass">Private banking</p>
          <h1 className="mt-3 max-w-xl text-5xl font-semibold tracking-tight text-ink sm:text-6xl">
            Apex Banking
          </h1>
          <p className="mt-5 max-w-xl text-lg text-ink/70">
            Customer accounts, transfers, and assistant workflows in one banking experience.
          </p>
          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <Link
              href="/login"
              className="inline-flex items-center justify-center rounded-full bg-ink px-5 py-3 text-base font-medium text-canvas hover:bg-[#24362f] focus:outline-none focus-visible:ring-2 focus-visible:ring-brass focus-visible:ring-offset-2"
            >
              Sign in
            </Link>
            <Link
              href="/register"
              className="inline-flex items-center justify-center rounded-full border border-line bg-surface px-5 py-3 text-base font-medium text-ink hover:bg-[#f6f1e6] focus:outline-none focus-visible:ring-2 focus-visible:ring-brass focus-visible:ring-offset-2"
            >
              Open account
            </Link>
          </div>
        </section>

        <aside className="rounded-3xl border border-line bg-ink p-6 text-canvas shadow-sm sm:p-8">
          <p className="text-xs font-medium uppercase tracking-[0.18em] text-[#d7b56a]">
            Demo customer
          </p>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight">{demoUser.name}</h2>
          <dl className="mt-6 space-y-5 text-sm">
            <div>
              <dt className="text-canvas/60">Username</dt>
              <dd className="mt-1 font-mono text-canvas">{demoUser.username}</dd>
            </div>
            <div>
              <dt className="text-canvas/60">Password</dt>
              <dd className="mt-1 font-mono text-canvas">{demoUser.password}</dd>
            </div>
            <div>
              <dt className="text-canvas/60">Accounts</dt>
              <dd className="mt-1 text-canvas">Checking and savings</dd>
            </div>
          </dl>
        </aside>
      </div>
    </main>
  );
}

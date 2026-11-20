'use client';

import React from 'react';
import Header from './Header';

interface AuthenticatedLayoutProps {
  children: React.ReactNode;
}

const AuthenticatedLayout: React.FC<AuthenticatedLayoutProps> = ({ children }) => {
  return (
    <div className="min-h-screen bg-canvas text-ink">
      <Header />
      <div className="lg:pl-72">
        <main className="min-h-screen px-4 pb-10 pt-20 sm:px-6 lg:px-8 lg:pt-8">
          {children}
        </main>
      </div>
    </div>
  );
};

export default AuthenticatedLayout;

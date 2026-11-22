'use client';

import { useEffect } from 'react';
import { registerBrowserOtel } from '@/lib/otel/browser';

export function OtelProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    void registerBrowserOtel();
  }, []);
  return children;
}

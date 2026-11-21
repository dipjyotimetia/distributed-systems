'use client';

import React, { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { loginSchema, LoginFormData } from '@/lib/utils/validation';
import { useAuth } from '@/lib/auth/context';
import Button from '@/components/ui/Button';
import Input from '@/components/ui/Input';
import ToastContainer, { useToasts } from '@/components/ui/ToastContainer';
import { demoUser } from '@/lib/demo';

const LoginForm: React.FC = () => {
  const router = useRouter();
  const { login } = useAuth();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { toasts, removeToast, showError } = useToasts();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      usernameOrEmail: demoUser.username,
      password: demoUser.password,
    },
  });

  const onSubmit = async (data: LoginFormData) => {
    setIsSubmitting(true);
    
    // Clear any existing error toasts when starting a new submission
    toasts.forEach(toast => {
      if (toast.type === 'error') {
        removeToast(toast.id);
      }
    });

    try {
      const response = await login(data);
      
      if (response.success) {
        router.push('/dashboard');
      } else {
        showError(
          'Sign in failed',
          response.message || 'Please check your credentials and try again.'
        );
      }
    } catch {
      showError(
        'Sign in failed',
        'An unexpected error occurred. Please try again.'
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <>
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
      
      <div className="grid min-h-screen bg-canvas lg:grid-cols-[0.9fr_1.1fr]">
        <aside className="hidden flex-col justify-between bg-ink px-10 py-12 text-canvas lg:flex">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-[#d7b56a] font-semibold text-ink">A</div>
          <div>
            <p className="text-xs font-medium uppercase tracking-[0.2em] text-[#d7b56a]">Private client</p>
            <p className="mt-4 max-w-sm text-4xl font-semibold tracking-tight">A quieter way to hold and move money.</p>
          </div>
          <p className="text-sm text-canvas/60">Secure Digital Banking Platform</p>
        </aside>
        <div className="flex items-center justify-center px-4 py-12 sm:px-6">
        <div className="w-full max-w-md space-y-8">
          <div>
            <div className="mb-4 flex justify-center lg:justify-start">
              <div className="flex h-12 w-12 items-center justify-center rounded-full bg-ink lg:hidden">
                <span className="text-lg font-semibold text-canvas">A</span>
              </div>
            </div>
            <p className="text-center text-xs font-medium uppercase tracking-[0.18em] text-brass lg:text-left">
              Apex Banking
            </p>
            <h2 className="mt-3 text-center text-3xl font-semibold tracking-tight text-ink lg:text-left">
              Sign in to your account
            </h2>
            <p className="mt-1 text-center text-sm text-ink/60 lg:text-left">
              Secure Digital Banking Platform
            </p>
            <p className="mt-4 text-center text-sm text-ink/70 lg:text-left">
              Or{' '}
              <Link
                href="/register"
                className="font-medium text-brass hover:text-ink"
              >
                create a new account
              </Link>
            </p>
          </div>

          <form className="mt-8 space-y-6" onSubmit={handleSubmit(onSubmit)}>
            <div className="space-y-4">
              <Input
                label="Username or Email"
                type="text"
                autoComplete="username"
                {...register('usernameOrEmail')}
                error={errors.usernameOrEmail?.message}
                placeholder="Enter your username or email"
              />

              <Input
                label="Password"
                type="password"
                autoComplete="current-password"
                {...register('password')}
                error={errors.password?.message}
                placeholder="Enter your password"
              />
            </div>

            <div className="flex items-center justify-between">
              <div className="flex items-center">
                <input
                  id="remember-me"
                  name="remember-me"
                  type="checkbox"
                  className="h-4 w-4 rounded border-line text-ink focus:ring-brass"
                />
                <label htmlFor="remember-me" className="ml-2 block text-sm text-ink">
                  Remember me
                </label>
              </div>

              <div className="text-sm">
                <a href="#" className="font-medium text-brass hover:text-ink">
                  Forgot your password?
                </a>
              </div>
            </div>

            <Button
              type="submit"
              size="lg"
              isLoading={isSubmitting}
              disabled={isSubmitting}
              className="w-full"
            >
              {isSubmitting ? 'Signing in...' : 'Sign in'}
            </Button>
          </form>

          <div className="mt-4 rounded-2xl border border-line bg-surface p-3">
            <p className="text-center text-xs text-ink/60">
              Demo customer:{' '}
              <span className="font-mono font-medium text-ink">
                {demoUser.username}
              </span>{' '}
              /{' '}
              <span className="font-mono font-medium text-ink">
                {demoUser.password}
              </span>
            </p>
          </div>
        </div>
        </div>
      </div>
    </>
  );
};

export default LoginForm;

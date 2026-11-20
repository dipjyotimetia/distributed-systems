'use client';

import React from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import { useAuth } from '@/lib/auth/context';
import Button from '@/components/ui/Button';
import PageHeader from '@/components/ui/PageHeader';

const ProfilePage: React.FC = () => {
  const { user } = useAuth();

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className="mx-auto max-w-3xl">
          <PageHeader eyebrow="Profile" title="User Profile" description="Identity details stored with your Apex account." />

          {user && (
            <div className="space-y-6">
              <div className="rounded-3xl border border-line bg-surface p-6">
                <h2 className="mb-4 text-lg font-medium text-ink">Account Information</h2>
                <dl className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <div>
                    <dt className="text-sm font-medium text-ink/60">User ID</dt>
                    <dd className="mt-1 text-sm text-ink">{user.id}</dd>
                  </div>
                  <div>
                    <dt className="text-sm font-medium text-ink/60">Username</dt>
                    <dd className="mt-1 text-sm text-ink">{user.username}</dd>
                  </div>
                  <div>
                    <dt className="text-sm font-medium text-ink/60">Email</dt>
                    <dd className="mt-1 text-sm text-ink">{user.email}</dd>
                  </div>
                  <div>
                    <dt className="text-sm font-medium text-ink/60">Roles</dt>
                    <dd className="mt-1 text-sm text-ink">{user.roles}</dd>
                  </div>
                  <div>
                    <dt className="text-sm font-medium text-ink/60">Account Created</dt>
                    <dd className="mt-1 text-sm text-ink">{new Date(user.createdAt).toLocaleDateString()}</dd>
                  </div>
                  <div>
                    <dt className="text-sm font-medium text-ink/60">Last Updated</dt>
                    <dd className="mt-1 text-sm text-ink">{new Date(user.updatedAt).toLocaleDateString()}</dd>
                  </div>
                </dl>
              </div>

              <div className="border-t border-line pt-6">
                <h3 className="mb-4 text-lg font-medium text-ink">Account Actions</h3>
                <div className="flex flex-wrap gap-3">
                  <Button variant="outline">Change Password</Button>
                  <Button variant="outline">Update Profile</Button>
                  <Button variant="outline">Security Settings</Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default ProfilePage;

'use client';

import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { AuthContextType, AuthProviderProps, User, LoginRequest, RegisterRequest, AuthResponse } from '../types/auth';
import { AuthAPI } from '../api/auth';

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isAuthenticated, setIsAuthenticated] = useState(false);

  // Restore the authenticated user from the server-side session.
  useEffect(() => {
    let active = true;
    void AuthAPI.getProfile().then(profile => {
      if (active && profile.success && profile.data) {
        setUser(profile.data);
        setIsAuthenticated(true);
      }
    }).catch(() => {
      // Loading a session can fail if the profile endpoint is unreachable.
    }).finally(() => {
      if (active) setIsLoading(false);
    });
    return () => { active = false; };
  }, []);

  const login = useCallback(async (credentials: LoginRequest): Promise<AuthResponse> => {
    setIsLoading(true);
    
    try {
      const response = await AuthAPI.login(credentials);
      
      if (response.success && response.data) {
        const { id, username, email, roles } = response.data;
        
        // Set user state
        const userData: User = {
          id,
          username,
          email,
          roles,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
        
        setUser(userData);
        setIsAuthenticated(true);
      }
      
      return response;
    } catch {
      return {
        success: false,
        message: 'Login failed',
        errors: ['An unexpected error occurred'],
      };
    } finally {
      setIsLoading(false);
    }
  }, []);

  const register = useCallback(async (userData: RegisterRequest): Promise<AuthResponse> => {
    setIsLoading(true);
    
    try {
      const response = await AuthAPI.register(userData);
      
      if (response.success) {
        // After successful registration, user needs to login
        // We don't auto-login after registration for security
      }
      
      return response;
    } catch {
      return {
        success: false,
        message: 'Registration failed',
        errors: ['An unexpected error occurred'],
      };
    } finally {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    await AuthAPI.logout();
    setUser(null);
    setIsAuthenticated(false);
  }, []);

  const refreshToken = useCallback(async (): Promise<boolean> => {
    try {
      const profile = await AuthAPI.getProfile();
      if (profile.success && profile.data) {
        setUser(profile.data);
        setIsAuthenticated(true);
        return true;
      }
    } catch {
      // Treat an unreachable profile endpoint as an unverified session.
    }
    setUser(null);
    setIsAuthenticated(false);
    return false;
  }, []);

  // Auto-refresh token every 5 minutes
  useEffect(() => {
    if (isAuthenticated) {
      const interval = setInterval(() => {
        refreshToken();
      }, 5 * 60 * 1000); // 5 minutes

      return () => clearInterval(interval);
    }
  }, [isAuthenticated, refreshToken]);

  const value: AuthContextType = {
    user,
    isLoading,
    isAuthenticated,
    login,
    register,
    logout,
    refreshToken,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};
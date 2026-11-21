// Export all API modules
export { default as apiClient } from './client';
export type { ApiResponse, PaginatedResponse } from './client';

export { AuthAPI } from './auth';
export type { 
  LoginRequest, 
  RegisterRequest, 
  AuthResponse, 
  User as AuthUser 
} from '../types/auth';

export { AccountsAPI } from './accounts';
export type { 
  Account, 
  CreateAccountRequest, 
  UpdateAccountRequest, 
  AccountSummary 
} from './accounts';

export { TransactionsAPI } from './transactions';
export type { 
  Transaction, 
  CreateTransactionRequest, 
  TransactionSummary, 
  TransactionFilters 
} from './transactions';

export { UsersAPI } from './users';
export type { 
  User as UserEntity, 
  UpdateUserRequest, 
  ChangePasswordRequest, 
  UserProfile, 
  UserPreferences 
} from './users';

// Re-export commonly used types
export type { User } from '../types/auth';

// API endpoints configuration
export const API_ENDPOINTS = {
  AUTH: {
    LOGIN: '/api/v1/users/login',
    REGISTER: '/api/v1/users/register',
    LOGOUT: '/api/v1/users/logout',
    REFRESH: '/api/v1/users/refresh',
    PROFILE: '/api/v1/users/profile',
  },
  ACCOUNTS: {
    BASE: '/api/v1/accounts',
    SUMMARY: '/api/v1/accounts/summary',
    BALANCE: (id: number) => `/api/v1/accounts/${id}/balance`,
    STATUS: (id: number) => `/api/v1/accounts/${id}/status`,
    TRANSACTIONS: (id: number) => `/api/v1/accounts/${id}/transactions`,
    SEARCH: '/api/v1/accounts/search',
  },
  TRANSACTIONS: {
    BASE: '/api/v1/transactions',
    SUMMARY: '/api/v1/transactions/summary',
    RECENT: '/api/v1/transactions/recent',
    SEARCH: '/api/v1/transactions/search',
    CANCEL: (id: number) => `/api/v1/transactions/${id}/cancel`,
    EXPORT: '/api/v1/transactions/export',
    REPORTS: {
      MONTHLY: '/api/v1/transactions/reports/monthly',
    },
  },
  USERS: {
    BASE: '/api/v1/users',
    PROFILE: '/api/v1/users/profile',
    PASSWORD: '/api/v1/users/password',
    PREFERENCES: '/api/v1/users/preferences',
    VERIFY_EMAIL: '/api/v1/users/verify-email',
    VERIFY_PHONE: '/api/v1/users/verify-phone',
    PASSWORD_RESET: '/api/v1/users/password-reset',
    TWO_FACTOR: '/api/v1/users/2fa',
    ACTIVITY: '/api/v1/users/activity',
    SESSIONS: '/api/v1/users/sessions',
    AVATAR: '/api/v1/users/avatar',
    SEARCH: '/api/v1/users/search',
    CHECK_USERNAME: '/api/v1/users/check-username',
    CHECK_EMAIL: '/api/v1/users/check-email',
  },
} as const;

// HTTP status codes
export const HTTP_STATUS = {
  OK: 200,
  CREATED: 201,
  NO_CONTENT: 204,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  UNPROCESSABLE_ENTITY: 422,
  INTERNAL_SERVER_ERROR: 500,
  SERVICE_UNAVAILABLE: 503,
} as const;

// Common error messages
export const ERROR_MESSAGES = {
  NETWORK_ERROR: 'Network error. Please check your connection.',
  TIMEOUT_ERROR: 'Request timeout. Please try again.',
  UNAUTHORIZED: 'Unauthorized. Please log in again.',
  FORBIDDEN: 'Forbidden. You do not have permission to perform this action.',
  NOT_FOUND: 'Not found. The requested resource does not exist.',
  VALIDATION_ERROR: 'Validation error. Please check your input.',
  CONFLICT: 'Conflict. The request could not be completed due to a conflict.',
  INTERNAL_ERROR: 'Internal server error. Please try again later.',
  SERVICE_UNAVAILABLE: 'Service unavailable. Please try again later.',
} as const;

// Utility functions for API responses
import type { ApiResponse as ApiResponseType } from './client';

export const ApiUtils = {
  isSuccess: <T>(response: ApiResponseType<T>): response is ApiResponseType<T> & { success: true; data: T } => {
    return response.success;
  },

  isError: <T>(response: ApiResponseType<T>): response is ApiResponseType<T> & { success: false; message: string } => {
    return !response.success;
  },

  getErrorMessage: <T>(response: ApiResponseType<T>): string => {
    if (response.success) return '';
    return response.message || 'An error occurred';
  },

  getErrorMessages: <T>(response: ApiResponseType<T>): string[] => {
    if (response.success) return [];
    return response.errors || [response.message || 'An error occurred'];
  },

  hasData: <T>(response: ApiResponseType<T>): response is ApiResponseType<T> & { data: T } => {
    return response.success && response.data !== undefined;
  },

  getData: <T>(response: ApiResponseType<T>): T | null => {
    return response.success ? response.data || null : null;
  },
};

// Export default API client
export { apiClient as default } from './client';
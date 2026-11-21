import axios from 'axios';
import type { LoginRequest } from '../../gen/http/LoginRequest';
import type { ProfileResponse } from '../../gen/http/ProfileResponse';
import type { RegisterRequest } from '../../gen/http/RegisterRequest';
import { AuthResponse, User } from '../types/auth';
import type { Wire } from './wire';

export type { LoginRequest, RegisterRequest };
export type UserProfile = Wire<ProfileResponse>;

// Always use relative URLs - Next.js will handle API routing
const API_BASE_URL = '';
const API_TIMEOUT = 30000;

// Create axios instance for auth API
const authAPI = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
});

export class AuthAPI {
  // User registration
  static async register(userData: RegisterRequest): Promise<AuthResponse> {
    try {
      const response = await authAPI.post('/api/users/register', userData);
      // Handle direct token response from server
      const tokenData = response.data;
      return {
        success: true,
        message: 'Registration successful',
        data: {
          type: tokenData.type,
          id: tokenData.id,
          username: tokenData.username,
          email: tokenData.email,
          roles: tokenData.roles,
        }
      };
    } catch (error: unknown) {
      const axiosError = error as { response?: { data?: { message?: string; errors?: string[] } } };
      return {
        success: false,
        message: axiosError.response?.data?.message || 'Registration failed',
        errors: axiosError.response?.data?.errors || ['Registration failed'],
      };
    }
  }

  // User login
  static async login(credentials: LoginRequest): Promise<AuthResponse> {
    try {
      const response = await authAPI.post('/api/users/login', credentials, { headers: { 'X-Banking-Session': 'cookie' } });
      // Handle direct token response from server
      const tokenData = response.data;
      return {
        success: true,
        message: 'Login successful',
        data: {
          type: tokenData.type,
          id: tokenData.id,
          username: tokenData.username,
          email: tokenData.email,
          roles: tokenData.roles,
        }
      };
    } catch (error: unknown) {
      const axiosError = error as { response?: { data?: { message?: string; errors?: string[] } } };
      return {
        success: false,
        message: axiosError.response?.data?.message || 'Login failed',
        errors: axiosError.response?.data?.errors || ['Login failed'],
      };
    }
  }

  static async logout(): Promise<void> {
    await authAPI.post('/api/users/logout');
  }

  // Get user profile
  static async getProfile(): Promise<{ success: boolean; data?: User; message?: string }> {
    try {
      const response = await authAPI.get('/api/users/profile');
      return response.data;
    } catch (error: unknown) {
      const axiosError = error as { response?: { data?: { message?: string } } };
      return {
        success: false,
        message: axiosError.response?.data?.message || 'Failed to fetch profile',
      };
    }
  }

  // Check username availability
  static async checkUsername(username: string): Promise<{ available: boolean }> {
    try {
      const response = await authAPI.get(`/api/users/check-username?username=${username}`);
      return response.data;
    } catch {
      return { available: false };
    }
  }

  // Check email availability
  static async checkEmail(email: string): Promise<{ available: boolean }> {
    try {
      const response = await authAPI.get(`/api/users/check-email?email=${email}`);
      return response.data;
    } catch {
      return { available: false };
    }
  }
}
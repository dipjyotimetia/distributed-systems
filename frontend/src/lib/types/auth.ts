import type { LoginRequest as GeneratedLoginRequest } from '../../gen/http/LoginRequest';
import type { RegisterRequest as GeneratedRegisterRequest } from '../../gen/http/RegisterRequest';
import type { UserIdentity } from '../../gen/http/UserIdentity';
import type { Wire } from '../api/wire';

// Authentication types. Request bodies and the profile identity come from the generated HTTP models.
export type User = Wire<UserIdentity>;
export type LoginRequest = GeneratedLoginRequest;
export type RegisterRequest = GeneratedRegisterRequest;

export interface AuthResponse {
  success: boolean;
  message: string;
  data?: {
    token?: string;
    type: string;
    id: number;
    username: string;
    email: string;
    roles: string;
  };
  errors?: string[];
}

export interface AuthContextType {
  user: User | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (credentials: LoginRequest) => Promise<AuthResponse>;
  register: (userData: RegisterRequest) => Promise<AuthResponse>;
  logout: () => Promise<void>;
  refreshToken: () => Promise<boolean>;
}

export interface AuthProviderProps {
  children: React.ReactNode;
}

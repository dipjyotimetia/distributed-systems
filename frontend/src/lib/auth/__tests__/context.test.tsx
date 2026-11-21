import React from 'react'
import { render, screen, waitFor, act } from '@testing-library/react'
import { AuthProvider, useAuth } from '../context'
import { AuthAPI } from '../../api/auth'

jest.mock('../../api/auth', () => ({
  AuthAPI: {
    login: jest.fn(),
    register: jest.fn(),
    getProfile: jest.fn(),
    logout: jest.fn(),
  },
}))

const mockAuthAPI = AuthAPI as jest.Mocked<typeof AuthAPI>

// Test component that uses the auth context
const TestComponent: React.FC = () => {
  const { user, isLoading, isAuthenticated, login, register, logout, refreshToken } = useAuth()

  return (
    <div>
      <div data-testid="loading">{isLoading ? 'Loading' : 'Not Loading'}</div>
      <div data-testid="authenticated">{isAuthenticated ? 'Authenticated' : 'Not Authenticated'}</div>
      <div data-testid="user">{user ? user.username : 'No User'}</div>
      <button onClick={() => login({ usernameOrEmail: 'test', password: 'password' })}>
        Login
      </button>
      <button onClick={() => register({ username: 'test', email: 'test@example.com', password: 'password' })}>
        Register
      </button>
      <button onClick={logout}>Logout</button>
      <button onClick={refreshToken}>Refresh Token</button>
    </div>
  )
}

describe('AuthContext', () => {
  beforeEach(() => {
    jest.resetAllMocks()
    mockAuthAPI.getProfile.mockResolvedValue({ success: false })
    mockAuthAPI.logout.mockResolvedValue(undefined)
  })

  it('should throw error when used outside provider', () => {
    // Suppress console.error for this test
    const consoleSpy = jest.spyOn(console, 'error').mockImplementation(() => {})
    
    expect(() => {
      render(<TestComponent />)
    }).toThrow('useAuth must be used within an AuthProvider')
    
    consoleSpy.mockRestore()
  })

  it('should provide initial state when no token exists', async () => {
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
  })

  it('should initialize with valid token and user profile', async () => {
    const mockUser = {
      id: 1,
      username: 'testuser',
      email: 'test@example.com',
      roles: ['USER'],
      createdAt: '2023-01-01T00:00:00Z',
      updatedAt: '2023-01-01T00:00:00Z',
    }
    
    mockAuthAPI.getProfile.mockResolvedValue({
      success: true,
      data: mockUser,
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('testuser')
  })

  it('should handle expired token during initialization', async () => {
    
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
  })

  it('should handle profile fetch failure during initialization', async () => {
    mockAuthAPI.getProfile.mockResolvedValue({
      success: false,
      message: 'Profile fetch failed',
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
  })

  it('should handle successful login', async () => {
    mockAuthAPI.login.mockResolvedValue({
      success: true,
      message: 'Login successful',
      data: {
        type: 'Bearer',
        id: 1,
        username: 'testuser',
        email: 'test@example.com',
        roles: ['USER'],
      },
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    await act(async () => {
      screen.getByRole('button', { name: 'Login' }).click()
    })
    
    await waitFor(() => {
      expect(screen.getByTestId('authenticated')).toHaveTextContent('Authenticated')
    })
    
    expect(screen.getByTestId('user')).toHaveTextContent('testuser')
  })

  it('should handle failed login', async () => {
    mockAuthAPI.login.mockResolvedValue({
      success: false,
      message: 'Invalid credentials',
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    await act(async () => {
      screen.getByRole('button', { name: 'Login' }).click()
    })
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
  })

  it('should handle successful registration', async () => {
    mockAuthAPI.register.mockResolvedValue({
      success: true,
      message: 'Registration successful',
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    await act(async () => {
      screen.getByRole('button', { name: 'Register' }).click()
    })
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    // Registration doesn't auto-login
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
  })

  it('should handle logout', async () => {
    const mockUser = {
      id: 1,
      username: 'testuser',
      email: 'test@example.com',
      roles: ['USER'],
      createdAt: '2023-01-01T00:00:00Z',
      updatedAt: '2023-01-01T00:00:00Z',
    }
    
    mockAuthAPI.getProfile.mockResolvedValue({
      success: true,
      data: mockUser,
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('authenticated')).toHaveTextContent('Authenticated')
    })
    
    await act(async () => {
      screen.getByRole('button', { name: 'Logout' }).click()
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
    expect(mockAuthAPI.logout).toHaveBeenCalledTimes(1)
  })

  it('should handle successful token refresh', async () => {
    const mockUser = {
      id: 1,
      username: 'testuser',
      email: 'test@example.com',
      roles: ['USER'],
      createdAt: '2023-01-01T00:00:00Z',
      updatedAt: '2023-01-01T00:00:00Z',
    }
    
    mockAuthAPI.getProfile.mockResolvedValue({
      success: true,
      data: mockUser,
    })
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('authenticated')).toHaveTextContent('Authenticated')
    })
    
    await act(async () => {
      screen.getByRole('button', { name: 'Refresh Token' }).click()
    })
    
    await waitFor(() => {
      expect(screen.getByTestId('authenticated')).toHaveTextContent('Authenticated')
    })
    
    expect(screen.getByTestId('user')).toHaveTextContent('testuser')
  })

  it('should handle failed token refresh', async () => {
    
    
    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    )
    
    await waitFor(() => {
      expect(screen.getByTestId('loading')).toHaveTextContent('Not Loading')
    })
    
    await act(async () => {
      screen.getByRole('button', { name: 'Refresh Token' }).click()
    })
    
    expect(screen.getByTestId('authenticated')).toHaveTextContent('Not Authenticated')
    expect(screen.getByTestId('user')).toHaveTextContent('No User')
  })
})
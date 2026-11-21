import type { AccountCreateRequest as GeneratedCreateAccountRequest } from '../../gen/http/AccountCreateRequest';
import type { AccountResponse } from '../../gen/http/AccountResponse';
import type { AccountSummary as GeneratedAccountSummary } from '../../gen/http/AccountSummary';
import type { AccountUpdateRequest as GeneratedUpdateAccountRequest } from '../../gen/http/AccountUpdateRequest';
import type { BalanceResponse } from '../../gen/http/BalanceResponse';
import { apiClient, ApiResponse, PaginatedResponse } from './client';
import type { Wire } from './wire';

export type Account = Wire<AccountResponse>;
export type CreateAccountRequest = Wire<GeneratedCreateAccountRequest>;
export type UpdateAccountRequest = Wire<GeneratedUpdateAccountRequest>;
export type AccountSummary = Wire<GeneratedAccountSummary>;

export class AccountsAPI {
  // Get all accounts for the current user
  static async getAccounts(): Promise<ApiResponse<Account[]>> {
    return await apiClient.get<Account[]>('/api/accounts');
  }

  // Get paginated accounts
  static async getAccountsPaginated(
    page: number = 0,
    size: number = 10,
    sort: string = 'createdAt,desc'
  ): Promise<ApiResponse<PaginatedResponse<Account>>> {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
      sort,
    });

    return await apiClient.get<PaginatedResponse<Account>>(`/api/accounts?${params}`);
  }

  // Get account by ID
  static async getAccount(accountId: number): Promise<ApiResponse<Account>> {
    return await apiClient.get<Account>(`/api/accounts/${accountId}`);
  }

  // Create new account
  static async createAccount(accountData: CreateAccountRequest): Promise<ApiResponse<Account>> {
    return await apiClient.post<Account>('/api/accounts', accountData);
  }

  // Update account
  static async updateAccount(
    accountId: number,
    accountData: UpdateAccountRequest
  ): Promise<ApiResponse<Account>> {
    return await apiClient.put<Account>(`/api/accounts/${accountId}`, accountData);
  }

  // Delete account
  static async deleteAccount(accountId: number): Promise<ApiResponse<void>> {
    return await apiClient.delete<void>(`/api/accounts/${accountId}`);
  }

  // Get account summary/statistics
  static async getAccountSummary(): Promise<ApiResponse<AccountSummary>> {
    return await apiClient.get<AccountSummary>('/api/accounts/summary');
  }

  // Get account balance
  static async getAccountBalance(accountId: number): Promise<ApiResponse<BalanceResponse>> {
    return await apiClient.get<BalanceResponse>(`/api/accounts/${accountId}/balance`);
  }

  // Update account status
  static async updateAccountStatus(
    accountId: number,
    status: Account['status']
  ): Promise<ApiResponse<Account>> {
    return await apiClient.patch<Account>(`/api/accounts/${accountId}/status`, { status });
  }

  // Freeze account
  static async freezeAccount(accountId: number): Promise<ApiResponse<Account>> {
    return await this.updateAccountStatus(accountId, 'FROZEN');
  }

  // Unfreeze account
  static async unfreezeAccount(accountId: number): Promise<ApiResponse<Account>> {
    return await this.updateAccountStatus(accountId, 'ACTIVE');
  }

  // Close account
  static async closeAccount(accountId: number): Promise<ApiResponse<Account>> {
    return await this.updateAccountStatus(accountId, 'CLOSED');
  }

  // Get accounts by type
  static async getAccountsByType(
    accountType: Account['accountType']
  ): Promise<ApiResponse<Account[]>> {
    return await apiClient.get<Account[]>(`/api/accounts?type=${accountType}`);
  }

  // Get accounts by status
  static async getAccountsByStatus(
    status: Account['status']
  ): Promise<ApiResponse<Account[]>> {
    return await apiClient.get<Account[]>(`/api/accounts?status=${status}`);
  }

  // Search accounts
  static async searchAccounts(
    query: string,
    page: number = 0,
    size: number = 10
  ): Promise<ApiResponse<PaginatedResponse<Account>>> {
    const params = new URLSearchParams({
      q: query,
      page: page.toString(),
      size: size.toString(),
    });

    return await apiClient.get<PaginatedResponse<Account>>(`/api/accounts/search?${params}`);
  }
}


import { AccountsApi, Configuration } from '@/gen/openapi/accounts/src';
import { ApiResponse, PaginatedResponse } from './client';

const accountsApi = new AccountsApi(new Configuration({ basePath: '' }));

async function call<T>(request: () => Promise<T>): Promise<ApiResponse<T>> {
  try {
    return { success: true, data: await request() };
  } catch (error) {
    return { success: false, message: error instanceof Error ? error.message : 'Request failed' };
  }
}

export interface Account {
  id: number;
  userId: number;
  accountNumber: string;
  accountType: 'CHECKING' | 'SAVINGS' | 'CREDIT' | 'INVESTMENT';
  balance: number;
  currency: string;
  status: 'ACTIVE' | 'INACTIVE' | 'FROZEN' | 'CLOSED';
  createdAt: string;
  updatedAt: string;
}

export interface CreateAccountRequest {
  accountType: 'CHECKING' | 'SAVINGS' | 'CREDIT' | 'INVESTMENT';
  initialBalance?: number;
  currency?: string;
}

export interface UpdateAccountRequest {
  accountType?: 'CHECKING' | 'SAVINGS' | 'CREDIT' | 'INVESTMENT';
  status?: 'ACTIVE' | 'INACTIVE' | 'FROZEN' | 'CLOSED';
}

export interface AccountSummary {
  totalAccounts: number;
  totalBalance: number;
  activeAccounts: number;
  accountsByType: {
    [key in Account['accountType']]: number;
  };
}

export class AccountsAPI {
  // Get all accounts for the current user
  static async getAccounts(): Promise<ApiResponse<Account[]>> {
    return call(() => accountsApi.listAccounts());
  }

  // Get paginated accounts
  static async getAccountsPaginated(
    page: number = 0,
    size: number = 10,
    sort: string = 'createdAt,desc'
  ): Promise<ApiResponse<PaginatedResponse<Account>>> {
    return call(() => accountsApi.listAccounts({ page, size, sort }));
  }

  // Get account by ID
  static async getAccount(accountId: number): Promise<ApiResponse<Account>> {
    return call(() => accountsApi.getAccount({ accountId }));
  }

  // Create new account
  static async createAccount(accountData: CreateAccountRequest): Promise<ApiResponse<Account>> {
    return call(() => accountsApi.createAccount({ accountCreateRequest: accountData }));
  }

  // Update account
  static async updateAccount(
    accountId: number,
    accountData: UpdateAccountRequest
  ): Promise<ApiResponse<Account>> {
    return call(() => accountsApi.updateAccount({ accountId, accountUpdateRequest: accountData }));
  }

  // Delete account
  static async deleteAccount(accountId: number): Promise<ApiResponse<void>> {
    return call(() => accountsApi.closeAccount({ accountId }));
  }

  // Get account summary/statistics
  static async getAccountSummary(): Promise<ApiResponse<AccountSummary>> {
    return call(() => accountsApi.summarizeAccounts());
  }

  // Get account balance
  static async getAccountBalance(accountId: number): Promise<ApiResponse<{ balance: number; currency: string }>> {
    return call(() => accountsApi.getAccountBalance({ accountId }));
  }

  // Update account status
  static async updateAccountStatus(
    accountId: number,
    status: Account['status']
  ): Promise<ApiResponse<Account>> {
    return call(() => accountsApi.updateAccountStatus({ accountId, statusUpdateRequest: { status } }));
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
    return call(() => accountsApi.listAccounts({ type: accountType }));
  }

  // Get accounts by status
  static async getAccountsByStatus(
    status: Account['status']
  ): Promise<ApiResponse<Account[]>> {
    return call(() => accountsApi.listAccounts({ status }));
  }

  // Search accounts
  static async searchAccounts(
    query: string,
    page: number = 0,
    size: number = 10
  ): Promise<ApiResponse<PaginatedResponse<Account>>> {
    return call(() => accountsApi.searchAccounts({ q: query, page, size }));
  }
}


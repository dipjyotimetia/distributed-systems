import { apiClient, ApiResponse, PaginatedResponse } from './client';
import { logTransaction } from '../logger';

export interface Transaction {
  id: number;
  userId: number;
  fromAccountId?: number;
  toAccountId?: number;
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER';
  amount: number;
  description: string;
  status: 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  createdAt: string;
  processedAt?: string;
}

export interface CreateTransactionRequest {
  accountId: number;
  toAccountId?: number;
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER';
  amount: number;
  currency?: string;
  description: string;
  metadata?: Record<string, unknown>;
}

export interface TransactionSummary {
  totalTransactions: number;
  totalDeposits: number;
  totalWithdrawals: number;
  totalTransfers: number;
  pendingTransactions: number;
  completedTransactions: number;
  failedTransactions: number;
  transactionsByType: {
    [key in Transaction['type']]: number;
  };
  transactionsByStatus: {
    [key in Transaction['status']]: number;
  };
}

export interface TransactionFilters {
  accountId?: number;
  type?: Transaction['type'];
  status?: Transaction['status'];
  startDate?: string;
  endDate?: string;
  minAmount?: number;
  maxAmount?: number;
  description?: string;
}

function idempotencyConfig() {
  return { headers: { 'Idempotency-Key': crypto.randomUUID() } };
}

export class TransactionsAPI {
  // Get all transactions for the current user
  static async getTransactions(): Promise<ApiResponse<Transaction[]>> {
    return await apiClient.get<Transaction[]>('/api/v1/transactions');
  }

  // Get paginated transactions
  static async getTransactionsPaginated(
    page: number = 0,
    size: number = 10,
    sort: string = 'createdAt,desc'
  ): Promise<ApiResponse<PaginatedResponse<Transaction>>> {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
      sort,
    });

    return await apiClient.get<PaginatedResponse<Transaction>>(`/api/v1/transactions?${params}`);
  }

  // Get filtered transactions
  static async getFilteredTransactions(
    filters: TransactionFilters,
    page: number = 0,
    size: number = 10,
    sort: string = 'createdAt,desc'
  ): Promise<ApiResponse<PaginatedResponse<Transaction>>> {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
      sort,
    });

    // Add filters to params
    if (filters.accountId) params.append('accountId', filters.accountId.toString());
    if (filters.type) params.append('type', filters.type);
    if (filters.status) params.append('status', filters.status);
    if (filters.startDate) params.append('startDate', filters.startDate);
    if (filters.endDate) params.append('endDate', filters.endDate);
    if (filters.minAmount) params.append('minAmount', filters.minAmount.toString());
    if (filters.maxAmount) params.append('maxAmount', filters.maxAmount.toString());
    if (filters.description) params.append('description', filters.description);

    return await apiClient.get<PaginatedResponse<Transaction>>(`/api/v1/transactions?${params}`);
  }

  // Get transaction by ID
  static async getTransaction(transactionId: number): Promise<ApiResponse<Transaction>> {
    return await apiClient.get<Transaction>(`/api/v1/transactions/${transactionId}`);
  }

  // Get transactions for a specific account
  static async getAccountTransactions(
    accountId: number,
    page: number = 0,
    size: number = 10,
    sort: string = 'createdAt,desc'
  ): Promise<ApiResponse<Transaction[]>> {
    const params = new URLSearchParams({
      accountId: accountId.toString(),
    });

    return await apiClient.get<Transaction[]>(`/api/v1/transactions?${params}`);
  }

  // Create new transaction
  static async createTransaction(transactionData: CreateTransactionRequest): Promise<ApiResponse<Transaction>> {
    return await apiClient.post<Transaction>('/api/v1/transactions', transactionData, idempotencyConfig());
  }

  // Create deposit transaction
  static async createDeposit(
    accountId: number,
    amount: number,
    description: string,
    currency: string = 'USD'
  ): Promise<ApiResponse<Transaction>> {
    console.log(`[TRANSACTION] Creating deposit: Account ${accountId}, Amount ${amount} ${currency}`);
    const depositData = {
      accountId,
      amount,
      description,
      currency,
    };

    const result = await apiClient.post<Transaction>('/api/v1/transactions/deposit', depositData, idempotencyConfig());
    logTransaction('deposit', accountId, amount, result.success);
    return result;
  }

  // Create withdrawal transaction
  static async createWithdrawal(
    accountId: number,
    amount: number,
    description: string,
    currency: string = 'USD'
  ): Promise<ApiResponse<Transaction>> {
    console.log(`[TRANSACTION] Creating withdrawal: Account ${accountId}, Amount ${amount} ${currency}`);
    const withdrawalData = {
      accountId,
      amount,
      description,
      currency,
    };

    const result = await apiClient.post<Transaction>('/api/v1/transactions/withdraw', withdrawalData, idempotencyConfig());
    logTransaction('withdrawal', accountId, amount, result.success);
    return result;
  }

  // Create transfer transaction
  static async createTransfer(
    fromAccountId: number,
    toAccountId: number,
    amount: number,
    description: string,
    currency: string = 'USD'
  ): Promise<ApiResponse<Transaction>> {
    console.log(`[TRANSACTION] Creating transfer: From Account ${fromAccountId} to Account ${toAccountId}, Amount ${amount} ${currency}`);
    const transferData = {
      fromAccountId,
      toAccountId,
      amount,
      description,
      currency,
    };

    const result = await apiClient.post<Transaction>('/api/v1/transactions/transfer', transferData, idempotencyConfig());
    logTransaction('transfer', fromAccountId, amount, result.success);
    return result;
  }

  // Cancel transaction (if pending)
  static async cancelTransaction(transactionId: number): Promise<ApiResponse<Transaction>> {
    return await apiClient.patch<Transaction>(`/api/v1/transactions/${transactionId}/cancel`);
  }

  // Get transaction summary/statistics
  static async getTransactionSummary(): Promise<ApiResponse<TransactionSummary>> {
    return await apiClient.get<TransactionSummary>('/api/v1/transactions/summary');
  }

  // Get transaction summary for a specific account
  static async getAccountTransactionSummary(accountId: number): Promise<ApiResponse<TransactionSummary>> {
    return await apiClient.get<TransactionSummary>(`/api/v1/accounts/${accountId}/transactions/summary`);
  }

  // Get transactions by type
  static async getTransactionsByType(
    type: Transaction['type'],
    page: number = 0,
    size: number = 10
  ): Promise<ApiResponse<PaginatedResponse<Transaction>>> {
    const params = new URLSearchParams({
      type,
      page: page.toString(),
      size: size.toString(),
      sort: 'createdAt,desc',
    });

    return await apiClient.get<PaginatedResponse<Transaction>>(`/api/v1/transactions?${params}`);
  }

  // Get transactions by status
  static async getTransactionsByStatus(
    status: Transaction['status'],
    page: number = 0,
    size: number = 10
  ): Promise<ApiResponse<PaginatedResponse<Transaction>>> {
    const params = new URLSearchParams({
      status,
      page: page.toString(),
      size: size.toString(),
      sort: 'createdAt,desc',
    });

    return await apiClient.get<PaginatedResponse<Transaction>>(`/api/v1/transactions?${params}`);
  }

  // Get pending transactions
  static async getPendingTransactions(): Promise<ApiResponse<Transaction[]>> {
    return await apiClient.get<Transaction[]>('/api/v1/transactions?status=PENDING');
  }

  // Get recent transactions
  static async getRecentTransactions(limit: number = 10): Promise<ApiResponse<Transaction[]>> {
    return await apiClient.get<Transaction[]>(`/api/v1/transactions/recent?limit=${limit}`);
  }

  // Search transactions
  static async searchTransactions(
    query: string,
    page: number = 0,
    size: number = 10
  ): Promise<ApiResponse<PaginatedResponse<Transaction>>> {
    const params = new URLSearchParams({
      q: query,
      page: page.toString(),
      size: size.toString(),
    });

    return await apiClient.get<PaginatedResponse<Transaction>>(`/api/v1/transactions/search?${params}`);
  }

  // Get transactions by date range
  static async getTransactionsByDateRange(
    startDate: string,
    endDate: string,
    page: number = 0,
    size: number = 10
  ): Promise<ApiResponse<PaginatedResponse<Transaction>>> {
    const params = new URLSearchParams({
      startDate,
      endDate,
      page: page.toString(),
      size: size.toString(),
      sort: 'createdAt,desc',
    });

    return await apiClient.get<PaginatedResponse<Transaction>>(`/api/v1/transactions?${params}`);
  }

  // Get monthly transaction report
  static async getMonthlyTransactionReport(
    year: number,
    month: number
  ): Promise<ApiResponse<TransactionSummary>> {
    return await apiClient.get<TransactionSummary>(`/api/v1/transactions/reports/monthly?year=${year}&month=${month}`);
  }

  // Export transactions
  static async exportTransactions(
    format: 'CSV' | 'PDF' | 'EXCEL',
    filters?: TransactionFilters
  ): Promise<ApiResponse<{ downloadUrl: string }>> {
    const params = new URLSearchParams({
      format,
    });

    if (filters) {
      if (filters.accountId) params.append('accountId', filters.accountId.toString());
      if (filters.type) params.append('type', filters.type);
      if (filters.status) params.append('status', filters.status);
      if (filters.startDate) params.append('startDate', filters.startDate);
      if (filters.endDate) params.append('endDate', filters.endDate);
    }

    return await apiClient.get<{ downloadUrl: string }>(`/api/v1/transactions/export?${params}`);
  }
}


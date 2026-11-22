const axios = require('axios');
const { randomUUID } = require('crypto');
const logger = require('../utils/logger');
const config = require('../config');
const { injectTraceHeaders } = require('../utils/tracing');
const { UsersApi, Configuration: UsersConfiguration } = require('../generated/users/src');
const { AccountsApi, StatementJobsApi, Configuration: AccountsConfiguration } = require('../generated/accounts/src');
const { TransactionsApi, Configuration: TransactionsConfiguration } = require('../generated/transactions/src');
const { DisputesApi, Configuration: DisputesConfiguration } = require('../generated/disputes/src');
const { PaymentsApi, Configuration: PaymentsConfiguration } = require('../generated/payments/src');
const { AiApi, Configuration: AiConfiguration } = require('../generated/ai/src');
const { NotificationsApi, Configuration: NotificationsConfiguration } = require('../generated/notifications/src');
const { GatewayApi, Configuration: GatewayConfiguration } = require('../generated/gateway/src');

class ApiClient {
  constructor() {
    this.baseURL = config.target.baseUrl;
    this.timeout = config.target.timeout;
    this.maxRetries = config.simulation.maxRetries;
    this.retryDelay = config.simulation.retryDelayMs;
    
    this.client = axios.create({
      baseURL: this.baseURL,
      timeout: this.timeout,
      headers: {
        'Content-Type': 'application/json',
        'User-Agent': 'Banking-Simulation-Client/1.0.0'
      }
    });

    this.setupInterceptors();
  }

  contract(Api, Configuration, base, token, extraHeaders = {}) {
    return new Api(new Configuration({
      basePath: base,
      headers: injectTraceHeaders({
        'Content-Type': 'application/json',
        'User-Agent': 'Banking-Simulation-Client/1.0.0',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...extraHeaders,
      }),
    }));
  }

  setupInterceptors() {
    // Request interceptor for logging
    this.client.interceptors.request.use(
      (config) => {
        Object.assign(config.headers, injectTraceHeaders({}));
        logger.debug('API Request', {
          method: config.method?.toUpperCase(),
          url: config.url,
          headers: config.headers
        });
        return config;
      },
      (error) => {
        logger.error('API Request Error', { error: error.message });
        return Promise.reject(error);
      }
    );

    // Response interceptor for logging and error handling
    this.client.interceptors.response.use(
      (response) => {
        logger.debug('API Response', {
          status: response.status,
          url: response.config.url,
          method: response.config.method?.toUpperCase()
        });
        return response;
      },
      (error) => {
        const errorData = {
          message: error.message,
          status: error.response?.status,
          url: error.config?.url,
          method: error.config?.method?.toUpperCase()
        };
        
        logger.warn('API Response Error', errorData);
        return Promise.reject(error);
      }
    );
  }

  async retryRequest(requestFn, retries = 0) {
    try {
      return await requestFn();
    } catch (error) {
      if (retries < this.maxRetries && this.isRetryableError(error)) {
        logger.info(`Retrying request (${retries + 1}/${this.maxRetries})`, {
          error: error.message,
          status: error.response?.status
        });
        
        await this.delay(this.retryDelay * (retries + 1));
        return this.retryRequest(requestFn, retries + 1);
      }
      throw error;
    }
  }

  isRetryableError(error) {
    if (!error.response) return true; // Network errors
    
    const status = error.response.status;
    return status >= 500 || status === 429; // Server errors or rate limiting
  }

  delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  // Authentication endpoints
  async register(userData) {
    return this.retryRequest(async () => this.contract(UsersApi, UsersConfiguration, this.baseURL).register({ registerRequest: userData }));
  }

  async login(credentials) {
    return this.retryRequest(async () => this.contract(UsersApi, UsersConfiguration, this.baseURL).login({ loginRequest: credentials }));
  }

  async getUserProfile(token) {
    return this.retryRequest(async () => this.contract(UsersApi, UsersConfiguration, this.baseURL, token).getProfile());
  }

  // Account endpoints
  async getAccounts(token) {
    return this.retryRequest(async () => this.contract(AccountsApi, AccountsConfiguration, this.baseURL, token).listAccounts());
  }

  async createAccount(accountData, token) {
    return this.retryRequest(async () => this.contract(AccountsApi, AccountsConfiguration, this.baseURL, token).createAccount({ accountCreateRequest: accountData }));
  }

  async getAccountById(accountId, token) {
    return this.retryRequest(async () => this.contract(AccountsApi, AccountsConfiguration, this.baseURL, token).getAccount({ accountId }));
  }

  async getAccountBalance(accountId, token) {
    return this.retryRequest(async () => this.contract(AccountsApi, AccountsConfiguration, this.baseURL, token).getAccountBalance({ accountId }));
  }

  async getAccountTransactions(accountId, token) {
    return this.retryRequest(async () => this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).listTransactions({ accountId }));
  }

  // Transaction endpoints
  async createTransaction(transactionData, token) {
    const payload = { currency: 'USD', ...transactionData };
    return this.retryRequest(async () => this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).createTransaction({ createTransactionRequest: payload }));
  }

  async getRecentTransactions(token) {
    return this.retryRequest(async () => this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).listTransactions());
  }

  async getTransaction(transactionId, token) {
    return this.retryRequest(async () => {
      const txns = await this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).listTransactions();
      const items = Array.isArray(txns) ? txns : txns?.content || [];
      return items.find(t => t.id === transactionId) || null;
    });
  }

  async deposit(transactionData, token) {
    const payload = { currency: 'USD', ...transactionData };
    return this.retryRequest(async () => this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).deposit({ depositRequest: payload }));
  }

  async withdraw(transactionData, token) {
    const payload = { currency: 'USD', ...transactionData };
    return this.retryRequest(async () => this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).withdraw({ withdrawRequest: payload }));
  }

  async transfer(transactionData, token) {
    const payload = { currency: 'USD', ...transactionData };
    return this.retryRequest(async () => this.contract(TransactionsApi, TransactionsConfiguration, this.baseURL, token).transfer({ transferRequest: payload }));
  }

  async checkUsername(username) {
    return this.retryRequest(async () => this.contract(UsersApi, UsersConfiguration, this.baseURL).checkUsername({ username }));
  }

  async checkEmail(email) {
    return this.retryRequest(async () => this.contract(UsersApi, UsersConfiguration, this.baseURL).checkEmail({ email }));
  }

  async fileDispute(transferId, token) {
    return this.retryRequest(async () => this.contract(DisputesApi, DisputesConfiguration, this.baseURL, token).fileDispute({
      fileDisputeRequest: { transferId, reason: 'Simulation dispute for a completed transfer' },
    }));
  }

  async queueStatementJob(accountId, token) {
    return this.retryRequest(async () => this.contract(StatementJobsApi, AccountsConfiguration, this.baseURL, token).createStatementJob({ accountId, idempotencyKey: randomUUID() }));
  }

  async submitPayment(accountId, token) {
    return this.retryRequest(async () => this.contract(PaymentsApi, PaymentsConfiguration, this.baseURL, token).initiatePayment({
      idempotencyKey: randomUUID(),
      paymentInstruction: { accountId, destination: 'SIMULATE0001', amountMinor: 100 },
    }));
  }

  async exportStatement(accountId, token) {
    return this.retryRequest(async () => this.contract(AccountsApi, AccountsConfiguration, this.baseURL, token).exportStatement({ accountId }));
  }

  async askAIChat(message, token, locale = 'en-US', accountContext = '') {
    return this.contract(AiApi, AiConfiguration, this.baseURL, token).chat({ chatRequest: { message, locale, accountContext } });
  }

  // Notification service endpoints
  async getNotifications(userId) {
    return this.retryRequest(async () => this.contract(NotificationsApi, NotificationsConfiguration, config.target.notificationServiceUrl).listUserNotifications({ userId: String(userId) }));
  }

  // Raw request without retry — used for error injection so 5xx responses
  // are not amplified by the retry loop.
  async rawRequest(method, path, data, token) {
    const headers = injectTraceHeaders({ 'Content-Type': 'application/json' });
    if (token) headers.Authorization = `Bearer ${token}`;
    return this.client.request({ method, url: path, data, headers });
  }

  // Health check
  async healthCheck() {
    return this.retryRequest(async () => this.contract(GatewayApi, GatewayConfiguration, this.baseURL).healthz());
  }
}

module.exports = ApiClient;

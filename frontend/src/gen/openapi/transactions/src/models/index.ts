/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface CreateTransactionRequest
 */
export interface CreateTransactionRequest {
    /**
     * 
     * @type {number}
     * @memberof CreateTransactionRequest
     */
    accountId: number;
    /**
     * 
     * @type {number}
     * @memberof CreateTransactionRequest
     */
    toAccountId?: number;
    /**
     * 
     * @type {string}
     * @memberof CreateTransactionRequest
     */
    type: CreateTransactionRequestTypeEnum;
    /**
     * 
     * @type {number}
     * @memberof CreateTransactionRequest
     */
    amount: number;
    /**
     * 
     * @type {string}
     * @memberof CreateTransactionRequest
     */
    currency: string;
    /**
     * 
     * @type {string}
     * @memberof CreateTransactionRequest
     */
    description?: string;
}


/**
 * @export
 */
export const CreateTransactionRequestTypeEnum = {
    Deposit: 'DEPOSIT',
    Withdrawal: 'WITHDRAWAL',
    Transfer: 'TRANSFER'
} as const;
export type CreateTransactionRequestTypeEnum = typeof CreateTransactionRequestTypeEnum[keyof typeof CreateTransactionRequestTypeEnum];

/**
 * 
 * @export
 * @interface DepositRequest
 */
export interface DepositRequest {
    /**
     * 
     * @type {number}
     * @memberof DepositRequest
     */
    accountId: number;
    /**
     * 
     * @type {number}
     * @memberof DepositRequest
     */
    amount: number;
}
/**
 * 
 * @export
 * @interface TransactionPage
 */
export interface TransactionPage {
    /**
     * 
     * @type {Array<TransactionResponse>}
     * @memberof TransactionPage
     */
    content: Array<TransactionResponse>;
    /**
     * 
     * @type {object}
     * @memberof TransactionPage
     */
    pageable: object;
    /**
     * 
     * @type {object}
     * @memberof TransactionPage
     */
    sort: object;
    /**
     * 
     * @type {boolean}
     * @memberof TransactionPage
     */
    last: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof TransactionPage
     */
    first: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof TransactionPage
     */
    empty: boolean;
    /**
     * 
     * @type {number}
     * @memberof TransactionPage
     */
    totalPages: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionPage
     */
    totalElements: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionPage
     */
    size: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionPage
     */
    number: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionPage
     */
    numberOfElements: number;
}
/**
 * 
 * @export
 * @interface TransactionResponse
 */
export interface TransactionResponse {
    /**
     * 
     * @type {number}
     * @memberof TransactionResponse
     */
    id?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionResponse
     */
    userId?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionResponse
     */
    fromAccountId?: number | null;
    /**
     * 
     * @type {number}
     * @memberof TransactionResponse
     */
    toAccountId?: number | null;
    /**
     * 
     * @type {number}
     * @memberof TransactionResponse
     */
    amount?: number;
    /**
     * 
     * @type {string}
     * @memberof TransactionResponse
     */
    type?: string;
    /**
     * 
     * @type {string}
     * @memberof TransactionResponse
     */
    processedAt?: string;
    /**
     * 
     * @type {string}
     * @memberof TransactionResponse
     */
    timestamp?: string;
}
/**
 * 
 * @export
 * @interface TransactionSummary
 */
export interface TransactionSummary {
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    totalTransactions?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    totalDeposits?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    totalWithdrawals?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    totalTransfers?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    pendingTransactions?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    completedTransactions?: number;
    /**
     * 
     * @type {number}
     * @memberof TransactionSummary
     */
    failedTransactions?: number;
    /**
     * 
     * @type {{ [key: string]: number; }}
     * @memberof TransactionSummary
     */
    transactionsByType?: { [key: string]: number; };
    /**
     * 
     * @type {{ [key: string]: number; }}
     * @memberof TransactionSummary
     */
    transactionsByStatus?: { [key: string]: number; };
}
/**
 * 
 * @export
 * @interface TransferRequest
 */
export interface TransferRequest {
    /**
     * 
     * @type {number}
     * @memberof TransferRequest
     */
    fromAccountId: number;
    /**
     * 
     * @type {number}
     * @memberof TransferRequest
     */
    toAccountId: number;
    /**
     * 
     * @type {number}
     * @memberof TransferRequest
     */
    amount: number;
}
/**
 * 
 * @export
 * @interface Withdraw400Response
 */
export interface Withdraw400Response {
    /**
     * 
     * @type {string}
     * @memberof Withdraw400Response
     */
    code: Withdraw400ResponseCodeEnum;
    /**
     * 
     * @type {string}
     * @memberof Withdraw400Response
     */
    message: string;
}


/**
 * @export
 */
export const Withdraw400ResponseCodeEnum = {
    InsufficientFunds: 'INSUFFICIENT_FUNDS'
} as const;
export type Withdraw400ResponseCodeEnum = typeof Withdraw400ResponseCodeEnum[keyof typeof Withdraw400ResponseCodeEnum];

/**
 * 
 * @export
 * @interface WithdrawRequest
 */
export interface WithdrawRequest {
    /**
     * 
     * @type {number}
     * @memberof WithdrawRequest
     */
    accountId: number;
    /**
     * 
     * @type {number}
     * @memberof WithdrawRequest
     */
    amount: number;
}

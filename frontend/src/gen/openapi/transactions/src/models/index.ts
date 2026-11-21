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
    /**
     *
     * @type {string}
     * @memberof DepositRequest
     */
    description?: string;
}
/**
 * Present when the ledger rejects a confirmed insufficient balance.
 * @export
 * @interface InsufficientFunds
 */
export interface InsufficientFunds {
    /**
     *
     * @type {string}
     * @memberof InsufficientFunds
     */
    code: InsufficientFundsCodeEnum;
    /**
     *
     * @type {string}
     * @memberof InsufficientFunds
     */
    message: string;
}


/**
 * @export
 */
export const InsufficientFundsCodeEnum = {
    InsufficientFunds: 'INSUFFICIENT_FUNDS'
} as const;
export type InsufficientFundsCodeEnum = typeof InsufficientFundsCodeEnum[keyof typeof InsufficientFundsCodeEnum];

/**
 *
 * @export
 * @interface MovementError
 */
export interface MovementError {
    /**
     * Stable machine-readable error code.
     * @type {string}
     * @memberof MovementError
     */
    code: string;
    /**
     *
     * @type {string}
     * @memberof MovementError
     */
    message?: string;
}
/**
 * Supply only toAccountId for DEPOSIT, only fromAccountId for WITHDRAWAL, and two distinct account IDs for TRANSFER. The caller must own the source account, or the destination for a deposit.
 *
 * @export
 * @interface MovementIntent
 */
export interface MovementIntent {
    /**
     *
     * @type {string}
     * @memberof MovementIntent
     */
    type: MovementIntentTypeEnum;
    /**
     *
     * @type {number}
     * @memberof MovementIntent
     */
    fromAccountId?: number;
    /**
     *
     * @type {number}
     * @memberof MovementIntent
     */
    toAccountId?: number;
    /**
     *
     * @type {MovementMoney}
     * @memberof MovementIntent
     */
    money: MovementMoney;
    /**
     *
     * @type {string}
     * @memberof MovementIntent
     */
    description?: string;
}


/**
 * @export
 */
export const MovementIntentTypeEnum = {
    Deposit: 'DEPOSIT',
    Withdrawal: 'WITHDRAWAL',
    Transfer: 'TRANSFER'
} as const;
export type MovementIntentTypeEnum = typeof MovementIntentTypeEnum[keyof typeof MovementIntentTypeEnum];

/**
 *
 * @export
 * @interface MovementMoney
 */
export interface MovementMoney {
    /**
     * Supported account currency as an ISO 4217 code.
     * @type {string}
     * @memberof MovementMoney
     */
    currency: string;
    /**
     * Positive exact amount within the supported currency scale. Excess precision is rejected.
     * @type {string}
     * @memberof MovementMoney
     */
    amount: string;
}
/**
 *
 * @export
 * @interface MovementOutcome
 */
export interface MovementOutcome {
    /**
     *
     * @type {string}
     * @memberof MovementOutcome
     */
    id: string;
    /**
     * POSTED requires durable account posting. REJECTED requires confirmation that no posting occurred and any hold was released.
     *
     * @type {string}
     * @memberof MovementOutcome
     */
    status: MovementOutcomeStatusEnum;
    /**
     * Machine-readable reason for a pending or rejected outcome.
     * @type {string}
     * @memberof MovementOutcome
     */
    reasonCode?: string;
    /**
     *
     * @type {string}
     * @memberof MovementOutcome
     */
    createdAt: string;
    /**
     *
     * @type {string}
     * @memberof MovementOutcome
     */
    updatedAt: string;
}


/**
 * @export
 */
export const MovementOutcomeStatusEnum = {
    Pending: 'PENDING',
    Posted: 'POSTED',
    Rejected: 'REJECTED'
} as const;
export type MovementOutcomeStatusEnum = typeof MovementOutcomeStatusEnum[keyof typeof MovementOutcomeStatusEnum];

/**
 * @type Rejection
 * Confirmed insufficient funds, or a validation field map. Fraud and other rejections use HTTP 400 with an empty body and are not this schema.
 * @export
 */
export type Rejection = InsufficientFunds | { [key: string]: string; };
/**
 * @type TransactionListOrPage
 * Transaction array, or a transaction page when page or size is supplied
 * @export
 */
export type TransactionListOrPage = Array<TransactionResponse> | TransactionPage;
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
    status?: string;
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
    /**
     *
     * @type {string}
     * @memberof TransferRequest
     */
    description?: string;
}
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
    /**
     *
     * @type {string}
     * @memberof WithdrawRequest
     */
    description?: string;
}

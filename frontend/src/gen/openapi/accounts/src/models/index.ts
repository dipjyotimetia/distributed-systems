/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface AccountCreateRequest
 */
export interface AccountCreateRequest {
    /**
     * 
     * @type {string}
     * @memberof AccountCreateRequest
     */
    accountType: AccountCreateRequestAccountTypeEnum;
    /**
     * 
     * @type {number}
     * @memberof AccountCreateRequest
     */
    initialBalance?: number;
    /**
     * 
     * @type {string}
     * @memberof AccountCreateRequest
     */
    currency?: string;
}


/**
 * @export
 */
export const AccountCreateRequestAccountTypeEnum = {
    Checking: 'CHECKING',
    Savings: 'SAVINGS',
    Credit: 'CREDIT',
    Investment: 'INVESTMENT'
} as const;
export type AccountCreateRequestAccountTypeEnum = typeof AccountCreateRequestAccountTypeEnum[keyof typeof AccountCreateRequestAccountTypeEnum];

/**
 * 
 * @export
 * @interface AccountPage
 */
export interface AccountPage {
    /**
     * 
     * @type {Array<AccountResponse>}
     * @memberof AccountPage
     */
    content: Array<AccountResponse>;
    /**
     * 
     * @type {object}
     * @memberof AccountPage
     */
    pageable: object;
    /**
     * 
     * @type {object}
     * @memberof AccountPage
     */
    sort: object;
    /**
     * 
     * @type {boolean}
     * @memberof AccountPage
     */
    last: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof AccountPage
     */
    first: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof AccountPage
     */
    empty: boolean;
    /**
     * 
     * @type {number}
     * @memberof AccountPage
     */
    totalPages: number;
    /**
     * 
     * @type {number}
     * @memberof AccountPage
     */
    totalElements: number;
    /**
     * 
     * @type {number}
     * @memberof AccountPage
     */
    size: number;
    /**
     * 
     * @type {number}
     * @memberof AccountPage
     */
    number: number;
    /**
     * 
     * @type {number}
     * @memberof AccountPage
     */
    numberOfElements: number;
}
/**
 * 
 * @export
 * @interface AccountResponse
 */
export interface AccountResponse {
    /**
     * 
     * @type {number}
     * @memberof AccountResponse
     */
    id?: number;
    /**
     * 
     * @type {number}
     * @memberof AccountResponse
     */
    userId?: number;
    /**
     * 
     * @type {string}
     * @memberof AccountResponse
     */
    accountNumber?: string;
    /**
     * 
     * @type {number}
     * @memberof AccountResponse
     */
    balance?: number;
    /**
     * 
     * @type {string}
     * @memberof AccountResponse
     */
    accountType?: string;
    /**
     * 
     * @type {string}
     * @memberof AccountResponse
     */
    currency?: string;
    /**
     * 
     * @type {AccountStatus}
     * @memberof AccountResponse
     */
    status?: AccountStatus;
    /**
     * 
     * @type {string}
     * @memberof AccountResponse
     */
    createdAt?: string;
    /**
     * 
     * @type {string}
     * @memberof AccountResponse
     */
    updatedAt?: string;
}



/**
 * 
 * @export
 */
export const AccountStatus = {
    Active: 'ACTIVE',
    Inactive: 'INACTIVE',
    Frozen: 'FROZEN',
    Closed: 'CLOSED'
} as const;
export type AccountStatus = typeof AccountStatus[keyof typeof AccountStatus];

/**
 * 
 * @export
 * @interface AccountSummary
 */
export interface AccountSummary {
    /**
     * 
     * @type {number}
     * @memberof AccountSummary
     */
    totalAccounts?: number;
    /**
     * 
     * @type {number}
     * @memberof AccountSummary
     */
    activeAccounts?: number;
    /**
     * 
     * @type {number}
     * @memberof AccountSummary
     */
    totalBalance?: number;
    /**
     * 
     * @type {{ [key: string]: number; }}
     * @memberof AccountSummary
     */
    accountsByType?: { [key: string]: number; };
}
/**
 * 
 * @export
 * @interface AccountUpdateRequest
 */
export interface AccountUpdateRequest {
    /**
     * 
     * @type {string}
     * @memberof AccountUpdateRequest
     */
    accountType?: AccountUpdateRequestAccountTypeEnum;
    /**
     * 
     * @type {string}
     * @memberof AccountUpdateRequest
     */
    status?: AccountUpdateRequestStatusEnum;
}


/**
 * @export
 */
export const AccountUpdateRequestAccountTypeEnum = {
    Checking: 'CHECKING',
    Savings: 'SAVINGS',
    Credit: 'CREDIT',
    Investment: 'INVESTMENT'
} as const;
export type AccountUpdateRequestAccountTypeEnum = typeof AccountUpdateRequestAccountTypeEnum[keyof typeof AccountUpdateRequestAccountTypeEnum];

/**
 * @export
 */
export const AccountUpdateRequestStatusEnum = {
    Active: 'ACTIVE',
    Frozen: 'FROZEN',
    Closed: 'CLOSED'
} as const;
export type AccountUpdateRequestStatusEnum = typeof AccountUpdateRequestStatusEnum[keyof typeof AccountUpdateRequestStatusEnum];

/**
 * 
 * @export
 * @interface BalanceResponse
 */
export interface BalanceResponse {
    /**
     * 
     * @type {number}
     * @memberof BalanceResponse
     */
    accountId?: number;
    /**
     * 
     * @type {string}
     * @memberof BalanceResponse
     */
    currency?: string;
    /**
     * 
     * @type {number}
     * @memberof BalanceResponse
     */
    balance?: number;
    /**
     * 
     * @type {string}
     * @memberof BalanceResponse
     */
    accountNumber?: string;
}
/**
 * 
 * @export
 * @interface BalanceUpdateRequest
 */
export interface BalanceUpdateRequest {
    /**
     * 
     * @type {number}
     * @memberof BalanceUpdateRequest
     */
    balance: number;
}
/**
 * 
 * @export
 * @interface StatementExport
 */
export interface StatementExport {
    /**
     * 
     * @type {string}
     * @memberof StatementExport
     */
    status?: string;
    /**
     * 
     * @type {string}
     * @memberof StatementExport
     */
    key?: string;
    /**
     * 
     * @type {{ [key: string]: any; }}
     * @memberof StatementExport
     */
    statement?: { [key: string]: any; };
}
/**
 * 
 * @export
 * @interface StatementJob
 */
export interface StatementJob {
    /**
     * 
     * @type {string}
     * @memberof StatementJob
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof StatementJob
     */
    status: string;
    /**
     * 
     * @type {string}
     * @memberof StatementJob
     */
    createdAt: string;
    /**
     * 
     * @type {string}
     * @memberof StatementJob
     */
    expiresAt: string;
}
/**
 * 
 * @export
 * @interface StatementJobPage
 */
export interface StatementJobPage {
    /**
     * 
     * @type {Array<StatementJob>}
     * @memberof StatementJobPage
     */
    items: Array<StatementJob>;
    /**
     * 
     * @type {boolean}
     * @memberof StatementJobPage
     */
    hasMore: boolean;
}
/**
 * 
 * @export
 * @interface StatusUpdateRequest
 */
export interface StatusUpdateRequest {
    /**
     * 
     * @type {string}
     * @memberof StatusUpdateRequest
     */
    status: StatusUpdateRequestStatusEnum;
}


/**
 * @export
 */
export const StatusUpdateRequestStatusEnum = {
    Active: 'ACTIVE',
    Frozen: 'FROZEN',
    Closed: 'CLOSED'
} as const;
export type StatusUpdateRequestStatusEnum = typeof StatusUpdateRequestStatusEnum[keyof typeof StatusUpdateRequestStatusEnum];


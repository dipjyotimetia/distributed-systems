/* tslint:disable */
/* eslint-disable */
/**
 *
 * @export
 * @interface Discrepancy
 */
export interface Discrepancy {
    /**
     *
     * @type {string}
     * @memberof Discrepancy
     */
    businessDate?: string;
    /**
     *
     * @type {number}
     * @memberof Discrepancy
     */
    recordNumber?: number;
    /**
     *
     * @type {string}
     * @memberof Discrepancy
     */
    paymentId?: string;
    /**
     *
     * @type {string}
     * @memberof Discrepancy
     */
    outcome?: string;
    /**
     *
     * @type {number}
     * @memberof Discrepancy
     */
    expectedAmountMinor?: number;
    /**
     *
     * @type {number}
     * @memberof Discrepancy
     */
    observedAmountMinor?: number;
    /**
     *
     * @type {string}
     * @memberof Discrepancy
     */
    observedReference?: string;
}
/**
 *
 * @export
 * @interface Payment
 */
export interface Payment {
    /**
     *
     * @type {string}
     * @memberof Payment
     */
    id?: string;
    /**
     *
     * @type {PaymentInstruction}
     * @memberof Payment
     */
    instruction?: PaymentInstruction;
    /**
     * REVIEW_REQUIRED can retain the hold. SETTLED requires durable account posting.
     * @type {string}
     * @memberof Payment
     */
    status?: string;
    /**
     *
     * @type {string}
     * @memberof Payment
     */
    clearingReference?: string;
    /**
     *
     * @type {string}
     * @memberof Payment
     */
    createdAt?: string;
    /**
     *
     * @type {string}
     * @memberof Payment
     */
    settlementBusinessDate?: string;
}
/**
 *
 * @export
 * @interface PaymentInstruction
 */
export interface PaymentInstruction {
    /**
     *
     * @type {number}
     * @memberof PaymentInstruction
     */
    accountId: number;
    /**
     *
     * @type {string}
     * @memberof PaymentInstruction
     */
    destination: string;
    /**
     *
     * @type {number}
     * @memberof PaymentInstruction
     */
    amountMinor: number;
}
/**
 *
 * @export
 * @interface PaymentResolution
 */
export interface PaymentResolution {
    /**
     *
     * @type {string}
     * @memberof PaymentResolution
     */
    id: string;
    /**
     *
     * @type {string}
     * @memberof PaymentResolution
     */
    status: PaymentResolutionStatusEnum;
}


/**
 * @export
 */
export const PaymentResolutionStatusEnum = {
    Pending: 'PENDING',
    Applied: 'APPLIED'
} as const;
export type PaymentResolutionStatusEnum = typeof PaymentResolutionStatusEnum[keyof typeof PaymentResolutionStatusEnum];

/**
 *
 * @export
 * @interface PaymentResolutionRequest
 */
export interface PaymentResolutionRequest {
    /**
     *
     * @type {number}
     * @memberof PaymentResolutionRequest
     */
    expectedRevision: number;
    /**
     *
     * @type {string}
     * @memberof PaymentResolutionRequest
     */
    evidenceId: string;
    /**
     *
     * @type {string}
     * @memberof PaymentResolutionRequest
     */
    action: PaymentResolutionRequestActionEnum;
    /**
     *
     * @type {string}
     * @memberof PaymentResolutionRequest
     */
    reason: string;
}


/**
 * @export
 */
export const PaymentResolutionRequestActionEnum = {
    ConfirmSettlement: 'CONFIRM_SETTLEMENT',
    ConfirmRejection: 'CONFIRM_REJECTION'
} as const;
export type PaymentResolutionRequestActionEnum = typeof PaymentResolutionRequestActionEnum[keyof typeof PaymentResolutionRequestActionEnum];

/**
 *
 * @export
 * @interface PaymentReview
 */
export interface PaymentReview {
    /**
     *
     * @type {string}
     * @memberof PaymentReview
     */
    paymentId: string;
    /**
     *
     * @type {number}
     * @memberof PaymentReview
     */
    revision: number;
    /**
     *
     * @type {string}
     * @memberof PaymentReview
     */
    status: string;
    /**
     *
     * @type {number}
     * @memberof PaymentReview
     */
    ageSeconds: number;
    /**
     *
     * @type {string}
     * @memberof PaymentReview
     */
    reason: string;
    /**
     *
     * @type {string}
     * @memberof PaymentReview
     */
    currency: PaymentReviewCurrencyEnum;
    /**
     *
     * @type {number}
     * @memberof PaymentReview
     */
    heldAmountMinor: number;
    /**
     *
     * @type {string}
     * @memberof PaymentReview
     */
    clearingReference?: string;
    /**
     *
     * @type {PaymentResolution}
     * @memberof PaymentReview
     */
    resolution?: PaymentResolution;
    /**
     *
     * @type {Array<PaymentReviewEvidence>}
     * @memberof PaymentReview
     */
    evidence: Array<PaymentReviewEvidence>;
}


/**
 * @export
 */
export const PaymentReviewCurrencyEnum = {
    Aud: 'AUD'
} as const;
export type PaymentReviewCurrencyEnum = typeof PaymentReviewCurrencyEnum[keyof typeof PaymentReviewCurrencyEnum];

/**
 *
 * @export
 * @interface PaymentReviewError
 */
export interface PaymentReviewError {
    /**
     *
     * @type {string}
     * @memberof PaymentReviewError
     */
    code: string;
}
/**
 *
 * @export
 * @interface PaymentReviewEvidence
 */
export interface PaymentReviewEvidence {
    /**
     *
     * @type {string}
     * @memberof PaymentReviewEvidence
     */
    id: string;
    /**
     *
     * @type {string}
     * @memberof PaymentReviewEvidence
     */
    kind: PaymentReviewEvidenceKindEnum;
    /**
     *
     * @type {number}
     * @memberof PaymentReviewEvidence
     */
    amountMinor: number;
    /**
     *
     * @type {string}
     * @memberof PaymentReviewEvidence
     */
    currency: PaymentReviewEvidenceCurrencyEnum;
    /**
     *
     * @type {string}
     * @memberof PaymentReviewEvidence
     */
    clearingReference: string;
    /**
     *
     * @type {string}
     * @memberof PaymentReviewEvidence
     */
    receivedAt: string;
    /**
     *
     * @type {string}
     * @memberof PaymentReviewEvidence
     */
    businessDate?: string;
}


/**
 * @export
 */
export const PaymentReviewEvidenceKindEnum = {
    Accepted: 'ACCEPTED',
    Settled: 'SETTLED',
    Rejected: 'REJECTED'
} as const;
export type PaymentReviewEvidenceKindEnum = typeof PaymentReviewEvidenceKindEnum[keyof typeof PaymentReviewEvidenceKindEnum];

/**
 * @export
 */
export const PaymentReviewEvidenceCurrencyEnum = {
    Aud: 'AUD'
} as const;
export type PaymentReviewEvidenceCurrencyEnum = typeof PaymentReviewEvidenceCurrencyEnum[keyof typeof PaymentReviewEvidenceCurrencyEnum];

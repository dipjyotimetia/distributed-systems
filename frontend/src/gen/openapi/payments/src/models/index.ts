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
     * 
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

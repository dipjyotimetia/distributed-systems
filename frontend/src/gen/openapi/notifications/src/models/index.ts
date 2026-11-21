/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface ErrorResponse
 */
export interface ErrorResponse {
    /**
     * 
     * @type {string}
     * @memberof ErrorResponse
     */
    error?: string;
}
/**
 * 
 * @export
 * @interface Notification
 */
export interface Notification {
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    transaction_id?: string;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    payment_id?: string;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    event_id?: string;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    currency?: string;
    /**
     * 
     * @type {number}
     * @memberof Notification
     */
    amount_minor?: number;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    account_id?: string | null;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    user_id?: string;
    /**
     * 
     * @type {number}
     * @memberof Notification
     */
    amount?: number;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    transaction_type?: string;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    status?: string;
    /**
     * 
     * @type {string}
     * @memberof Notification
     */
    timestamp?: string;
}
/**
 * 
 * @export
 * @interface NotificationHealth200Response
 */
export interface NotificationHealth200Response {
    /**
     * 
     * @type {string}
     * @memberof NotificationHealth200Response
     */
    status: string;
}

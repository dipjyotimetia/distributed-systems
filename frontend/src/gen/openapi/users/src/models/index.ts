/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface Availability
 */
export interface Availability {
    /**
     * 
     * @type {boolean}
     * @memberof Availability
     */
    success?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof Availability
     */
    available?: boolean;
    /**
     * 
     * @type {string}
     * @memberof Availability
     */
    message?: string;
}
/**
 * 
 * @export
 * @interface ErrorResponse
 */
export interface ErrorResponse {
    /**
     * 
     * @type {boolean}
     * @memberof ErrorResponse
     */
    success?: boolean;
    /**
     * 
     * @type {string}
     * @memberof ErrorResponse
     */
    message?: string;
}
/**
 * 
 * @export
 * @interface LoginRequest
 */
export interface LoginRequest {
    /**
     * 
     * @type {string}
     * @memberof LoginRequest
     */
    usernameOrEmail: string;
    /**
     * 
     * @type {string}
     * @memberof LoginRequest
     */
    password: string;
}
/**
 * 
 * @export
 * @interface LoginResponse
 */
export interface LoginResponse {
    /**
     * 
     * @type {string}
     * @memberof LoginResponse
     */
    token?: string;
    /**
     * 
     * @type {string}
     * @memberof LoginResponse
     */
    type?: string;
    /**
     * 
     * @type {number}
     * @memberof LoginResponse
     */
    id?: number;
    /**
     * 
     * @type {string}
     * @memberof LoginResponse
     */
    username?: string;
    /**
     * 
     * @type {string}
     * @memberof LoginResponse
     */
    email?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof LoginResponse
     */
    roles?: Array<string>;
}
/**
 * 
 * @export
 * @interface ProfileResponse
 */
export interface ProfileResponse {
    /**
     * 
     * @type {boolean}
     * @memberof ProfileResponse
     */
    success?: boolean;
    /**
     * 
     * @type {string}
     * @memberof ProfileResponse
     */
    message?: string;
    /**
     * 
     * @type {User}
     * @memberof ProfileResponse
     */
    data?: User;
}
/**
 * 
 * @export
 * @interface RegisterRequest
 */
export interface RegisterRequest {
    /**
     * 
     * @type {string}
     * @memberof RegisterRequest
     */
    username: string;
    /**
     * 
     * @type {string}
     * @memberof RegisterRequest
     */
    email: string;
    /**
     * 
     * @type {string}
     * @memberof RegisterRequest
     */
    password: string;
    /**
     * 
     * @type {boolean}
     * @memberof RegisterRequest
     */
    seedAccountData?: boolean;
}
/**
 * 
 * @export
 * @interface User
 */
export interface User {
    /**
     * 
     * @type {number}
     * @memberof User
     */
    id?: number;
    /**
     * 
     * @type {string}
     * @memberof User
     */
    username?: string;
    /**
     * 
     * @type {string}
     * @memberof User
     */
    email?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof User
     */
    roles?: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof User
     */
    createdAt?: string;
    /**
     * 
     * @type {string}
     * @memberof User
     */
    updatedAt?: string;
}

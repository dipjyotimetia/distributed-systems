/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface AgentChatRequest
 */
export interface AgentChatRequest {
    /**
     * 
     * @type {string}
     * @memberof AgentChatRequest
     */
    message: string;
    /**
     * 
     * @type {string}
     * @memberof AgentChatRequest
     */
    accountId?: string | null;
    /**
     * 
     * @type {string}
     * @memberof AgentChatRequest
     */
    locale?: string;
    /**
     * 
     * @type {string}
     * @memberof AgentChatRequest
     */
    conversationClaim?: string | null;
    /**
     * 
     * @type {string}
     * @memberof AgentChatRequest
     */
    authToken?: string;
    /**
     * 
     * @type {boolean}
     * @memberof AgentChatRequest
     */
    wait?: boolean;
}
/**
 * 
 * @export
 * @interface ChatRequest
 */
export interface ChatRequest {
    /**
     * 
     * @type {string}
     * @memberof ChatRequest
     */
    message: string;
    /**
     * 
     * @type {string}
     * @memberof ChatRequest
     */
    accountContext?: string;
    /**
     * 
     * @type {string}
     * @memberof ChatRequest
     */
    locale?: string;
}
/**
 * 
 * @export
 * @interface ChatResponse
 */
export interface ChatResponse {
    /**
     * 
     * @type {Array<ProviderResult>}
     * @memberof ChatResponse
     */
    results: Array<ProviderResult>;
}
/**
 * 
 * @export
 * @interface DisputeResearchRequest
 */
export interface DisputeResearchRequest {
    /**
     * 
     * @type {number}
     * @memberof DisputeResearchRequest
     */
    caseId: number;
    /**
     * 
     * @type {string}
     * @memberof DisputeResearchRequest
     */
    locale?: string;
    /**
     * 
     * @type {boolean}
     * @memberof DisputeResearchRequest
     */
    wait?: boolean;
}
/**
 * 
 * @export
 * @interface Health
 */
export interface Health {
    /**
     * 
     * @type {string}
     * @memberof Health
     */
    status?: string;
    /**
     * 
     * @type {string}
     * @memberof Health
     */
    temporalQueue?: string;
}
/**
 * 
 * @export
 * @interface Provider
 */
export interface Provider {
    /**
     * 
     * @type {string}
     * @memberof Provider
     */
    provider?: string;
    /**
     * 
     * @type {string}
     * @memberof Provider
     */
    name?: string;
    /**
     * 
     * @type {string}
     * @memberof Provider
     */
    model?: string;
}
/**
 * 
 * @export
 * @interface ProviderResult
 */
export interface ProviderResult {
    /**
     * 
     * @type {string}
     * @memberof ProviderResult
     */
    provider: string;
    /**
     * 
     * @type {string}
     * @memberof ProviderResult
     */
    name: string;
    /**
     * 
     * @type {string}
     * @memberof ProviderResult
     */
    model: string;
    /**
     * 
     * @type {string}
     * @memberof ProviderResult
     */
    message?: string;
    /**
     * 
     * @type {string}
     * @memberof ProviderResult
     */
    error?: string | null;
    /**
     * 
     * @type {number}
     * @memberof ProviderResult
     */
    durationMs?: number;
}

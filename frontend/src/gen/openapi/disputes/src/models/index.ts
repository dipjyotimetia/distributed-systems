/* tslint:disable */
/* eslint-disable */
/**
 * 
 * @export
 * @interface DisputeCase
 */
export interface DisputeCase {
    /**
     * 
     * @type {boolean}
     * @memberof DisputeCase
     */
    evidenceEnabled?: boolean;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    investigationStage?: string;
    /**
     * 
     * @type {EvidenceRequest}
     * @memberof DisputeCase
     */
    evidenceRequest?: EvidenceRequest;
    /**
     * 
     * @type {number}
     * @memberof DisputeCase
     */
    id?: number;
    /**
     * 
     * @type {number}
     * @memberof DisputeCase
     */
    transferId?: number;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    reason?: string;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    status?: string;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    createdAt?: string;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    escalatedAt?: string;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    resolvedAt?: string;
    /**
     * 
     * @type {string}
     * @memberof DisputeCase
     */
    resolution?: string;
    /**
     * 
     * @type {any}
     * @memberof DisputeCase
     */
    aiDraft?: any | null;
}
/**
 * 
 * @export
 * @interface DisputePage
 */
export interface DisputePage {
    /**
     * 
     * @type {Array<DisputeCase>}
     * @memberof DisputePage
     */
    items: Array<DisputeCase>;
    /**
     * 
     * @type {boolean}
     * @memberof DisputePage
     */
    hasMore: boolean;
}
/**
 * 
 * @export
 * @interface ErrorMessage
 */
export interface ErrorMessage {
    /**
     * 
     * @type {string}
     * @memberof ErrorMessage
     */
    message: string;
}
/**
 * 
 * @export
 * @interface EvidenceRequest
 */
export interface EvidenceRequest {
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    id?: string;
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    prompt?: string;
    /**
     * 
     * @type {number}
     * @memberof EvidenceRequest
     */
    requestedBy?: number;
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    requestedAt?: string;
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    response?: string;
    /**
     * 
     * @type {number}
     * @memberof EvidenceRequest
     */
    respondedBy?: number;
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    respondedAt?: string;
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    deadline?: string;
    /**
     * 
     * @type {string}
     * @memberof EvidenceRequest
     */
    closedAt?: string;
}
/**
 * 
 * @export
 * @interface EvidenceText
 */
export interface EvidenceText {
    /**
     * 
     * @type {string}
     * @memberof EvidenceText
     */
    text: string;
}
/**
 * 
 * @export
 * @interface FileDisputeRequest
 */
export interface FileDisputeRequest {
    /**
     * 
     * @type {number}
     * @memberof FileDisputeRequest
     */
    transferId: number;
    /**
     * 
     * @type {string}
     * @memberof FileDisputeRequest
     */
    reason: string;
}
/**
 * 
 * @export
 * @interface ResolutionRequest
 */
export interface ResolutionRequest {
    /**
     * 
     * @type {string}
     * @memberof ResolutionRequest
     */
    resolution: string;
}

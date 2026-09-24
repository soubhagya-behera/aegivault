package com.aegivault.aegivault.gateway;

/**
 * Deterministic inspection outcome for one AI request: either it may
 * proceed or it must not. Carries no payload — see
 * {@link SecurityInspectionResult} for the safe reason codes.
 */
public enum SecurityVerdict {
    ALLOW,
    BLOCK
}

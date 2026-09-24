package com.aegivault.aegivault.gateway;

/**
 * Safe machine-readable reason codes for a {@link SecurityVerdict#BLOCK}.
 * The enum name is the code: category labels only, never matched values,
 * request content, or secrets.
 */
public enum BlockReason {
    PII_DETECTED,
    SECRET_DETECTED
}

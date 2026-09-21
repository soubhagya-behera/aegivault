package com.aegivault.aegivault.sanitization;

/**
 * Domain failure for a sanitization request that cannot be completed safely,
 * for example a strategy that has no registered implementation. Messages name
 * policy metadata (PII types, strategies) only and never include data values.
 */
public class SanitizationException extends RuntimeException {

    public SanitizationException(String message) {
        super(message);
    }
}

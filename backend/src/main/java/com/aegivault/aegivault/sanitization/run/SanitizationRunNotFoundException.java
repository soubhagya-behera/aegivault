package com.aegivault.aegivault.sanitization.run;

/**
 * Thrown when no sanitization run with the id exists for the calling owner.
 * Missing ids and other owners' ids produce this same exception so callers
 * cannot probe for another owner's runs.
 */
public class SanitizationRunNotFoundException extends RuntimeException {

    public SanitizationRunNotFoundException() {
        super("Sanitization run not found.");
    }
}

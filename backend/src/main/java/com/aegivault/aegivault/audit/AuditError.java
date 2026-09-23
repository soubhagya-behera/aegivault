package com.aegivault.aegivault.audit;

/**
 * Safe error body for the audit endpoints, mirroring the existing
 * {@code {"message": "..."}} convention: a fixed sentence, never database
 * or stack-trace details.
 *
 * @param message fixed human sentence, never an exception message
 */
public record AuditError(String message) {
}

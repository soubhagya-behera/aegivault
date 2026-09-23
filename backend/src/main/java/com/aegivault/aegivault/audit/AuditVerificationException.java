package com.aegivault.aegivault.audit;

/**
 * The verification operation itself failed unexpectedly (infrastructure,
 * not cryptography): the ledger could not be read or replayed at all.
 * This is distinct from a verification that ran and found a break, which
 * stays a normal {@code valid=false} response. The message is generic on
 * purpose — the cause is retained for server logs, but database and
 * stack-trace details must never reach an API response.
 */
public class AuditVerificationException extends RuntimeException {

    public AuditVerificationException(Throwable cause) {
        super("Unable to verify audit ledger.", cause);
    }
}

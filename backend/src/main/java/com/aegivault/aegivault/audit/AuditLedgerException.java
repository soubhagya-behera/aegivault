package com.aegivault.aegivault.audit;

/**
 * An audit entry that should exist does not: appending to the ledger
 * failed after the lifecycle transition it records had already committed.
 * The message is generic on purpose — the cause is retained for server
 * logs, but database and stack-trace details must never reach an API
 * response. Callers do not retry: there is no retry queue, and replaying
 * the request would create a second run rather than fill the gap.
 */
public class AuditLedgerException extends RuntimeException {

    public AuditLedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}

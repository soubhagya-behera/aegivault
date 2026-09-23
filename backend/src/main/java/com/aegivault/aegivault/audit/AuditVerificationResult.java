package com.aegivault.aegivault.audit;

/**
 * Outcome of one ledger verification replay: either the whole chain is
 * intact, or the first broken entry plus a machine-readable reason. A
 * result is a value, never an exception — verification reports what it
 * found; callers decide what a broken chain means. An empty ledger is
 * valid: there is nothing to contradict.
 *
 * @param valid whether every entry checked out
 * @param failureReason machine-readable cause when invalid, null when valid
 * @param failedSequenceNumber 1-based sequence of the first broken entry,
 *        null when valid
 */
public record AuditVerificationResult(boolean valid, String failureReason, Long failedSequenceNumber) {

    static AuditVerificationResult ok() {
        return new AuditVerificationResult(true, null, null);
    }

    static AuditVerificationResult broken(String failureReason, long failedSequenceNumber) {
        return new AuditVerificationResult(false, failureReason, failedSequenceNumber);
    }
}

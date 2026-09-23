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
 * @param entriesChecked how many entries the replay examined — the whole
 *        chain as read in one pass, so the count always matches the verdict
 */
public record AuditVerificationResult(
        boolean valid, String failureReason, Long failedSequenceNumber, long entriesChecked) {

    static AuditVerificationResult ok(long entriesChecked) {
        return new AuditVerificationResult(true, null, null, entriesChecked);
    }

    static AuditVerificationResult broken(
            String failureReason, long failedSequenceNumber, long entriesChecked) {
        return new AuditVerificationResult(false, failureReason, failedSequenceNumber, entriesChecked);
    }
}

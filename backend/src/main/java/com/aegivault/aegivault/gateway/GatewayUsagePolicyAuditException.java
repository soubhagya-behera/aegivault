package com.aegivault.aegivault.gateway;

/**
 * Signals that a gateway usage policy decision could not be written to the
 * audit ledger, so there is no evidence that the decision was ever made. The
 * message is the entire safe HTTP body — it carries no database detail, hash,
 * actor, policy id, counter value, or underlying exception text.
 *
 * <p><strong>Fail closed.</strong> A quota decision whose evidence cannot be
 * stored is not a decision the system is entitled to act on, so the request is
 * not admitted — and, deliberately, a refused request does not return its
 * ordinary HTTP 429 either, because that status would imply the rejection had
 * been durably recorded when it had not.
 *
 * <p>Deliberately distinct from the inspection audit failure
 * ({@code AuditLedgerException}, HTTP 500 {@code Unable to record audit
 * event.}): the two record different evidence about different stages of a
 * request, and keeping them apart stops a reader from assuming an inspection
 * happened when only a policy check did.
 */
public class GatewayUsagePolicyAuditException extends RuntimeException {

    /** The only safe policy-audit failure message, shared by throw site and handler. */
    public static final String MESSAGE = "Unable to record gateway policy audit event.";

    public GatewayUsagePolicyAuditException(Throwable cause) {
        super(MESSAGE, cause);
    }
}

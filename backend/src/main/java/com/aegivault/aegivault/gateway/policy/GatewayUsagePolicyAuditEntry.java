package com.aegivault.aegivault.gateway.policy;

import com.aegivault.aegivault.audit.AuditLedgerEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read projection of one audit ledger entry, shaped for an owner
 * reading their own policy history.
 *
 * <p>This is a deliberately narrow view of {@link AuditLedgerEntry}. It
 * carries only what answers "what happened to this policy, and when":
 * the event type, the policy id the entry concerns, the safe event metadata
 * document exactly as it was written, and the instant it was recorded.
 *
 * <p>Everything about the ledger's internals is withheld: the chain
 * {@code sequenceNumber}, {@code previousHash}, and {@code entryHash} are
 * never projected, the {@code actorSubject} is never repeated here (the
 * caller is already the authenticated actor the query was scoped to), and
 * the entry's own database id is not exposed either. Those fields belong to
 * integrity verification, which stays a separate concern reachable only
 * through the audit verification endpoint.
 *
 * <p>{@code eventData} is passed through verbatim rather than re-derived or
 * enriched: whatever the policy audit events recorded is exactly what comes
 * back, so no policy name, description, limit, enabled state, owner, usage
 * count, counter, Redis key, prompt, provider response, PII, or secret can
 * appear here — this layer reads the ledger and never joins it back to
 * current policy state.
 *
 * @param eventType the recorded policy event type, never null
 * @param resourceId the policy UUID the entry concerns, never null
 * @param eventData the safe metadata document, verbatim, never null
 * @param createdAt when the entry was recorded (UTC), never null
 */
public record GatewayUsagePolicyAuditEntry(
        String eventType,
        UUID resourceId,
        String eventData,
        Instant createdAt) {

    /**
     * Projects one ledger entry. {@code resourceId} is the entry's own
     * resource id rather than the id that was queried, so a projection can
     * never claim to concern a policy the entry did not.
     */
    static GatewayUsagePolicyAuditEntry from(AuditLedgerEntry entry) {
        return new GatewayUsagePolicyAuditEntry(
                entry.getEventType(),
                entry.getResourceId(),
                entry.getEventData(),
                entry.getCreatedAt());
    }
}

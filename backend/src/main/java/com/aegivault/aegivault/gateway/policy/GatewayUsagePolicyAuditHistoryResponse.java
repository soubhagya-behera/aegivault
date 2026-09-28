package com.aegivault.aegivault.gateway.policy;

import java.util.List;

/**
 * API response for {@code GET /api/gateway/policies/{policyId}/audit}: the
 * authenticated owner's audit history for that one policy, newest first.
 *
 * <p>The list may legitimately be empty — for a policy that never existed,
 * one owned by another actor, or one with no recorded activity — and an
 * empty history is a normal 200 answer rather than an error, so this
 * endpoint cannot be used to probe whether someone else's policy id is
 * real.
 *
 * <p>Each entry is a {@link GatewayUsagePolicyAuditEntry}: event type,
 * resource id, the safe event metadata verbatim, and the recorded instant.
 * No chain hashes, sequence number, actor subject, or other ledger
 * internals appear anywhere in this response, and no current policy state
 * is joined in. There is no pagination: the API is bounded to the newest
 * 100 entries.
 *
 * @param entries the owner's policy history, newest first, never null
 */
public record GatewayUsagePolicyAuditHistoryResponse(List<GatewayUsagePolicyAuditEntry> entries) {

    /**
     * Wraps a query result, mapping a null list to empty so the response can
     * never carry a null {@code entries} field.
     */
    public static GatewayUsagePolicyAuditHistoryResponse of(List<GatewayUsagePolicyAuditEntry> entries) {
        return new GatewayUsagePolicyAuditHistoryResponse(
                entries == null ? List.of() : List.copyOf(entries));
    }
}

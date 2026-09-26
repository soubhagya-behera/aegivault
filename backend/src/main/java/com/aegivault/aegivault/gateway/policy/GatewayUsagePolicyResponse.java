package com.aegivault.aegivault.gateway.policy;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of a stored gateway usage policy: its label, the request and
 * token quantity limits it declares, whether it is enabled, and timestamps.
 *
 * <p>{@code ownerSubject} is deliberately excluded — it is an internal
 * authorization field and every endpoint here is already scoped to the
 * authenticated owner — and no persistence detail (table, column, or
 * derived key) appears either. There are no pricing, currency, or cost
 * fields, so this view cannot carry any.
 *
 * @param id policy id
 * @param name human label, at most 255 characters
 * @param description optional free text, at most 1024 characters, may be null
 * @param requestsPerMinute declared requests per minute, null when unset
 * @param requestsPerDay declared requests per day, null when unset
 * @param tokensPerDay declared tokens per day, null when unset
 * @param enabled whether the definition is switched on (not yet consulted
 *        by any gateway code path)
 * @param createdAt creation instant (UTC)
 * @param updatedAt last-update instant (UTC)
 */
public record GatewayUsagePolicyResponse(
        UUID id,
        String name,
        String description,
        Long requestsPerMinute,
        Long requestsPerDay,
        Long tokensPerDay,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    static GatewayUsagePolicyResponse from(GatewayUsagePolicy policy) {
        return new GatewayUsagePolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getDescription(),
                policy.getRequestsPerMinute(),
                policy.getRequestsPerDay(),
                policy.getTokensPerDay(),
                policy.isEnabled(),
                policy.getCreatedAt(),
                policy.getUpdatedAt());
    }
}
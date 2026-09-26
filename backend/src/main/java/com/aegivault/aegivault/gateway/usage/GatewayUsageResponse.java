package com.aegivault.aegivault.gateway.usage;

import java.util.List;

/**
 * Authenticated self-service gateway usage response: at most the 100
 * newest usage records for the currently authenticated actor plus
 * that actor's database-side aggregate over all persisted rows.
 * Usage metadata only — never actor subjects, prompt or response
 * content, secrets, PII, or Redis information.
 */
public record GatewayUsageResponse(
        List<GatewayUsageHistoryItemResponse> history, GatewayUsageAggregateResponse aggregate) {

    public GatewayUsageResponse {
        history = history == null ? List.of() : List.copyOf(history);
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
    }
}

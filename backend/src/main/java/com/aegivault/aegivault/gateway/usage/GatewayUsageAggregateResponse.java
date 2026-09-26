package com.aegivault.aegivault.gateway.usage;

/**
 * API view of one actor's gateway usage aggregate: metadata only,
 * never money, cost, budgets, or quotas. {@code recordCount} is the
 * exact number of persisted rows for the actor; each token total is
 * the exact database-side total of known values, or null when no
 * known value exists — null means unknown, never zero.
 */
public record GatewayUsageAggregateResponse(
        long recordCount, Long promptTokens, Long completionTokens, Long totalTokens) {

    static GatewayUsageAggregateResponse from(GatewayUsageAggregate aggregate) {
        return new GatewayUsageAggregateResponse(
                aggregate.recordCount(),
                aggregate.promptTokens(),
                aggregate.completionTokens(),
                aggregate.totalTokens());
    }
}

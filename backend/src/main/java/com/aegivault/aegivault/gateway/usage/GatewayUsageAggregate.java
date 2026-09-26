package com.aegivault.aegivault.gateway.usage;

/**
 * Immutable aggregate over one actor's gateway usage records:
 * metadata only, never money, cost, budgets, quotas, or utilization.
 *
 * <p>{@code recordCount} is the exact number of matching usage rows.
 * Each token sum is the exact database-side total of the known values
 * for that column — or null when no known value exists. Null means
 * unknown, never zero: an actor whose providers reported no prompt
 * counts aggregates to a null {@code promptTokens} even when
 * {@code recordCount} is positive, so "no known token values" stays
 * unambiguous against "a real numeric total".
 *
 * @param recordCount exact matching-row count, never negative
 * @param promptTokens total of known prompt token values, null when none
 *        are known
 * @param completionTokens total of known completion token values, null
 *        when none are known
 * @param totalTokens total of known total token values, null when none
 *        are known
 */
public record GatewayUsageAggregate(
        long recordCount, Long promptTokens, Long completionTokens, Long totalTokens) {

    public GatewayUsageAggregate {
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount must not be negative");
        }
        if (promptTokens != null && promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens must not be negative");
        }
        if (completionTokens != null && completionTokens < 0) {
            throw new IllegalArgumentException("completionTokens must not be negative");
        }
        if (totalTokens != null && totalTokens < 0) {
            throw new IllegalArgumentException("totalTokens must not be negative");
        }
    }
}

package com.aegivault.aegivault.gateway.policy;

/**
 * One immutable, already-collected usage observation for a single actor,
 * supplied to {@link GatewayUsagePolicyEvaluator}.
 *
 * <p>This type is pure input. Nothing collects it, queries it, or derives it:
 * the caller decides where the numbers come from and is responsible for
 * their accuracy. No value is ever inferred here — in particular a token
 * total is never estimated from characters, bytes, request count, or
 * response size. When the true total is unavailable the snapshot says so
 * explicitly through {@code tokenUsageKnown} and leaves the number null,
 * because "unknown" and "zero" are different facts.
 *
 * <p>{@code tokenUsageKnown} is the trust flag, not the number's presence:
 * it must be true only when {@code totalTokensInCurrentDay} is a real
 * measurement. A snapshot claiming to know the total while supplying none
 * is contradictory and is rejected.
 *
 * @param requestsInCurrentMinute observed request count for this minute, at
 *        least zero
 * @param requestsInCurrentDay observed request count for this day, at least
 *        zero
 * @param totalTokensInCurrentDay observed token total for this day, null
 *        when unknown
 * @param tokenUsageKnown whether {@code totalTokensInCurrentDay} is a
 *        trustworthy measurement; false means the total is unknown, never
 *        zero
 * @throws IllegalArgumentException when a count is negative, or when the
 *         total is declared known but absent
 */
public record GatewayUsagePolicyUsageSnapshot(
        long requestsInCurrentMinute,
        long requestsInCurrentDay,
        Long totalTokensInCurrentDay,
        boolean tokenUsageKnown) {

    public GatewayUsagePolicyUsageSnapshot {
        if (requestsInCurrentMinute < 0L) {
            throw new IllegalArgumentException("requestsInCurrentMinute must not be negative");
        }
        if (requestsInCurrentDay < 0L) {
            throw new IllegalArgumentException("requestsInCurrentDay must not be negative");
        }
        if (totalTokensInCurrentDay != null && totalTokensInCurrentDay < 0L) {
            throw new IllegalArgumentException("totalTokensInCurrentDay must not be negative");
        }
        if (tokenUsageKnown && totalTokensInCurrentDay == null) {
            throw new IllegalArgumentException("totalTokensInCurrentDay is required when tokenUsageKnown is true");
        }
    }

    /**
     * A snapshot with request counts only: token usage is explicitly
     * unknown, never assumed to be zero.
     */
    public static GatewayUsagePolicyUsageSnapshot withoutTokenUsage(
            long requestsInCurrentMinute, long requestsInCurrentDay) {
        return new GatewayUsagePolicyUsageSnapshot(requestsInCurrentMinute, requestsInCurrentDay, null, false);
    }

    /**
     * A snapshot whose token total is a real measurement.
     *
     * @param totalTokensInCurrentDay the measured total, at least zero
     */
    public static GatewayUsagePolicyUsageSnapshot withTokenUsage(
            long requestsInCurrentMinute, long requestsInCurrentDay, long totalTokensInCurrentDay) {
        return new GatewayUsagePolicyUsageSnapshot(
                requestsInCurrentMinute, requestsInCurrentDay, totalTokensInCurrentDay, true);
    }
}
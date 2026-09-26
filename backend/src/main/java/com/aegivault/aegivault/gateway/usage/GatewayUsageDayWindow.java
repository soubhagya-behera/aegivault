package com.aegivault.aegivault.gateway.usage;

/**
 * Read-only database projection over one actor's usage records inside one
 * time window: how many rows matched, the sum of their known token totals,
 * and how many of those totals were actually known.
 *
 * <p>This is a query projection, not an aggregate model and not a second
 * {@link GatewayUsageAggregate}: {@link GatewayUsageAggregate} sums the
 * three token columns for a window, while this projection exists to answer
 * one narrower question — "is the token total for this window fully known?" —
 * which the sum alone cannot express. A partial sum is deliberately
 * distinguishable from a complete one here, so no caller can mistake "some
 * totals known" for "all totals known".
 *
 * <p>Metadata only, like everything else over this table: no request
 * content, provider content, PII, or secrets.
 */
public interface GatewayUsageDayWindow {

    /** Exact number of usage rows in the window. */
    long getRecordCount();

    /** Sum of the known {@code total_tokens} values, null when none are known. */
    Long getTotalTokens();

    /** How many rows in the window have a non-null {@code total_tokens}. */
    long getKnownTokenCount();
}
package com.aegivault.aegivault.gateway.policy.budget;

/**
 * What the provider phase actually produced, which is the one fact that
 * decides how a token reservation should be settled.
 *
 * <p>This is deliberately about <em>whether a response existed</em>, not about
 * whether the gateway was happy with it. The distinction matters because the two
 * are easily conflated and they account differently:
 *
 * <ul>
 *   <li>{@link #NO_RESPONSE} — the provider call failed before producing any
 *       response. Nothing was generated, so nothing was consumed, and a
 *       reservation held for that attempt must be released.</li>
 *   <li>{@link #RESPONSE_PRODUCED} — a provider response exists, and it may
 *       have consumed tokens whether or not the gateway went on to deliver
 *       it. A response blocked by security inspection still belongs here: the
 *       provider already did the work, and discarding its usage would
 *       under-account for real spend.</li>
 * </ul>
 *
 * <p>A security block is therefore <em>not</em> a third kind of outcome. It is a
 * provider response that the gateway chose not to deliver, and it is settled
 * exactly like any other response.
 */
public enum GatewayProviderPhaseOutcome {

    /** The provider call failed before any response was produced. */
    NO_RESPONSE,

    /**
     * A provider response was produced, and may have consumed tokens. Covers a
     * delivered completion and a response blocked by security inspection
     * alike.
     */
    RESPONSE_PRODUCED
}
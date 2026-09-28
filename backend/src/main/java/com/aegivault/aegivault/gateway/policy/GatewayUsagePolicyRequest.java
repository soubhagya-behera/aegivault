package com.aegivault.aegivault.gateway.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload for both {@code POST /api/gateway/policies} and the full-replacement
 * {@code PUT /api/gateway/policies/{policyId}} — the two contracts are
 * identical, so one record serves both rather than two copies drifting apart.
 *
 * <p>Policy definitions only: a label, an optional bounded description, the
 * request and token quantity limits, and the enabled switch. There is
 * deliberately no {@code ownerSubject} component — ownership comes from the
 * verified JWT subject only, and an attempted {@code ownerSubject} property
 * is not bound and never trusted. There are no pricing, currency, or cost
 * fields either.
 *
 * <p>Each limit must be strictly positive when present (null leaves it
 * unconstrained), and at least one limit must be supplied. A policy that
 * constrains nothing would look valid while changing nothing, so it is
 * rejected: the Bean Validation bounds fail first (400), and the
 * {@link GatewayUsagePolicy} aggregate rejects the same cases again, so the
 * rule holds for any caller, not just this one.
 *
 * <p>Expected JSON shape:
 *
 * <pre>
 * {
 *   "name": "team-default",
 *   "description": "...",
 *   "requestsPerMinute": 60,
 *   "requestsPerDay": 10000,
 *   "tokensPerDay": 1000000,
 *   "reservationTokensPerRequest": 4000,
 *   "enabled": true
 * }
 * </pre>
 *
 * <p>{@code reservationTokensPerRequest} is the policy owner's configured
 * pre-request reservation amount, never a per-call client choice. It must be
 * strictly positive when present, must not exceed {@code tokensPerDay}, and is
 * required whenever {@code tokensPerDay} is present — so a daily token policy
 * cannot be declared without it. The Bean Validation bounds catch a
 * non-positive amount, and {@link GatewayUsagePolicy} enforces the same
 * cross-field rules for any caller.
 *
 * @param name human label, never blank, at most 255 characters
 * @param description optional free text, null or at most 1024 characters
 * @param requestsPerMinute declared requests per minute, null or positive
 * @param requestsPerDay declared requests per day, null or positive
 * @param tokensPerDay declared tokens per day, null or positive
 * @param reservationTokensPerRequest maximum tokens reserved for one request
 *        before provider invocation, null or positive; required with
 *        {@code tokensPerDay} and never greater than it
 * @param enabled whether the definition is switched on; absent means true
 */
public record GatewayUsagePolicyRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1024) String description,
        @Positive Long requestsPerMinute,
        @Positive Long requestsPerDay,
        @Positive Long tokensPerDay,
        @Positive Long reservationTokensPerRequest,
        Boolean enabled) {

    /** Enabled defaults to true when the caller omits it. */
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
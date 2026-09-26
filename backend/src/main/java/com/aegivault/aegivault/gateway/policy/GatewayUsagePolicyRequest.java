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
 *   "enabled": true
 * }
 * </pre>
 *
 * @param name human label, never blank, at most 255 characters
 * @param description optional free text, null or at most 1024 characters
 * @param requestsPerMinute declared requests per minute, null or positive
 * @param requestsPerDay declared requests per day, null or positive
 * @param tokensPerDay declared tokens per day, null or positive
 * @param enabled whether the definition is switched on; absent means true
 */
public record GatewayUsagePolicyRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1024) String description,
        @Positive Long requestsPerMinute,
        @Positive Long requestsPerDay,
        @Positive Long tokensPerDay,
        Boolean enabled) {

    /** Enabled defaults to true when the caller omits it. */
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
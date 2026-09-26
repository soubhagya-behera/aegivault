package com.aegivault.aegivault.gateway.usage;

/**
 * Small safe error body for gateway usage query failures, shaped like the
 * dataset, policy, run, and gateway error bodies so every 4xx response in
 * the API carries the same single {@code message} field. Carries the safe
 * message only — never the actor subject, the submitted window bounds, or
 * exception text.
 */
public record GatewayUsageError(String message) {}
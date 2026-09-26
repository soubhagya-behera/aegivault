package com.aegivault.aegivault.gateway.policy;

/**
 * Small safe error body for gateway usage policy failures, shaped like the
 * dataset, policy, run, and gateway usage error bodies so every 4xx response
 * in the API carries the same single {@code message} field.
 */
public record GatewayUsagePolicyError(String message) {}
package com.aegivault.aegivault.sanitization.policy;

/**
 * Small error body for policy failures; kept inside the policy module and
 * shaped like the dataset and run error bodies so every 4xx response in the
 * API carries the same single {@code message} field.
 */
public record PolicyError(String message) {}

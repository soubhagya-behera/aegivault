package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.sanitization.TransformationRule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API view of a stored policy: labels, the explicit rules, and timestamps
 * only. {@code ownerSubject} is deliberately excluded — it is an internal
 * authorization field and every endpoint here is already scoped to the
 * authenticated owner — and no persistence detail (table, column, or
 * derived key) appears either.
 *
 * <p>Rules are exposed through the existing {@link TransformationRule}
 * record, so the JSON shape is exactly {@code {"piiType": "...",
 * "strategy": "..."}} with enum names only. Nothing in this view can carry
 * raw PII values or CSV data, because nothing in the aggregate stores any.
 *
 * @param id policy id
 * @param name human label, at most 255 characters
 * @param version version label, at most 255 characters
 * @param description optional free text, at most 1024 characters, may be null
 * @param rules ordered rules, at least one, alphabetical by PII type
 * @param createdAt creation instant (UTC)
 * @param updatedAt last-update instant (UTC)
 */
public record PolicyResponse(
        UUID id,
        String name,
        String version,
        String description,
        List<TransformationRule> rules,
        Instant createdAt,
        Instant updatedAt) {

    static PolicyResponse from(SanitizationPolicy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getVersion(),
                policy.getDescription(),
                policy.getRules().stream()
                        .map(rule -> new TransformationRule(
                                rule.getPiiType(), rule.getTransformationStrategy()))
                        .toList(),
                policy.getCreatedAt(),
                policy.getUpdatedAt());
    }
}

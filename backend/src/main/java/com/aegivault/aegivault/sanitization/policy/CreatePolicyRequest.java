package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.sanitization.TransformationRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creation payload for {@code POST /api/policies}. Policy metadata only:
 * two bounded labels, an optional bounded description, and explicit
 * transformation rules — never CSV data, PII values, secrets, or
 * credentials.
 *
 * <p>Rules reuse {@link TransformationRule} directly, so no parallel rule
 * model exists: {@code piiType} and {@code strategy} bind to the existing
 * enums by name and unknown names fail JSON deserialization before any
 * domain code runs (400). Duplicate PII types are rejected by the aggregate
 * through {@code TransformationPlan.of}, which surfaces as 400 as well, and
 * the rule list is implicitly bounded — at most one entry per
 * {@code PiiType} exists, so any longer list must contain a duplicate and
 * can never be accepted. There is deliberately no {@code ownerSubject}
 * component: ownership comes from the verified JWT subject only, and an
 * attempted {@code ownerSubject} property is not bound and never used.
 *
 * <p>Expected JSON shape:
 *
 * <pre>
 * {
 *   "name": "...",
 *   "version": "...",
 *   "description": "...",
 *   "rules": [{"piiType": "EMAIL", "strategy": "SYNTHETIC_EMAIL"}]
 * }
 * </pre>
 *
 * @param name human label, never blank, at most 255 characters (the same
 *        bound the dataset name and run policy labels use)
 * @param version version label, never blank, at most 255 characters
 * @param description optional free text, null or at most 1024 characters
 *        (the same bound the dataset original filename uses)
 * @param rules explicit rules, never null, never empty, no null entries
 */
public record CreatePolicyRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String version,
        @Size(max = 1024) String description,
        @NotNull @Size(min = 1) List<@NotNull TransformationRule> rules) {

    public CreatePolicyRequest {
        if (rules != null) {
            rules = List.copyOf(rules);
        }
    }
}

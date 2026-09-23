package com.aegivault.aegivault.sanitization.policy;

import com.aegivault.aegivault.sanitization.TransformationRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Replacement payload for {@code PUT /api/policies/{policyId}}. The same
 * contract as {@link CreatePolicyRequest}: two bounded labels, an optional
 * bounded description, and a complete replacement rule set — never CSV data,
 * PII values, secrets, or credentials, and never an {@code ownerSubject}
 * (ownership comes from the verified JWT subject only, and an attempted
 * {@code ownerSubject} property is not bound and never used).
 *
 * <p>Rules reuse {@link TransformationRule} directly, like creation: unknown
 * enum names fail JSON deserialization before any domain code runs (400),
 * and duplicate PII types are rejected by the aggregate (400). The rule
 * list is the policy's whole new rule set, not a patch: types absent from
 * the list are removed by the update.
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
 * @param name human label, never blank, at most 255 characters
 * @param version version label, never blank, at most 255 characters
 * @param description optional free text, null or at most 1024 characters
 * @param rules complete replacement rules, never null, never empty, no null
 *        entries
 */
public record UpdatePolicyRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String version,
        @Size(max = 1024) String description,
        @NotNull @Size(min = 1) List<@NotNull TransformationRule> rules) {

    public UpdatePolicyRequest {
        if (rules != null) {
            rules = List.copyOf(rules);
        }
    }
}

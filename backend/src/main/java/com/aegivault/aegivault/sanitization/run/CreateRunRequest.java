package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Future creation payload for {@code POST /api/runs} (endpoint not yet
 * implemented). Policy metadata only: a dataset reference, policy labels,
 * and explicit transformation rules — never CSV data, PII values, secrets,
 * or credentials.
 *
 * <p>Rules reuse {@link TransformationRule} directly, so no parallel rule
 * model exists: {@code piiType} and {@code strategy} bind to the existing
 * enums by name, and unknown names fail JSON deserialization before any
 * domain code runs. Duplicates are rejected by
 * {@link TransformationPlan#of(List)}, and missing types stay missing —
 * the plan fails closed at execution instead of being silently defaulted.
 *
 * <p>Expected JSON shape:
 *
 * <pre>
 * {
 *   "datasetId": "...",
 *   "policyName": "...",
 *   "policyVersion": "...",
 *   "rules": [{"piiType": "EMAIL", "strategy": "SYNTHETIC_EMAIL"}]
 * }
 * </pre>
 *
 * @param datasetId dataset to sanitize, never null
 * @param policyName policy label frozen into the run, never blank
 * @param policyVersion version label frozen into the run, never blank
 * @param rules explicit rules, never null, never empty, no null entries
 */
public record CreateRunRequest(
        @NotNull UUID datasetId,
        @NotBlank String policyName,
        @NotBlank String policyVersion,
        @NotNull @Size(min = 1) List<@NotNull TransformationRule> rules) {

    public CreateRunRequest {
        rules = List.copyOf(rules);
    }

    /**
     * Maps the validated rules to the exact plan they declare: only the
     * supplied types are covered, nothing is defaulted or invented.
     *
     * @return a plan covering exactly the supplied rules
     * @throws IllegalArgumentException when two rules target the same PII type
     */
    public TransformationPlan toTransformationPlan() {
        return TransformationPlan.of(rules);
    }
}

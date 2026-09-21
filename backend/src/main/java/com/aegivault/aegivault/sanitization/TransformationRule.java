package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;
import java.util.Objects;

/**
 * One explicit entry of a {@link TransformationPlan}: a detected
 * {@link PiiType} paired with the {@link TransformationStrategy} that should
 * apply to it.
 *
 * <p>Rules exist so a plan can be written down and validated as a list, which
 * makes contradictory plans (two rules for the same type) detectable. A rule
 * carries policy metadata only — never data values.
 *
 * @param piiType   detected PII category the rule applies to, never null
 * @param strategy  transformation to apply, never null
 */
public record TransformationRule(PiiType piiType, TransformationStrategy strategy) {

    public TransformationRule {
        Objects.requireNonNull(piiType, "piiType must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
    }
}

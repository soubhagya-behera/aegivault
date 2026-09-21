package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;

/**
 * Thrown when a {@link TransformationPlan} does not configure a strategy for a
 * detected {@link PiiType}.
 *
 * <p>Sanitization fails closed: an unmapped type is never silently kept,
 * redacted, or defaulted, because silently choosing a transformation would hide
 * a policy gap. Callers either extend the plan or handle the type explicitly.
 * The message names the PII type only.
 */
public class MissingTransformationException extends SanitizationException {

    private final PiiType piiType;

    public MissingTransformationException(PiiType piiType) {
        super("No transformation strategy is configured for PII type " + piiType + ".");
        this.piiType = piiType;
    }

    /**
     * @return the PII type that the plan did not cover
     */
    public PiiType piiType() {
        return piiType;
    }
}

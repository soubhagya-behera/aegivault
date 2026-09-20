package com.aegivault.aegivault.pii;

import java.util.Objects;

/**
 * Immutable result of a single PII detection.
 *
 * <p>Carries only the detected {@link PiiType}. Raw sensitive values are
 * deliberately excluded, and no confidence score is introduced until a
 * genuine scoring requirement emerges.
 */
public record PiiDetection(PiiType type) {

    public PiiDetection {
        Objects.requireNonNull(type, "type must not be null");
    }
}

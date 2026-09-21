package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.springframework.stereotype.Component;

/**
 * REDACT: replaces the whole value with a fixed placeholder.
 *
 * <p>No character of the original value is retained and no length information
 * is leaked, so this strategy is the choice for free text (names, addresses)
 * and for credentials where masking a tail would still expose data.
 */
@Component
public class RedactTransformation implements ValueTransformation {

    public static final String REPLACEMENT = "[REDACTED]";

    @Override
    public TransformationStrategy strategy() {
        return TransformationStrategy.REDACT;
    }

    @Override
    public String apply(String value) {
        return REPLACEMENT;
    }
}

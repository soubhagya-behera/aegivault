package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.springframework.stereotype.Component;

/**
 * KEEP: returns the value unchanged.
 *
 * <p>Intended for columns that are explicitly approved to remain as they are,
 * for example a non-sensitive business key. Because the original value survives
 * the pipeline, mapping a detected PII type to KEEP is a deliberate,
 * hand-written policy decision — it is never applied implicitly by a default.
 */
@Component
public class KeepTransformation implements ValueTransformation {

    @Override
    public TransformationStrategy strategy() {
        return TransformationStrategy.KEEP;
    }

    @Override
    public String apply(String value) {
        return value;
    }
}

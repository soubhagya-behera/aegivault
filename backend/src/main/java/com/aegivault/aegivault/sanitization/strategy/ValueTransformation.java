package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;

/**
 * Contract for one pure value transformation.
 *
 * <p>Implementations are stateless and deterministic: the same input value must
 * always produce the same output value within a process and across processes,
 * because relationship preservation (identical inputs mapping to identical
 * outputs) depends on it. Determinism must come from the input value and stable
 * configuration only — no randomness, no counters, no clock, no mapping store,
 * and no contextual state in this milestone.
 *
 * <p>Implementations are pure functions of their input: they perform no
 * network, database, cache, file, or AI call, they never log, and they never
 * include the input value in an exception message or any other output.
 *
 * <p>Null and blank handling is the caller's responsibility and is implemented
 * once in the sanitization engine, which returns null for a null value and
 * returns blank values unchanged without invoking a transformation. Implementations
 * may therefore assume a non-null, non-blank {@code value} and must never
 * return {@code null}.
 */
public interface ValueTransformation {

    /**
     * Strategy this implementation provides.
     *
     * @return the strategy identity, never null
     */
    TransformationStrategy strategy();

    /**
     * Applies the transformation to one value.
     *
     * @param value non-null, non-blank value to transform
     * @return the transformed value, never null
     */
    String apply(String value);
}

package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Sanitization engine: turns one already-detected value into its sanitized form
 * by following an explicit {@link TransformationPlan}.
 *
 * <p>Boundary: detection and transformation are separate. This class never
 * inspects a value for PII, never chooses a strategy by itself, and never
 * touches {@code PiiDetectorRegistry} — the caller passes the {@link PiiType}
 * that detection produced, and the plan decides what happens to it. A plan also
 * never falls back to the {@link DefaultTransformationPolicy}: the engine only
 * applies the plan it is given.
 *
 * <p>This class reads no CSV, accesses no repository, opens no file, performs no
 * HTTP call, authenticates nobody, persists nothing, logs nothing, and calls no
 * external, cache, or AI service. Values are transformed in memory and returned;
 * nothing is retained after the call.
 *
 * <p>Null and blank policy, applied in one place instead of in every strategy:
 * a null value returns null, and a blank value (empty or whitespace-only) is
 * returned unchanged, because a value without characters carries no PII and
 * rewriting it would only fabricate content. Every other value is transformed
 * by the resolved strategy.
 *
 * <p>Exceptions name policy metadata only. A {@link PiiType} the plan does not
 * cover fails closed with {@link MissingTransformationException} rather than
 * being silently kept.
 */
@Component
public class DataSanitizationService {

    private final TransformationRegistry registry;

    public DataSanitizationService(TransformationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Sanitizes one detected value.
     *
     * @param value    value to sanitize, may be null
     * @param piiType  PII type detection reported for the value, never null
     * @param plan     plan that maps the type to a strategy, never null
     * @return the sanitized value (null for a null value, unchanged for a blank
     *         value), never null for a non-null input
     * @throws MissingTransformationException when the plan does not cover the type
     */
    public String sanitize(String value, PiiType piiType, TransformationPlan plan) {
        return sanitize(value, resolveStrategy(piiType, plan));
    }

    /**
     * Resolves the strategy a plan configures for one PII type.
     *
     * @param piiType PII type to resolve, never null
     * @param plan    plan to consult, never null
     * @return the configured strategy
     * @throws MissingTransformationException when the plan does not cover the type
     */
    public TransformationStrategy resolveStrategy(PiiType piiType, TransformationPlan plan) {
        Objects.requireNonNull(piiType, "piiType must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        return plan.strategyFor(piiType).orElseThrow(() -> new MissingTransformationException(piiType));
    }

    /**
     * Sanitizes one value with an already-resolved strategy.
     *
     * @param value    value to sanitize, may be null
     * @param strategy strategy to apply, never null
     * @return the sanitized value
     * @throws SanitizationException when no implementation is registered for the strategy
     */
    public String sanitize(String value, TransformationStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return value;
        }
        return registry.transformationFor(strategy).apply(value);
    }
}

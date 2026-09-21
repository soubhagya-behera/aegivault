package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, explicit mapping from {@link PiiType} to
 * {@link TransformationStrategy}.
 *
 * <p>The plan is the only place where a sanitization decision is made. The
 * engine never infers a strategy from a detection result, and the mapping is
 * not an intrinsic property of {@link PiiType} — the same type can be mapped
 * differently by different plans.
 *
 * <p>A plan need not cover every type. A type that the plan does not mention
 * fails closed when sanitization is attempted (see
 * {@link MissingTransformationException}) instead of being silently kept or
 * silently rewritten.
 *
 * <p>Plans are constructed from explicit {@link TransformationRule}s, and
 * duplicate rules for the same type are rejected, so a contradictory plan
 * cannot reach the engine. Plans carry policy metadata only; they never contain
 * data values.
 */
public record TransformationPlan(Map<PiiType, TransformationStrategy> strategies) {

    public TransformationPlan {
        Objects.requireNonNull(strategies, "strategies must not be null");
        Map<PiiType, TransformationStrategy> validated = new EnumMap<>(PiiType.class);
        for (Map.Entry<PiiType, TransformationStrategy> entry : strategies.entrySet()) {
            PiiType piiType = Objects.requireNonNull(entry.getKey(), "piiType must not be null");
            TransformationStrategy strategy = Objects.requireNonNull(entry.getValue(), "strategy must not be null");
            validated.put(piiType, strategy);
        }
        strategies = Map.copyOf(validated);
    }

    /**
     * Builds a plan from explicit rules.
     *
     * @param rules transformation rules, never null, no null entries
     * @return an immutable plan covering exactly the supplied types
     * @throws IllegalArgumentException when two rules target the same PII type
     */
    public static TransformationPlan of(List<TransformationRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        Map<PiiType, TransformationStrategy> strategies = new EnumMap<>(PiiType.class);
        for (TransformationRule rule : rules) {
            Objects.requireNonNull(rule, "rule must not be null");
            TransformationStrategy existing = strategies.putIfAbsent(rule.piiType(), rule.strategy());
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate transformation rule for PII type " + rule.piiType() + ".");
            }
        }
        return new TransformationPlan(strategies);
    }

    /**
     * Builds a plan from explicit rules.
     *
     * @param rules transformation rules, never null, no null entries
     * @return an immutable plan covering exactly the supplied types
     * @throws IllegalArgumentException when two rules target the same PII type
     */
    public static TransformationPlan of(TransformationRule... rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        return of(List.of(rules));
    }

    /**
     * Resolves the strategy configured for one PII type.
     *
     * @param piiType detected PII type, never null
     * @return the configured strategy, or empty when the plan does not cover
     *         the type
     */
    public Optional<TransformationStrategy> strategyFor(PiiType piiType) {
        Objects.requireNonNull(piiType, "piiType must not be null");
        return Optional.ofNullable(strategies.get(piiType));
    }
}

package com.aegivault.aegivault.sanitization.strategy;

import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Orchestration-only registry over the available {@link ValueTransformation}
 * beans.
 *
 * <p>Maps each {@link TransformationStrategy} to the single implementation that
 * provides it, so the engine resolves behaviour without a switch statement and
 * a duplicate implementation is a startup failure instead of a silent
 * last-one-wins. The registry contains no transformation logic itself, and
 * detection is not involved at all.
 */
@Component
public class TransformationRegistry {

    private final Map<TransformationStrategy, ValueTransformation> byStrategy;

    public TransformationRegistry(List<ValueTransformation> transformations) {
        Objects.requireNonNull(transformations, "transformations must not be null");
        Map<TransformationStrategy, ValueTransformation> registered = new EnumMap<>(TransformationStrategy.class);
        for (ValueTransformation transformation : transformations) {
            Objects.requireNonNull(transformation, "transformation must not be null");
            TransformationStrategy strategy =
                    Objects.requireNonNull(transformation.strategy(), "strategy must not be null");
            if (registered.putIfAbsent(strategy, transformation) != null) {
                throw new IllegalArgumentException(
                        "Duplicate transformation implementation for strategy " + strategy + ".");
            }
        }
        this.byStrategy = Map.copyOf(registered);
    }

    /**
     * Resolves the implementation of one strategy.
     *
     * @param strategy strategy to resolve, never null
     * @return the registered implementation
     * @throws SanitizationException when no implementation is registered
     */
    public ValueTransformation transformationFor(TransformationStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        ValueTransformation transformation = byStrategy.get(strategy);
        if (transformation == null) {
            throw new SanitizationException(
                    "No transformation implementation is registered for strategy " + strategy + ".");
        }
        return transformation;
    }
}

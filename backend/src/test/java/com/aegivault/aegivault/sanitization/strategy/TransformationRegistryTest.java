package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link TransformationRegistry} (no Spring, no I/O). */
class TransformationRegistryTest {

    private static List<ValueTransformation> allTransformations() {
        return List.of(
                new KeepTransformation(),
                new RedactTransformation(),
                new MaskTransformation(),
                new SyntheticEmailTransformation(),
                new SyntheticPhoneTransformation(),
                new Sha256HashTransformation());
    }

    @Test
    void resolvesEveryRegisteredStrategy() {
        TransformationRegistry registry = new TransformationRegistry(allTransformations());
        for (TransformationStrategy strategy : TransformationStrategy.values()) {
            assertThat(registry.transformationFor(strategy).strategy()).isEqualTo(strategy);
        }
    }

    @Test
    void returnsTheSameInstanceForRepeatedLookups() {
        TransformationRegistry registry = new TransformationRegistry(allTransformations());
        assertThat(registry.transformationFor(TransformationStrategy.MASK))
                .isSameAs(registry.transformationFor(TransformationStrategy.MASK));
    }

    @Test
    void rejectsDuplicateImplementationsOfOneStrategy() {
        assertThatThrownBy(() -> new TransformationRegistry(
                        List.of(new KeepTransformation(), new KeepTransformation())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate transformation implementation for strategy KEEP");
    }

    @Test
    void rejectsNullAndMissingImplementations() {
        assertThatThrownBy(() -> new TransformationRegistry(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransformationRegistry(Arrays.asList((ValueTransformation) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transformation must not be null");
        assertThatThrownBy(() -> new TransformationRegistry(List.of()).transformationFor(TransformationStrategy.REDACT))
                .isInstanceOf(SanitizationException.class)
                .hasMessageContaining("No transformation implementation is registered for strategy REDACT");
    }

    @Test
    void rejectsNullStrategyLookup() {
        TransformationRegistry registry = new TransformationRegistry(allTransformations());
        assertThatThrownBy(() -> registry.transformationFor(null)).isInstanceOf(NullPointerException.class);
    }
}

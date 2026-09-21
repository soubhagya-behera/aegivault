package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link KeepTransformation} (no Spring, no I/O). */
class KeepTransformationTest {

    private final ValueTransformation transformation = new KeepTransformation();

    @Test
    void reportsItsStrategy() {
        assertThat(transformation.strategy()).isEqualTo(TransformationStrategy.KEEP);
    }

    @Test
    void returnsTheValueUnchanged() {
        assertThat(transformation.apply("alice@example.com")).isEqualTo("alice@example.com");
    }

    @Test
    void keepsNonSensitiveBusinessValues() {
        assertThat(transformation.apply("ORD-2026-001")).isEqualTo("ORD-2026-001");
    }

    @Test
    void outputIsDeterministic() {
        assertThat(transformation.apply("alice@example.com")).isEqualTo(transformation.apply("alice@example.com"));
    }
}

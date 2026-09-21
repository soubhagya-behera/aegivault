package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link MaskTransformation} (no Spring, no I/O). */
class MaskTransformationTest {

    private final ValueTransformation transformation = new MaskTransformation();

    @Test
    void reportsItsStrategy() {
        assertThat(transformation.strategy()).isEqualTo(TransformationStrategy.MASK);
    }

    @Test
    void masksAllButTheExplicitDefaultSuffix() {
        String masked = transformation.apply("4111111111111111");
        assertThat(masked).isEqualTo("************1111");
        assertThat(masked).hasSize(16);
    }

    @Test
    void masksShortValuesEntirely() {
        assertThat(transformation.apply("12")).isEqualTo("**");
        assertThat(transformation.apply("1234")).isEqualTo("****");
    }

    @Test
    void masksTheExactPrefixOfABoundaryLengthValue() {
        assertThat(transformation.apply("12345")).isEqualTo("*2345");
    }

    @Test
    void masksAnyValueGenericallyRatherThanUnderstandingCards() {
        String masked = transformation.apply("alice@example.com");
        assertThat(masked).hasSize("alice@example.com".length());
        assertThat(masked.chars().limit(masked.length() - MaskTransformation.DEFAULT_VISIBLE_SUFFIX))
                .allMatch(character -> character == '*');
    }

    @Test
    void neverReturnsTheOriginalValue() {
        assertThat(transformation.apply("abc")).isNotEqualTo("abc");
        assertThat(transformation.apply("4111111111111111")).isNotEqualTo("4111111111111111");
    }

    @Test
    void supportsACustomVisibleSuffix() {
        assertThat(new MaskTransformation(2).apply("abcdefghij")).isEqualTo("********ij");
        assertThat(new MaskTransformation(0).apply("secret")).isEqualTo("******");
    }

    @Test
    void rejectsANegativeVisibleSuffix() {
        assertThatThrownBy(() -> new MaskTransformation(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outputIsDeterministic() {
        assertThat(transformation.apply("4111111111111111")).isEqualTo(transformation.apply("4111111111111111"));
    }
}

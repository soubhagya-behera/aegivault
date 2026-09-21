package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link RedactTransformation} (no Spring, no I/O). */
class RedactTransformationTest {

    private final ValueTransformation transformation = new RedactTransformation();

    @Test
    void reportsItsStrategy() {
        assertThat(transformation.strategy()).isEqualTo(TransformationStrategy.REDACT);
    }

    @Test
    void replacesTheWholeValueWithAPlaceholder() {
        assertThat(transformation.apply("alice@example.com")).isEqualTo(RedactTransformation.REPLACEMENT);
    }

    @Test
    void retainsNoPartOfTheOriginalValue() {
        String redacted = transformation.apply("alice@example.com");
        assertThat(redacted).doesNotContain("alice");
        assertThat(redacted).doesNotContain("example.com");
        assertThat(redacted).hasSize(RedactTransformation.REPLACEMENT.length());
    }

    @Test
    void outputIsDeterministic() {
        assertThat(transformation.apply("some secret")).isEqualTo(transformation.apply("some secret"));
    }
}

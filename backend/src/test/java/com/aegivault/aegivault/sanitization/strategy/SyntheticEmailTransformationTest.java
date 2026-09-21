package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link SyntheticEmailTransformation} (no Spring, no I/O). */
class SyntheticEmailTransformationTest {

    private final ValueTransformation transformation = new SyntheticEmailTransformation();

    @Test
    void reportsItsStrategy() {
        assertThat(transformation.strategy()).isEqualTo(TransformationStrategy.SYNTHETIC_EMAIL);
    }

    @Test
    void producesAReservedDomainAddressOfFixedShape() {
        assertThat(transformation.apply("alice@example.com"))
                .matches("^user-[0-9a-f]{" + SyntheticEmailTransformation.TOKEN_LENGTH + "}@example\\.invalid$");
    }

    @Test
    void preservesNoPartOfTheOriginalMailbox() {
        String synthetic = transformation.apply("alice@example.com");
        assertThat(synthetic).doesNotContain("alice");
        assertThat(synthetic).doesNotContain("@example.com");
        assertThat(synthetic).endsWith("@" + SyntheticEmailTransformation.SYNTHETIC_DOMAIN);
    }

    @Test
    void outputIsRecognisedByTheProjectEmailDetector() {
        assertThat(new EmailDetector().detect(transformation.apply("alice@example.com"))).isPresent();
    }

    @Test
    void caseVariantsProduceDistinctButStableTokens() {
        String lower = transformation.apply("alice@example.com");
        String upper = transformation.apply("ALICE@EXAMPLE.COM");
        assertThat(upper).isNotEqualTo(lower);
        assertThat(transformation.apply("ALICE@EXAMPLE.COM")).isEqualTo(upper);
    }

    @Test
    void distinctValuesProduceDistinctAddresses() {
        assertThat(transformation.apply("user1@example.com")).isNotEqualTo(transformation.apply("user2@example.com"));
    }

    @Test
    void outputIsDeterministicAcrossInstances() {
        assertThat(new SyntheticEmailTransformation().apply("alice@example.com"))
                .isEqualTo(new SyntheticEmailTransformation().apply("alice@example.com"));
    }
}

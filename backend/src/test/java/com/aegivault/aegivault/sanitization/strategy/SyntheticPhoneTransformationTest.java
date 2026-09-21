package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link SyntheticPhoneTransformation} (no Spring, no I/O). */
class SyntheticPhoneTransformationTest {

    private final ValueTransformation transformation = new SyntheticPhoneTransformation();

    @Test
    void reportsItsStrategy() {
        assertThat(transformation.strategy()).isEqualTo(TransformationStrategy.SYNTHETIC_PHONE);
    }

    @Test
    void producesTenDigitsWithAnAcceptedLeadingDigit() {
        assertThat(transformation.apply("9876543210")).matches("^[6-9]\\d{9}$");
    }

    @Test
    void outputIsAcceptedByTheProjectPhoneDetector() {
        assertThat(new PhoneDetector().detect(transformation.apply("9876543210"))).isPresent();
    }

    @Test
    void preservesNoPartOfTheOriginalNumber() {
        assertThat(transformation.apply("+91 9876543210")).doesNotContain("9876543210");
    }

    @Test
    void distinctValuesProduceDistinctNumbers() {
        assertThat(transformation.apply("9876543210")).isNotEqualTo(transformation.apply("9123456789"));
    }

    @Test
    void outputIsDeterministicAcrossInstances() {
        assertThat(new SyntheticPhoneTransformation().apply("9876543210"))
                .isEqualTo(new SyntheticPhoneTransformation().apply("9876543210"));
    }
}

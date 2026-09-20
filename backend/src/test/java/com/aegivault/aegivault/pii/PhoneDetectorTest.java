package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PhoneDetector} (no Spring context, no I/O).
 */
class PhoneDetectorTest {

    private final PiiDetector detector = new PhoneDetector();

    @Test
    void detectsPlainTenDigits() {
        assertThat(detector.detect("9876543210"))
                .contains(new PiiDetection(PiiType.PHONE));
    }

    @Test
    void detectsTrunkPrefixed() {
        assertThat(detector.detect("09876543210"))
                .contains(new PiiDetection(PiiType.PHONE));
    }

    @Test
    void detectsInternationalCompact() {
        assertThat(detector.detect("+919876543210"))
                .contains(new PiiDetection(PiiType.PHONE));
    }

    @Test
    void detectsInternationalSpaced() {
        assertThat(detector.detect("+91 9876543210"))
                .contains(new PiiDetection(PiiType.PHONE));
    }

    @Test
    void detectsInternationalHyphenated() {
        assertThat(detector.detect("+91-9876543210"))
                .contains(new PiiDetection(PiiType.PHONE));
    }

    @Test
    void rejectsNull() {
        assertThat(detector.detect(null)).isEmpty();
    }

    @Test
    void rejectsBlank() {
        assertThat(detector.detect("   ")).isEmpty();
    }

    @Test
    void rejectsTooShort() {
        assertThat(detector.detect("987654321")).isEmpty();
    }

    @Test
    void rejectsTooLong() {
        assertThat(detector.detect("98765432109876543210")).isEmpty();
    }

    @Test
    void rejectsAlphabeticInput() {
        assertThat(detector.detect("98765abc10")).isEmpty();
    }

    @Test
    void rejectsMultipleNumbersInOneValue() {
        assertThat(detector.detect("9876543210 9876543210")).isEmpty();
    }

    @Test
    void rejectsInvalidPunctuation() {
        assertThat(detector.detect("98765.43210")).isEmpty();
    }

    @Test
    void rejectsParenthesisedValue() {
        assertThat(detector.detect("(098)76543210")).isEmpty();
    }

    @Test
    void rejectsArbitraryTenDigits() {
        assertThat(detector.detect("1234567890")).isEmpty();
    }

    @Test
    void rejectsUnsupportedCountryCode() {
        assertThat(detector.detect("+1 9876543210")).isEmpty();
    }

    @Test
    void rejectsDoubledSeparator() {
        assertThat(detector.detect("98765--43210")).isEmpty();
    }

    @Test
    void resultTypeIsPhone() {
        assertThat(detector.detect("9876543210"))
                .map(PiiDetection::type)
                .contains(PiiType.PHONE);
    }

    @Test
    void resultDoesNotContainRawValue() {
        assertThat(detector.detect("9876543210").orElseThrow().toString())
                .doesNotContain("9876543210");
    }
}

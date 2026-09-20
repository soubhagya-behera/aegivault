package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link EmailDetector} (no Spring context, no I/O).
 */
class EmailDetectorTest {

    private final PiiDetector detector = new EmailDetector();

    @Test
    void detectsStandardEmail() {
        assertThat(detector.detect("alice@example.com"))
                .contains(new PiiDetection(PiiType.EMAIL));
    }

    @Test
    void detectsCorporateEmail() {
        assertThat(detector.detect("john.doe@example.co.in"))
                .contains(new PiiDetection(PiiType.EMAIL));
    }

    @Test
    void detectsPlusAddressing() {
        assertThat(detector.detect("alice+test@example.com"))
                .contains(new PiiDetection(PiiType.EMAIL));
    }

    @Test
    void detectsUppercaseEmail() {
        assertThat(detector.detect("USER@EXAMPLE.COM"))
                .contains(new PiiDetection(PiiType.EMAIL));
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
    void rejectsMissingLocalPart() {
        assertThat(detector.detect("@example.com")).isEmpty();
    }

    @Test
    void rejectsMissingDomain() {
        assertThat(detector.detect("alice@")).isEmpty();
    }

    @Test
    void rejectsMissingAtSign() {
        assertThat(detector.detect("alice")).isEmpty();
    }

    @Test
    void rejectsWhitespaceInsideEmail() {
        assertThat(detector.detect("alice @example.com")).isEmpty();
    }

    @Test
    void rejectsDoubleDotDomain() {
        assertThat(detector.detect("alice@example..com")).isEmpty();
    }

    @Test
    void resultTypeIsEmail() {
        assertThat(detector.detect("alice@example.com"))
                .map(PiiDetection::type)
                .contains(PiiType.EMAIL);
    }

    @Test
    void resultDoesNotContainRawValue() {
        assertThat(detector.detect("alice@example.com").orElseThrow().toString())
                .doesNotContain("alice@example.com");
    }
}

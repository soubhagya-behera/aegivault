package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the PII foundation (no Spring context, no I/O).
 */
class PiiDetectionTest {

    @Test
    void piiTypeContainsExactlyExpectedValues() {
        assertThat(PiiType.values())
                .containsExactly(
                        PiiType.EMAIL,
                        PiiType.PHONE,
                        PiiType.PERSON_NAME,
                        PiiType.ADDRESS,
                        PiiType.CREDIT_CARD,
                        PiiType.IP_ADDRESS,
                        PiiType.UUID,
                        PiiType.API_KEY,
                        PiiType.PASSWORD,
                        PiiType.JWT,
                        PiiType.CUSTOM_IDENTIFIER);
    }

    @Test
    void detectionRetainsType() {
        assertThat(new PiiDetection(PiiType.EMAIL).type()).isEqualTo(PiiType.EMAIL);
    }

    @Test
    void detectionRejectsNullType() {
        assertThatThrownBy(() -> new PiiDetection(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void detectorContractReportsDetectionOrEmpty() {
        PiiDetector detector =
                value -> (value != null && value.contains("@"))
                        ? Optional.of(new PiiDetection(PiiType.EMAIL))
                        : Optional.empty();

        assertThat(detector.detect("a@b")).contains(new PiiDetection(PiiType.EMAIL));
        assertThat(detector.detect("plain")).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("  ")).isEmpty();
    }
}

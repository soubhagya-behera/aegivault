package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link AddressDetector} (no Spring context, no I/O, no network).
 */
class AddressDetectorTest {

    private final PiiDetector detector = new AddressDetector();

    @Test
    void detectsStreetWithHouseNumber() {
        assertThat(detector.detect("221 Baker Street"))
                .contains(new PiiDetection(PiiType.ADDRESS));
    }

    @Test
    void detectsStreetWithPostalCode() {
        assertThat(detector.detect("Maple Avenue Springfield 90210"))
                .contains(new PiiDetection(PiiType.ADDRESS));
    }

    @Test
    void detectsRoadWithHouseNumberAndPin() {
        assertThat(detector.detect("12 MG Road Bengaluru 560001"))
                .contains(new PiiDetection(PiiType.ADDRESS));
    }

    @Test
    void rejectsNumberWithoutStreetSignal() {
        assertThat(detector.detect("Order 12345 shipped")).isEmpty();
    }

    @Test
    void rejectsStreetKeywordWithoutNumberOrPostalCode() {
        assertThat(detector.detect("Baker Street")).isEmpty();
    }

    @Test
    void rejectsUrl() {
        assertThat(detector.detect("https://example.com/Baker Street 221")).isEmpty();
    }

    @Test
    void rejectsEmail() {
        assertThat(detector.detect("221 Baker Street alice@example.com")).isEmpty();
    }

    @Test
    void rejectsPhoneValue() {
        assertThat(detector.detect("9876543210")).isEmpty();
    }

    @Test
    void rejectsIpValue() {
        assertThat(detector.detect("192.168.1.10")).isEmpty();
    }

    @Test
    void rejectsUuidValue() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsCreditCardValue() {
        assertThat(detector.detect("4111111111111111")).isEmpty();
    }

    @Test
    void rejectsNumericId() {
        assertThat(detector.detect("123456789")).isEmpty();
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
    void resultTypeIsAddress() {
        assertThat(detector.detect("221 Baker Street"))
                .map(PiiDetection::type)
                .contains(PiiType.ADDRESS);
    }

    @Test
    void resultDoesNotContainRawValue() {
        assertThat(detector.detect("221 Baker Street").orElseThrow().toString())
                .doesNotContain("221 Baker Street");
    }
}

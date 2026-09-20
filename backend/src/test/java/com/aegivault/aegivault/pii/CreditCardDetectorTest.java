package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CreditCardDetector} (no Spring context, no I/O).
 *
 * <p>All card numbers used are standard non-production test/example numbers.
 */
class CreditCardDetectorTest {

    private static final String VALID_CARD = "4111111111111111";

    private final PiiDetector detector = new CreditCardDetector();

    @Test
    void detectsValidSixteenDigitCard() {
        assertThat(detector.detect("4111111111111111"))
                .contains(new PiiDetection(PiiType.CREDIT_CARD));
    }

    @Test
    void detectsValidCardWithSpaces() {
        assertThat(detector.detect("4111 1111 1111 1111"))
                .contains(new PiiDetection(PiiType.CREDIT_CARD));
    }

    @Test
    void detectsValidCardWithHyphens() {
        assertThat(detector.detect("4111-1111-1111-1111"))
                .contains(new PiiDetection(PiiType.CREDIT_CARD));
    }

    @Test
    void detectsValidFifteenDigitCard() {
        assertThat(detector.detect("378282246310005"))
                .contains(new PiiDetection(PiiType.CREDIT_CARD));
    }

    @Test
    void detectsValidNineteenDigitCard() {
        assertThat(detector.detect("4000000000000000006"))
                .contains(new PiiDetection(PiiType.CREDIT_CARD));
    }

    @Test
    void rejectsSingleDigitChangeBreakingLuhn() {
        assertThat(detector.detect("4111111111111112")).isEmpty();
    }

    @Test
    void rejectsTooShort() {
        assertThat(detector.detect("411111111111")).isEmpty();
    }

    @Test
    void rejectsTooLong() {
        assertThat(detector.detect("41111111111111111111")).isEmpty();
    }

    @Test
    void rejectsLetters() {
        assertThat(detector.detect("4111a11111111111")).isEmpty();
    }

    @Test
    void rejectsUnsupportedPunctuation() {
        assertThat(detector.detect("4111.1111.1111.1111")).isEmpty();
    }

    @Test
    void rejectsMultipleCardsInOneValue() {
        assertThat(detector.detect("4111111111111111 4111111111111111")).isEmpty();
    }

    @Test
    void rejectsEmptyString() {
        assertThat(detector.detect("")).isEmpty();
    }

    @Test
    void rejectsBlankString() {
        assertThat(detector.detect("   ")).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(detector.detect(null)).isEmpty();
    }

    @Test
    void rejectsDoubledSeparator() {
        assertThat(detector.detect("4111--1111-1111-1111")).isEmpty();
    }

    @Test
    void rejectsCorrectLengthFailingLuhn() {
        assertThat(detector.detect("1234567890123456")).isEmpty();
    }

    @Test
    void resultTypeIsCreditCard() {
        assertThat(detector.detect(VALID_CARD))
                .map(PiiDetection::type)
                .contains(PiiType.CREDIT_CARD);
    }

    @Test
    void resultDoesNotContainRawValue() {
        assertThat(detector.detect(VALID_CARD).orElseThrow().toString())
                .doesNotContain(VALID_CARD);
    }
}

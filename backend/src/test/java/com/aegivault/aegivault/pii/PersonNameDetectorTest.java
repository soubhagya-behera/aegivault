package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PersonNameDetector} (no Spring context, no I/O, no NLP).
 */
class PersonNameDetectorTest {

    private final PiiDetector detector = new PersonNameDetector();

    @Test
    void detectsSingleName() {
        assertThat(detector.detect("Alice")).contains(new PiiDetection(PiiType.PERSON_NAME));
    }

    @Test
    void detectsTwoTokenName() {
        assertThat(detector.detect("Alice Johnson"))
                .contains(new PiiDetection(PiiType.PERSON_NAME));
    }

    @Test
    void detectsThreeTokenName() {
        assertThat(detector.detect("Mary Jane Watson"))
                .contains(new PiiDetection(PiiType.PERSON_NAME));
    }

    @Test
    void detectsHyphenatedName() {
        assertThat(detector.detect("Anne-Marie Claire"))
                .contains(new PiiDetection(PiiType.PERSON_NAME));
    }

    @Test
    void detectsApostropheName() {
        assertThat(detector.detect("Daniel O'Brien"))
                .contains(new PiiDetection(PiiType.PERSON_NAME));
    }

    @Test
    void rejectsAdministrator() {
        assertThat(detector.detect("Administrator")).isEmpty();
    }

    @Test
    void rejectsCustomer() {
        assertThat(detector.detect("Customer")).isEmpty();
    }

    @Test
    void rejectsDeveloper() {
        assertThat(detector.detect("Developer")).isEmpty();
    }

    @Test
    void rejectsSentence() {
        assertThat(detector.detect("Alice went home")).isEmpty();
    }

    @Test
    void rejectsTooManyTokens() {
        assertThat(detector.detect("Alice Bob Carol Dave")).isEmpty();
    }

    @Test
    void rejectsEmail() {
        assertThat(detector.detect("alice@example.com")).isEmpty();
    }

    @Test
    void rejectsUrl() {
        assertThat(detector.detect("https://example.com/alice")).isEmpty();
    }

    @Test
    void rejectsIdentifier() {
        assertThat(detector.detect("alice_2024")).isEmpty();
    }

    @Test
    void rejectsNumericValue() {
        assertThat(detector.detect("12345")).isEmpty();
    }

    @Test
    void rejectsAlphanumericCode() {
        assertThat(detector.detect("ORD-2024-987")).isEmpty();
    }

    @Test
    void rejectsLowercaseWord() {
        assertThat(detector.detect("alice")).isEmpty();
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
    void resultTypeIsPersonName() {
        assertThat(detector.detect("Alice Johnson"))
                .map(PiiDetection::type)
                .contains(PiiType.PERSON_NAME);
    }

    @Test
    void resultDoesNotContainRawName() {
        assertThat(detector.detect("Alice Johnson").orElseThrow().toString())
                .doesNotContain("Alice Johnson");
    }
}

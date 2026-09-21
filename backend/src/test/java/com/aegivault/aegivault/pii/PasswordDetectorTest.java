package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PasswordDetector} (no Spring context, no I/O).
 */
class PasswordDetectorTest {

    private final PiiDetector detector = new PasswordDetector();

    @Test
    void detectsPasswordEqualsForm() {
        assertThat(detector.detect("password=Secret1234"))
                .contains(new PiiDetection(PiiType.PASSWORD));
    }

    @Test
    void detectsPasswordColonForm() {
        assertThat(detector.detect("password:Secret1234"))
                .contains(new PiiDetection(PiiType.PASSWORD));
    }

    @Test
    void detectsPasswdForm() {
        assertThat(detector.detect("passwd=Secret1234"))
                .contains(new PiiDetection(PiiType.PASSWORD));
    }

    @Test
    void detectsPwdForm() {
        assertThat(detector.detect("pwd=Secret1234"))
                .contains(new PiiDetection(PiiType.PASSWORD));
    }

    @Test
    void labelMatchingIsCaseInsensitive() {
        assertThat(detector.detect("PASSWORD=Secret1234"))
                .contains(new PiiDetection(PiiType.PASSWORD));
        assertThat(detector.detect("PassWord:Secret1234"))
                .contains(new PiiDetection(PiiType.PASSWORD));
    }

    @Test
    void rejectsMissingValue() {
        assertThat(detector.detect("password=")).isEmpty();
        assertThat(detector.detect("password:")).isEmpty();
    }

    @Test
    void rejectsTooShortSecret() {
        assertThat(detector.detect("password=abc")).isEmpty();
    }

    @Test
    void rejectsWhitespaceInsideSecret() {
        assertThat(detector.detect("password=Secret 1234")).isEmpty();
    }

    @Test
    void rejectsArbitraryLongString() {
        assertThat(detector.detect("correct horse battery staple random words here")).isEmpty();
    }

    @Test
    void rejectsBareAlphanumericString() {
        assertThat(detector.detect("Secret1234Secret1234")).isEmpty();
    }

    @Test
    void rejectsEmbeddedLabelInSentence() {
        assertThat(detector.detect("my password=Secret1234 today")).isEmpty();
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
    void rejectsSurroundingWhitespace() {
        assertThat(detector.detect("  password=Secret1234 ")).isEmpty();
    }

    @Test
    void resultTypeIsPassword() {
        assertThat(detector.detect("password=Secret1234"))
                .map(PiiDetection::type)
                .contains(PiiType.PASSWORD);
    }

    @Test
    void resultDoesNotContainRawSecret() {
        assertThat(detector.detect("password=Secret1234").orElseThrow().toString())
                .doesNotContain("Secret1234");
    }
}

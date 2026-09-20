package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link UuidDetector} (no Spring context, no I/O, no network).
 */
class UuidDetectorTest {

    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";

    private final PiiDetector detector = new UuidDetector();

    @Test
    void detectsStandardLowercaseUuid() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-446655440000"))
                .contains(new PiiDetection(PiiType.UUID));
    }

    @Test
    void detectsUppercaseUuid() {
        assertThat(detector.detect("550E8400-E29B-41D4-A716-446655440000"))
                .contains(new PiiDetection(PiiType.UUID));
    }

    @Test
    void detectsMixedCaseUuid() {
        assertThat(detector.detect("550e8400-E29B-41d4-A716-446655440000"))
                .contains(new PiiDetection(PiiType.UUID));
    }

    @Test
    void detectsAnotherValidUuid() {
        assertThat(detector.detect("123e4567-e89b-12d3-a456-426614174000"))
                .contains(new PiiDetection(PiiType.UUID));
    }

    @Test
    void detectsDifferentVersionPattern() {
        assertThat(detector.detect("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
                .contains(new PiiDetection(PiiType.UUID));
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
    void rejectsEmpty() {
        assertThat(detector.detect("")).isEmpty();
    }

    @Test
    void rejectsSurroundingWhitespace() {
        assertThat(detector.detect(" 550e8400-e29b-41d4-a716-446655440000 ")).isEmpty();
    }

    @Test
    void rejectsEmbeddedUuidInText() {
        assertThat(detector.detect("User ID: 550e8400-e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsMissingHyphen() {
        assertThat(detector.detect("550e8400e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsIncorrectGroupLength() {
        assertThat(detector.detect("550e840-e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsExtraHyphen() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-44665544-0000")).isEmpty();
    }

    @Test
    void rejectsInvalidHexCharacter() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-44665544000g")).isEmpty();
    }

    @Test
    void rejectsBraces() {
        assertThat(detector.detect("{550e8400-e29b-41d4-a716-446655440000}")).isEmpty();
    }

    @Test
    void rejectsParentheses() {
        assertThat(detector.detect("(550e8400-e29b-41d4-a716-446655440000)")).isEmpty();
    }

    @Test
    void rejectsLeadingExtraCharacters() {
        assertThat(detector.detect("prefix-550e8400-e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsTrailingExtraCharacters() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-446655440000-suffix")).isEmpty();
    }

    @Test
    void resultTypeIsUuid() {
        assertThat(detector.detect(UUID)).map(PiiDetection::type).contains(PiiType.UUID);
    }

    @Test
    void resultDoesNotContainRawValue() {
        assertThat(detector.detect(UUID).orElseThrow().toString()).doesNotContain(UUID);
    }

    @Test
    void doesNotMutateOrNormalizeInput() {
        String input = " 550e8400-e29b-41d4-a716-446655440000 ";

        assertThat(detector.detect(input)).isEmpty();
        assertThat(input).isEqualTo(" 550e8400-e29b-41d4-a716-446655440000 ");
    }

    @Test
    void registryRecognizesUuidValue() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(
                new EmailDetector(),
                new PhoneDetector(),
                new CreditCardDetector(),
                new IpAddressDetector(),
                new UuidDetector()));

        assertThat(registry.detect("550e8400-e29b-41d4-a716-446655440000"))
                .containsExactly(new PiiDetection(PiiType.UUID));
    }
}

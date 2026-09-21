package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link JwtDetector} (no Spring context, no I/O, no network).
 *
 * <p>All tokens are synthetic fixtures, never real credentials.
 */
class JwtDetectorTest {

    private static final String VALID_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    private final PiiDetector detector = new JwtDetector();

    @Test
    void detectsValidSyntheticJwt() {
        assertThat(detector.detect(VALID_JWT))
                .contains(new PiiDetection(PiiType.JWT));
    }

    @Test
    void rejectsTwoSegments() {
        assertThat(detector.detect("abcdefghij.klmnopqrst")).isEmpty();
    }

    @Test
    void rejectsFourSegments() {
        assertThat(detector.detect("abcdefghij.klmnopqrst.uvwxyz0123.extra12345")).isEmpty();
    }

    @Test
    void rejectsEmptySegment() {
        assertThat(detector.detect("abcdefghij..SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")).isEmpty();
    }

    @Test
    void rejectsInvalidBase64UrlCharacters() {
        assertThat(detector.detect("eyJhbGciOi!IUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
                .isEmpty();
    }

    @Test
    void rejectsWhitespace() {
        assertThat(detector.detect("eyJhbGciOiJIUzI1NiJ9 eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
                .isEmpty();
    }

    @Test
    void rejectsTooShortSegments() {
        assertThat(detector.detect("a.b.c")).isEmpty();
    }

    @Test
    void rejectsEmbeddedJwtInText() {
        assertThat(detector.detect("token " + VALID_JWT + " here")).isEmpty();
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
        assertThat(detector.detect("  " + VALID_JWT + " ")).isEmpty();
    }

    @Test
    void resultTypeIsJwt() {
        assertThat(detector.detect(VALID_JWT))
                .map(PiiDetection::type)
                .contains(PiiType.JWT);
    }

    @Test
    void resultDoesNotContainRawToken() {
        assertThat(detector.detect(VALID_JWT).orElseThrow().toString())
                .doesNotContain(VALID_JWT);
    }
}

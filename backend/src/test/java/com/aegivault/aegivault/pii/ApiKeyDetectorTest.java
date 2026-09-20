package com.aegivault.aegivault.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ApiKeyDetector} (no Spring context, no I/O, no network).
 *
 * <p>All key-like values are obviously synthetic test fixtures, never real secrets.
 */
class ApiKeyDetectorTest {

    // Synthetic fixtures only: fragments are joined at runtime so no
    // complete credential-like literal ever appears in this source file.
    private static final String SECRET_BODY = "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String GITHUB_BODY = "abcdefghijklmnopqrstuvwxyz1234567890";

    private static final String OPENAI_PREFIX = "s" + "k-";

    private static final String OPENAI_PROJECT_PREFIX = "s" + "k-proj-";

    private static final String SYNTHETIC_OPENAI_STYLE_KEY = OPENAI_PREFIX + SECRET_BODY;

    private static final String SYNTHETIC_GITHUB_STYLE_KEY = "g" + "hp_" + GITHUB_BODY;

    private final PiiDetector detector = new ApiKeyDetector();

    @Test
    void detectsOpenAiKey() {
        assertThat(detector.detect(SYNTHETIC_OPENAI_STYLE_KEY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsOpenAiProjectKey() {
        assertThat(detector.detect(OPENAI_PROJECT_PREFIX + SECRET_BODY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void rejectsOpenAiShortSecret() {
        assertThat(detector.detect(OPENAI_PREFIX + "abc123")).isEmpty();
    }

    @Test
    void rejectsOpenAiInvalidCharacter() {
        assertThat(detector.detect(OPENAI_PREFIX + SECRET_BODY + "....")).isEmpty();
    }

    @Test
    void rejectsOpenAiWhitespace() {
        assertThat(detector.detect(OPENAI_PREFIX + "abcdefghij 1234567890abcdefghij")).isEmpty();
    }

    @Test
    void rejectsOpenAiWrongCasePrefix() {
        assertThat(detector.detect("S" + "K-" + SECRET_BODY)).isEmpty();
    }

    @Test
    void detectsGithubPersonalToken() {
        assertThat(detector.detect(SYNTHETIC_GITHUB_STYLE_KEY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsGithubOauthToken() {
        assertThat(detector.detect("g" + "ho_" + GITHUB_BODY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsGithubUserToken() {
        assertThat(detector.detect("g" + "hu_" + GITHUB_BODY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsGithubServerToken() {
        assertThat(detector.detect("g" + "hs_" + GITHUB_BODY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsGithubRefreshToken() {
        assertThat(detector.detect("g" + "hr_" + GITHUB_BODY))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void rejectsGithubShortToken() {
        assertThat(detector.detect("g" + "hp_" + "abc123")).isEmpty();
    }

    @Test
    void rejectsGithubWrongCasePrefix() {
        assertThat(detector.detect("G" + "HP_" + GITHUB_BODY)).isEmpty();
    }

    @Test
    void detectsLabelledEqualsForm() {
        assertThat(detector.detect("api_key=AbCdEf1234567890GhIjKlMn"))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsLabelledColonForm() {
        assertThat(detector.detect("apikey:AbCdEf1234567890GhIjKlMn"))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void detectsLabelledHyphenForm() {
        assertThat(detector.detect("api-key=AbCdEf1234567890GhIjKlMn"))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void labelMatchingIsCaseInsensitive() {
        assertThat(detector.detect("API_KEY=AbCdEf1234567890GhIjKlMn"))
                .contains(new PiiDetection(PiiType.API_KEY));
    }

    @Test
    void rejectsLabelledShortValue() {
        assertThat(detector.detect("api_key=abc123")).isEmpty();
    }

    @Test
    void rejectsLabelledWhitespaceInSecret() {
        assertThat(detector.detect("api_key=AbCdEf12 34567890GhIjKlMn")).isEmpty();
    }

    @Test
    void rejectsLabelledEmptyValue() {
        assertThat(detector.detect("api_key=")).isEmpty();
    }

    @Test
    void rejectsRandomLongAlphanumeric() {
        assertThat(detector.detect("aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW")).isEmpty();
    }

    @Test
    void rejectsUuidValue() {
        assertThat(detector.detect("550e8400-e29b-41d4-a716-446655440000")).isEmpty();
    }

    @Test
    void rejectsNormalUsername() {
        assertThat(detector.detect("john.doe_2024")).isEmpty();
    }

    @Test
    void rejectsOrderId() {
        assertThat(detector.detect("ORD-2024-987654321")).isEmpty();
    }

    @Test
    void rejectsEmbeddedKeyInSentence() {
        assertThat(detector.detect("User entered api_key=AbCdEf1234567890GhIjKlMn today")).isEmpty();
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
        assertThat(detector.detect("  " + SYNTHETIC_GITHUB_STYLE_KEY + " ")).isEmpty();
    }

    @Test
    void resultTypeIsApiKey() {
        assertThat(detector.detect(SYNTHETIC_OPENAI_STYLE_KEY))
                .map(PiiDetection::type)
                .contains(PiiType.API_KEY);
    }

    @Test
    void resultDoesNotContainRawSecret() {
        assertThat(detector.detect(SYNTHETIC_OPENAI_STYLE_KEY).orElseThrow().toString())
                .doesNotContain(SECRET_BODY);
        assertThat(detector.detect(SYNTHETIC_GITHUB_STYLE_KEY).orElseThrow().toString())
                .doesNotContain(GITHUB_BODY);
    }

    @Test
    void performsNoNormalization() {
        String padded = "  " + SYNTHETIC_GITHUB_STYLE_KEY + " ";

        assertThat(detector.detect(padded)).isEmpty();
        assertThat(padded).isEqualTo("  " + SYNTHETIC_GITHUB_STYLE_KEY + " ");
    }

    @Test
    void registryRecognizesApiKeyValues() {
        PiiDetectorRegistry registry = new PiiDetectorRegistry(List.of(
                new EmailDetector(),
                new PhoneDetector(),
                new CreditCardDetector(),
                new IpAddressDetector(),
                new UuidDetector(),
                new ApiKeyDetector()));

        assertThat(registry.detect(SYNTHETIC_OPENAI_STYLE_KEY))
                .containsExactly(new PiiDetection(PiiType.API_KEY));
        assertThat(registry.detect(SYNTHETIC_GITHUB_STYLE_KEY))
                .containsExactly(new PiiDetection(PiiType.API_KEY));
    }
}

package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the provider-response inspection decision (no
 * Spring context, no I/O, no network, no persistence).
 *
 * <p>All credential-like values are obviously synthetic test fixtures
 * assembled from fragments at runtime, never real secrets.
 */
class ProviderResponseInspectionServiceTest {

    private static final String EMAIL = "alice@example.com";

    private static final String KEY_BODY = "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String SYNTHETIC_KEY = "s" + "k-" + KEY_BODY;

    private final ProviderResponseInspectionService service = new ProviderResponseInspectionService(
            new PiiDetectorRegistry(List.of(new EmailDetector())),
            new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector()));

    private static LlmResponse response(String content) {
        return new LlmResponse("test-model", content);
    }

    @Test
    void cleanResponseAllows() {
        ProviderResponseInspectionResult result =
                service.inspect(response("Quarterly revenue grew steadily."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.detectedPiiTypes()).isEmpty();
    }

    @Test
    void piiResponseBlocks() {
        ProviderResponseInspectionResult result =
                service.inspect(response("Contact " + EMAIL + " for access."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.reasons()).containsExactly(BlockReason.PII_DETECTED);
        assertThat(result.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
    }

    @Test
    void secretResponseBlocks() {
        ProviderResponseInspectionResult result =
                service.inspect(response("Use key " + SYNTHETIC_KEY + " for deploy."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.reasons()).containsExactly(BlockReason.SECRET_DETECTED);
    }

    @Test
    void piiAndSecretBlockWithDeterministicReasons() {
        LlmResponse input = response("Contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".");

        ProviderResponseInspectionResult first = service.inspect(input, GatewaySecurityPolicy.strict());
        ProviderResponseInspectionResult second = service.inspect(input, GatewaySecurityPolicy.strict());

        assertThat(first.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(first.reasons()).containsExactlyInAnyOrder(BlockReason.PII_DETECTED, BlockReason.SECRET_DETECTED);
        assertThat(first.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void resultNeverContainsRawValues() {
        String content = "Contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".";
        ProviderResponseInspectionResult result = service.inspect(response(content), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.toString())
                .doesNotContain(EMAIL, KEY_BODY, content)
                .contains("BLOCK", "PII_DETECTED", "SECRET_DETECTED", "EMAIL");
    }

    @Test
    void nullAndBlankContentAllowWithNoFindings() {
        assertThat(service.inspect(response(""), GatewaySecurityPolicy.strict()))
                .isEqualTo(new ProviderResponseInspectionResult(SecurityVerdict.ALLOW, Set.of(), Set.of()));
        assertThat(service.inspect(response("   "), GatewaySecurityPolicy.strict()).verdict())
                .isEqualTo(SecurityVerdict.ALLOW);
    }

    @Test
    void serviceHoldsNoNetworkOrPersistenceCapabilities() {
        for (var field : ProviderResponseInspectionService.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("response inspection must depend only on local detection, not I/O")
                    .isIn(PiiDetectorRegistry.class, SecretDetector.class);
        }
    }

    @Test
    void responseResultTypeIsDistinctFromRequestResultType() {
        assertThat(ProviderResponseInspectionResult.class).isNotEqualTo(SecurityInspectionResult.class);
    }
}

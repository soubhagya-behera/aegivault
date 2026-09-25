package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the gateway inspection foundation (no Spring context,
 * no I/O, no network, no persistence).
 *
 * <p>All credential-like values are obviously synthetic test fixtures
 * assembled from fragments at runtime, never real secrets.
 */
class SecurityInspectionServiceTest {

    // Synthetic fixtures only: fragments are joined at runtime so no
    // complete credential-like literal ever appears in this source file.
    private static final String EMAIL = "alice@example.com";

    private static final String KEY_BODY = "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String SYNTHETIC_KEY = "s" + "k-" + KEY_BODY;

    private static final String SYNTHETIC_JWT =
            "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwfQ" + "." + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c";

    private final SecurityInspectionService service = new SecurityInspectionService(
            new PiiDetectorRegistry(List.of(new EmailDetector())),
            new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector()));

    private static GatewayInspectionRequest request(String content) {
        return new GatewayInspectionRequest(UUID.randomUUID(), "analyst", "test-model", content);
    }

    @Test
    void cleanRequestAllows() {
        SecurityInspectionResult result = service.inspect(
                request("Summarize quarterly revenue trends for the board."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.detectedPiiTypes()).isEmpty();
    }

    @Test
    void piiBlocksWhenEnabled() {
        SecurityInspectionResult result =
                service.inspect(request("Contact " + EMAIL + " for access."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.reasons()).containsExactly(BlockReason.PII_DETECTED);
        assertThat(result.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
    }

    @Test
    void piiAllowsWhenDisabledButRemainsReported() {
        SecurityInspectionResult result = service.inspect(
                request("Contact " + EMAIL + " for access."),
                new GatewaySecurityPolicy(false, true));

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
    }

    @Test
    void secretBlocksWhenEnabled() {
        SecurityInspectionResult result =
                service.inspect(request("Use key " + SYNTHETIC_KEY + " for deploy."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.reasons()).containsExactly(BlockReason.SECRET_DETECTED);
    }

    @Test
    void jwtShapedSecretBlocksWhenEnabled() {
        SecurityInspectionResult result =
                service.inspect(request("Bearer " + SYNTHETIC_JWT), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.reasons()).containsExactly(BlockReason.SECRET_DETECTED);
    }

    @Test
    void secretAllowsWhenDisabled() {
        SecurityInspectionResult result = service.inspect(
                request("Use key " + SYNTHETIC_KEY + " for deploy."),
                new GatewaySecurityPolicy(true, false));

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void piiAndSecretBlockWithDeterministicReasons() {
        GatewayInspectionRequest input = request("Contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".");

        SecurityInspectionResult first = service.inspect(input, GatewaySecurityPolicy.strict());
        SecurityInspectionResult second = service.inspect(input, GatewaySecurityPolicy.strict());

        assertThat(first.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(first.reasons()).containsExactlyInAnyOrder(BlockReason.PII_DETECTED, BlockReason.SECRET_DETECTED);
        assertThat(first.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void resultNeverContainsRawValues() {
        String content = "Contact " + EMAIL + " with key " + SYNTHETIC_KEY + " and token " + SYNTHETIC_JWT + ".";
        SecurityInspectionResult result = service.inspect(request(content), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(result.toString())
                .doesNotContain(EMAIL, KEY_BODY, SYNTHETIC_JWT, content)
                .contains("BLOCK", "PII_DETECTED", "SECRET_DETECTED", "EMAIL");
    }

    @Test
    void piiLegFlowsThroughTheSharedRegistry() {
        SecurityInspectionService withoutDetectors = new SecurityInspectionService(
                new PiiDetectorRegistry(List.of()), serviceSecret());

        SecurityInspectionResult result = withoutDetectors.inspect(
                request("Contact " + EMAIL + " for access."), GatewaySecurityPolicy.strict());

        assertThat(result.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(result.detectedPiiTypes()).isEmpty();
    }

    @Test
    void nullAndBlankContentAllowWithNoFindings() {
        assertThat(service.inspect(request(null), GatewaySecurityPolicy.strict()))
                .isEqualTo(new SecurityInspectionResult(SecurityVerdict.ALLOW, Set.of(), Set.of()));
        assertThat(service.inspect(request("   "), GatewaySecurityPolicy.strict()).verdict())
                .isEqualTo(SecurityVerdict.ALLOW);
    }

    @Test
    void repeatedIdenticalInputProducesIdenticalDecision() {
        GatewayInspectionRequest input = request("Contact " + EMAIL + " for access.");

        assertThat(service.inspect(input, GatewaySecurityPolicy.strict()))
                .isEqualTo(service.inspect(input, GatewaySecurityPolicy.strict()));
        assertThat(service.inspect(input, GatewaySecurityPolicy.monitoring()))
                .isEqualTo(service.inspect(input, GatewaySecurityPolicy.monitoring()));
    }

    @Test
    void serviceHoldsNoNetworkOrPersistenceCapabilities() {
        for (var field : SecurityInspectionService.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("inspection must depend only on local detection, not I/O")
                    .isIn(PiiDetectorRegistry.class, SecretDetector.class);
        }
    }

    private static SecretDetector serviceSecret() {
        return new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector());
    }
}

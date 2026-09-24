package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for {@link GatewayCompletionService} (no Spring
 * context, no I/O, no network): request inspection gates the provider,
 * BLOCK never reaches it, clean provider responses return unchanged,
 * sensitive provider responses are blocked without reaching the client,
 * and provider failures stay generic without running response
 * inspection.
 */
class GatewayCompletionServiceTest {

    private static final String EMAIL = "alice@example.com";

    private static final String KEY_BODY = "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String SYNTHETIC_KEY = "s" + "k-" + KEY_BODY;

    private final SecurityInspectionService inspections = new SecurityInspectionService(
            new PiiDetectorRegistry(List.of(new EmailDetector())),
            new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector()));

    private final ProviderResponseInspectionService responseInspections =
            new ProviderResponseInspectionService(
                    new PiiDetectorRegistry(List.of(new EmailDetector())),
                    new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector()));

    private final LlmProvider providers = mock(LlmProvider.class);

    private final GatewayAuditService audit = mock(GatewayAuditService.class);

    private final GatewayCompletionService service = new GatewayCompletionService(
            inspections, responseInspections, providers, audit);

    private static GatewayInspectionRequest inspection(String content) {
        return new GatewayInspectionRequest(UUID.randomUUID(), "analyst", "test-model", content);
    }

    @Test
    void allowForwardsModelAndContentExactlyOnce() {
        when(providers.complete(any())).thenReturn(new LlmResponse("test-model", "completion text"));
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        GatewayCompleteResponse response = service.complete(request);

        ArgumentCaptor<LlmRequest> forwarded = ArgumentCaptor.forClass(LlmRequest.class);
        verify(providers, times(1)).complete(forwarded.capture());
        assertThat(forwarded.getValue()).isEqualTo(new LlmRequest("test-model", "Summarize quarterly revenue trends."));
        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.provider()).isEqualTo(new LlmResponse("test-model", "completion text"));
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void cleanRequestWithCleanProviderResponseReturnsProviderContentUnchanged() {
        LlmResponse completion = new LlmResponse("test-model", "Quarterly revenue grew steadily.");
        when(providers.complete(any())).thenReturn(completion);

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.reasons()).isEmpty();
        assertThat(response.detectedPiiTypes()).isEmpty();
        assertThat(response.provider()).isEqualTo(completion);
    }

    @Test
    void blockNeverReachesTheProvider() {
        GatewayInspectionRequest request = inspection("Contact " + EMAIL + " for access.");

        GatewayCompleteResponse response = service.complete(request);

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        verify(providers, never()).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerResponseWithPiiIsBlockedWithoutReturningContent() {
        when(providers.complete(any())).thenReturn(new LlmResponse("test-model", "Contact " + EMAIL + " for access."));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        assertThat(response.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        assertThat(response.toString()).doesNotContain(EMAIL);
        verify(providers, times(1)).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerResponseWithSecretIsBlockedWithoutReturningContent() {
        when(providers.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Use key " + SYNTHETIC_KEY + " for deploy."));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.SECRET_DETECTED);
        assertThat(response.toString()).doesNotContain(SYNTHETIC_KEY, KEY_BODY);
        verify(providers, times(1)).complete(any());
    }

    @Test
    void providerResponseWithBothBlocksWithDeterministicReasons() {
        when(providers.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Contact " + EMAIL + " with key " + SYNTHETIC_KEY + "."));

        GatewayCompleteResponse first = service.complete(inspection("Summarize quarterly revenue trends."));
        GatewayCompleteResponse second = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(first.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(first.reasons()).containsExactly(BlockReason.PII_DETECTED, BlockReason.SECRET_DETECTED);
        assertThat(first.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        assertThat(first.toString()).doesNotContain(EMAIL, SYNTHETIC_KEY, KEY_BODY);
        assertThat(second.reasons()).isEqualTo(first.reasons());
        assertThat(second.detectedPiiTypes()).isEqualTo(first.detectedPiiTypes());
    }

    @Test
    void providerFailureBecomesAGenericException() {
        when(providers.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        assertThatThrownBy(() -> service.complete(request))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.")
                .hasMessageNotContaining("simulated-provider-boom-9z");
        // The inspection audit already recorded stays single — never duplicated.
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerFailureDoesNotExecuteResponseInspection() {
        ProviderResponseInspectionService responseSpy = mock(ProviderResponseInspectionService.class);
        GatewayCompletionService failingService =
                new GatewayCompletionService(inspections, responseSpy, providers, audit);
        when(providers.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));

        assertThatThrownBy(() -> failingService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class);
        verify(responseSpy, never()).inspect(any(), any());
        verify(audit, times(1)).record(any(), any());
    }
}

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for {@link GatewayCompletionService} (no Spring
 * context, no I/O, no network): inspection gates the provider, BLOCK
 * never reaches it, and provider failures stay generic.
 */
class GatewayCompletionServiceTest {

    private static final String EMAIL = "alice@example.com";

    private final SecurityInspectionService inspections = new SecurityInspectionService(
            new PiiDetectorRegistry(List.of(new EmailDetector())),
            new DefaultSecretDetector(new ApiKeyDetector(), new JwtDetector()));

    private final LlmProvider providers = mock(LlmProvider.class);

    private final GatewayAuditService audit = mock(GatewayAuditService.class);

    private final GatewayCompletionService service =
            new GatewayCompletionService(inspections, providers, audit);

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
}

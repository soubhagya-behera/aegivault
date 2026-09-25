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
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.provider.MockLlmProvider;
import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for {@link GatewayCompletionService} (no Spring
 * context, no I/O, no network): request inspection gates provider
 * selection, BLOCK never reaches the selector or the selected
 * provider, clean provider responses return unchanged, sensitive
 * provider responses are blocked without reaching the client, and
 * provider and selection failures stay generic without running
 * response inspection.
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

    private final LlmProviderSelector selector = mock(LlmProviderSelector.class);

    private final LlmProvider selected = mock(LlmProvider.class);

    private final GatewayAuditService audit = mock(GatewayAuditService.class);

    private final GatewayRateLimiter rateLimiter = actor -> true;

    private final GatewayCompletionService service = new GatewayCompletionService(
            rateLimiter, inspections, responseInspections, selector, audit);

    private static GatewayInspectionRequest inspection(String content) {
        return new GatewayInspectionRequest(UUID.randomUUID(), "analyst", "test-model", content);
    }

    @BeforeEach
    void selectTheMockedProvider() {
        when(selector.select(any())).thenReturn(selected);
    }

    @Test
    void allowForwardsModelAndContentExactlyOnce() {
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "completion text"));
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        GatewayCompleteResponse response = service.complete(request);

        verify(selector, times(1)).select("test-model");
        ArgumentCaptor<LlmRequest> forwarded = ArgumentCaptor.forClass(LlmRequest.class);
        verify(selected, times(1)).complete(forwarded.capture());
        assertThat(forwarded.getValue()).isEqualTo(new LlmRequest("test-model", "Summarize quarterly revenue trends."));
        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.provider()).isEqualTo(new LlmResponse("test-model", "completion text"));
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void cleanRequestWithCleanProviderResponseReturnsProviderContentUnchanged() {
        LlmResponse completion = new LlmResponse("test-model", "Quarterly revenue grew steadily.");
        when(selected.complete(any())).thenReturn(completion);

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.reasons()).isEmpty();
        assertThat(response.detectedPiiTypes()).isEmpty();
        assertThat(response.provider()).isEqualTo(completion);
        verify(selector, times(1)).select("test-model");
        verify(selected, times(1)).complete(any());
    }

    @Test
    void blockNeverReachesTheProvider() {
        GatewayInspectionRequest request = inspection("Contact " + EMAIL + " for access.");

        GatewayCompleteResponse response = service.complete(request);

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerResponseWithPiiIsBlockedWithoutReturningContent() {
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Contact " + EMAIL + " for access."));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        assertThat(response.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        assertThat(response.toString()).doesNotContain(EMAIL);
        verify(selected, times(1)).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerResponseWithSecretIsBlockedWithoutReturningContent() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Use key " + SYNTHETIC_KEY + " for deploy."));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.SECRET_DETECTED);
        assertThat(response.toString()).doesNotContain(SYNTHETIC_KEY, KEY_BODY);
        verify(selected, times(1)).complete(any());
    }

    @Test
    void providerResponseWithBothBlocksWithDeterministicReasons() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Contact " + EMAIL + " with key " + SYNTHETIC_KEY + "."));

        GatewayCompleteResponse first = service.complete(inspection("Summarize quarterly revenue trends."));
        GatewayCompleteResponse second = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(first.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(first.reasons()).containsExactlyInAnyOrder(BlockReason.PII_DETECTED, BlockReason.SECRET_DETECTED);
        assertThat(first.detectedPiiTypes()).containsExactly(PiiType.EMAIL);
        assertThat(first.toString()).doesNotContain(EMAIL, SYNTHETIC_KEY, KEY_BODY);
        assertThat(second.reasons()).isEqualTo(first.reasons());
        assertThat(second.detectedPiiTypes()).isEqualTo(first.detectedPiiTypes());
    }

    @Test
    void providerFailureBecomesAGenericException() {
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));
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
                new GatewayCompletionService(rateLimiter, inspections, responseSpy, selector, audit);
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));

        assertThatThrownBy(() -> failingService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class);
        verify(responseSpy, never()).inspect(any(), any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerSelectionFailureBecomesAGenericException() {
        when(selector.select(any())).thenThrow(new RuntimeException("simulated-routing-boom-7q"));
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        assertThatThrownBy(() -> service.complete(request))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.")
                .hasMessageNotContaining("simulated-routing-boom-7q");
        verify(selected, never()).complete(any());
        // The inspection audit already recorded stays single — never duplicated.
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerSelectionFailureDoesNotExecuteResponseInspection() {
        ProviderResponseInspectionService responseSpy = mock(ProviderResponseInspectionService.class);
        GatewayCompletionService failingService =
                new GatewayCompletionService(rateLimiter, inspections, responseSpy, selector, audit);
        when(selector.select(any())).thenThrow(new RuntimeException("simulated-routing-boom-7q"));

        assertThatThrownBy(() -> failingService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.")
                .hasMessageNotContaining("simulated-routing-boom-7q");
        verify(responseSpy, never()).inspect(any(), any());
        verify(selected, never()).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerResponseExactlyAtLimitIsAcceptedAndInspected() {
        String atLimit = "a".repeat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", atLimit));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.provider()).isEqualTo(new LlmResponse("test-model", atLimit));
        assertThat(response.reasons()).isEmpty();
        verify(selected, times(1)).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerResponseOneOverLimitIsRejectedSafely() {
        String oversized = "a".repeat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH + 1);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", oversized));
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        assertThatThrownBy(() -> service.complete(request))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.")
                .hasMessageNotContaining("a".repeat(100));
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void oversizedProviderResponseNeverReachesResponseInspection() {
        ProviderResponseInspectionService responseSpy = mock(ProviderResponseInspectionService.class);
        GatewayCompletionService oversizedService =
                new GatewayCompletionService(rateLimiter, inspections, responseSpy, selector, audit);
        String oversized = "a".repeat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH + 1);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", oversized));

        assertThatThrownBy(() -> oversizedService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.");
        verify(responseSpy, never()).inspect(any(), any());
        verify(selected, times(1)).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void oversizedProviderResponseWithPiiStillFailsInsteadOfBlocking() {
        ProviderResponseInspectionService responseSpy = mock(ProviderResponseInspectionService.class);
        GatewayCompletionService oversizedService =
                new GatewayCompletionService(rateLimiter, inspections, responseSpy, selector, audit);
        String oversizedPii = EMAIL + " " + "a".repeat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", oversizedPii));

        assertThatThrownBy(() -> oversizedService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.");
        verify(responseSpy, never()).inspect(any(), any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerLimitReusesGatewayInputBound() {
        assertThat(GatewayCompletionService.MAX_PROVIDER_RESPONSE_LENGTH)
                .isEqualTo(GatewayInspectRequest.MAX_CONTENT_LENGTH)
                .isEqualTo(65_536);
    }

    @Test
    void rateLimitedActorFailsBeforeInspectionAuditOrProvider() {
        SecurityInspectionService inspectionsSpy = mock(SecurityInspectionService.class);
        GatewayCompletionService limitedService = new GatewayCompletionService(
                actor -> false, inspectionsSpy, responseInspections, selector, audit);

        assertThatThrownBy(() -> limitedService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayRateLimitExceededException.class)
                .hasMessage(GatewayRateLimitExceededException.MESSAGE);
        verify(inspectionsSpy, never()).inspect(any(), any());
        verify(audit, never()).record(any(), any());
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
    }

    @Test
    void rateLimiterReceivesTheJwtDerivedActor() {
        List<String> seen = new java.util.ArrayList<>();
        GatewayRateLimiter recording = actor -> {
            seen.add(actor);
            return true;
        };
        GatewayCompletionService recordingService = new GatewayCompletionService(
                recording, inspections, responseInspections, selector, audit);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "completion text"));

        recordingService.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(seen).containsExactly("analyst");
    }

    @Test
    void serviceDependsOnTheSelectorRatherThanAConcreteProvider() {
        var fieldTypes = Arrays.stream(GatewayCompletionService.class.getDeclaredFields())
                .map(field -> field.getType())
                .collect(Collectors.toSet());

        assertThat(fieldTypes.contains(LlmProviderSelector.class)).isTrue();
        assertThat(fieldTypes.contains(MockLlmProvider.class)).isFalse();
    }
}

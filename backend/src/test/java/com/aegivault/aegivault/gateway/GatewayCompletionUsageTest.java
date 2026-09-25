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
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.gateway.provider.LlmUsage;
import com.aegivault.aegivault.gateway.usage.GatewayUsageException;
import com.aegivault.aegivault.gateway.usage.GatewayUsageOutcome;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecorder;
import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Focused tests for provider usage metadata in the gateway completion
 * flow: ALLOW exposes the provider-reported usage, BLOCK exposes neither
 * provider content nor usage, and request BLOCK plus provider failure
 * behavior are unchanged.
 */
class GatewayCompletionUsageTest {

    private static final String EMAIL = "alice@example.com";

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

    private final GatewayUsageRecorder usage = mock(GatewayUsageRecorder.class);

    private final GatewayCompletionService service = new GatewayCompletionService(
            actor -> true, inspections, responseInspections, selector, audit, usage);

    private static GatewayInspectionRequest inspection(String content) {
        return new GatewayInspectionRequest(UUID.randomUUID(), "analyst", "test-model", content);
    }

    @BeforeEach
    void selectTheMockedProvider() {
        when(selector.select(any())).thenReturn(selected);
    }

    @Test
    void allowResponseCarriesProviderUsage() {
        LlmUsage counts = new LlmUsage(12L, 34L, 46L);
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Quarterly revenue grew steadily.", counts));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.provider().usage()).isEqualTo(counts);
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void allowResponseWithUnknownUsageKeepsUsageUnknown() {
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", "Quarterly revenue grew steadily."));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.provider().usage()).isEqualTo(LlmUsage.unknown());
        assertThat(response.provider().usage().isUnknown()).isTrue();
    }

    @Test
    void usageDoesNotAffectResponseInspection() {
        LlmUsage counts = new LlmUsage(10L, 20L, 30L);
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Quarterly revenue grew steadily.", counts));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.ALLOW);
        assertThat(response.reasons()).isEmpty();
        assertThat(response.detectedPiiTypes()).isEmpty();
        assertThat(response.provider().usage()).isEqualTo(counts);
    }

    @Test
    void responseBlockExposesNeitherContentNorUsage() {
        LlmUsage counts = new LlmUsage(10L, 20L, 30L);
        String sensitive = "Contact " + EMAIL + " for access.";
        when(selected.complete(any())).thenReturn(new LlmResponse("test-model", sensitive, counts));

        GatewayCompleteResponse response = service.complete(inspection("Summarize quarterly revenue trends."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        assertThat(response.toString()).doesNotContain(EMAIL);
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void requestBlockBehaviorRemainsUnchanged() {
        GatewayCompleteResponse response = service.complete(inspection("Contact " + EMAIL + " for access."));

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        assertThat(response.provider()).isNull();
        assertThat(response.reasons()).containsExactly(BlockReason.PII_DETECTED);
        verify(selector, never()).select(any());
        verify(selected, never()).complete(any());
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void providerFailureBehaviorRemainsUnchanged() {
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));

        assertThatThrownBy(() -> service.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class)
                .hasMessage("Unable to complete gateway request.")
                .hasMessageNotContaining("simulated-provider-boom-9z");
        verify(audit, times(1)).record(any(), any());
    }

    @Test
    void requestAuditRecordingCarriesNoProviderUsage() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse(
                        "test-model", "Quarterly revenue grew steadily.", new LlmUsage(12L, 34L, 46L)));

        service.complete(inspection("Summarize quarterly revenue trends."));

        ArgumentCaptor<SecurityInspectionResult> recorded = ArgumentCaptor.forClass(SecurityInspectionResult.class);
        verify(audit, times(1)).record(any(), recorded.capture());
        assertThat(recorded.getValue().verdict()).isEqualTo(SecurityVerdict.ALLOW);
        var components = Arrays.stream(SecurityInspectionResult.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertThat(components).containsExactlyInAnyOrder("verdict", "reasons", "detectedPiiTypes");
    }

    @Test
    void deliveredResponseRecordsDeliveredWithExactUsage() {
        LlmUsage counts = new LlmUsage(12L, 34L, 46L);
        LlmResponse completion = new LlmResponse("test-model", "Quarterly revenue grew steadily.", counts);
        when(selected.complete(any())).thenReturn(completion);
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        service.complete(request);

        ArgumentCaptor<GatewayInspectionRequest> recordedInspection =
                ArgumentCaptor.forClass(GatewayInspectionRequest.class);
        ArgumentCaptor<LlmResponse> recordedResponse = ArgumentCaptor.forClass(LlmResponse.class);
        ArgumentCaptor<GatewayUsageOutcome> recordedOutcome =
                ArgumentCaptor.forClass(GatewayUsageOutcome.class);
        verify(usage, times(1))
                .record(recordedInspection.capture(), recordedResponse.capture(), recordedOutcome.capture());
        assertThat(recordedInspection.getValue().requestId()).isEqualTo(request.requestId());
        assertThat(recordedInspection.getValue().actorSubject()).isEqualTo("analyst");
        assertThat(recordedInspection.getValue().model()).isEqualTo("test-model");
        assertThat(recordedResponse.getValue()).isEqualTo(completion);
        assertThat(recordedOutcome.getValue()).isEqualTo(GatewayUsageOutcome.DELIVERED);
    }

    @Test
    void blockedResponseRecordsSecurityBlocked() {
        LlmResponse completion =
                new LlmResponse("test-model", "Contact " + EMAIL + " for access.", new LlmUsage(10L, 20L, 30L));
        when(selected.complete(any())).thenReturn(completion);
        GatewayInspectionRequest request = inspection("Summarize quarterly revenue trends.");

        GatewayCompleteResponse response = service.complete(request);

        assertThat(response.verdict()).isEqualTo(SecurityVerdict.BLOCK);
        verify(usage, times(1)).record(request, completion, GatewayUsageOutcome.SECURITY_BLOCKED);
    }

    @Test
    void requestBlockRecordsNoUsage() {
        service.complete(inspection("Contact " + EMAIL + " for access."));

        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void rateLimitedRequestRecordsNoUsage() {
        GatewayCompletionService limitedService = new GatewayCompletionService(
                actor -> false, inspections, responseInspections, selector, audit, usage);

        assertThatThrownBy(() -> limitedService.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayRateLimitExceededException.class);
        verify(usage, never()).record(any(), any(), any());
        verify(audit, never()).record(any(), any());
    }

    @Test
    void providerFailureRecordsNoUsage() {
        when(selected.complete(any())).thenThrow(new RuntimeException("simulated-provider-boom-9z"));

        assertThatThrownBy(() -> service.complete(inspection("Summarize quarterly revenue trends.")))
                .isInstanceOf(GatewayProviderException.class);
        verify(usage, never()).record(any(), any(), any());
    }

    @Test
    void usageFailurePropagatesInsteadOfTheAllowResponse() {
        when(selected.complete(any()))
                .thenReturn(new LlmResponse("test-model", "Quarterly revenue grew steadily."));
        GatewayUsageException failure = new GatewayUsageException(
                "Unable to record gateway usage.", new RuntimeException("simulated-usage-boom-4u"));
        org.mockito.Mockito.doThrow(failure).when(usage).record(any(), any(), any());

        assertThatThrownBy(() -> service.complete(inspection("Summarize quarterly revenue trends.")))
                .isSameAs(failure);
        verify(audit, times(1)).record(any(), any());
    }
}

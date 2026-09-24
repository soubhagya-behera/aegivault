package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the LLM provider abstraction and its deterministic
 * mock (no Spring context, no I/O, no network, no persistence, no audit).
 */
class MockLlmProviderTest {

    private final MockLlmProvider mock = new MockLlmProvider();

    @Test
    void calledThroughTheAbstraction() {
        LlmProvider provider = new MockLlmProvider();

        LlmResponse response = provider.complete(new LlmRequest("local-test-model", "summarize this."));

        assertThat(response).isNotNull();
        assertThat(response.model()).isEqualTo("local-test-model");
        assertThat(response.content()).isNotBlank();
    }

    @Test
    void identicalRequestsGiveIdenticalResponses() {
        LlmRequest request = new LlmRequest("local-test-model", "summarize this.");

        assertThat(new MockLlmProvider().complete(request)).isEqualTo(mock.complete(request));
        assertThat(mock.complete(request)).isEqualTo(mock.complete(request));
    }

    @Test
    void differentContentGivesDifferentResponse() {
        LlmResponse first = mock.complete(new LlmRequest("local-test-model", "summarize this."));
        LlmResponse second = mock.complete(new LlmRequest("local-test-model", "summarize that."));

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void differentModelGivesDifferentResponse() {
        LlmResponse first = mock.complete(new LlmRequest("model-a", "summarize this."));
        LlmResponse second = mock.complete(new LlmRequest("model-b", "summarize this."));

        assertThat(second).isNotEqualTo(first);
        assertThat(second.model()).isEqualTo("model-b");
    }

    @Test
    void mockIsObviouslyNotAnAiAnswer() {
        LlmResponse response = mock.complete(new LlmRequest("local-test-model", "summarize this."));

        assertThat(response.content()).contains("MOCK", "not an AI answer");
    }

    @Test
    void responseContainsNoRequestContent() {
        String content = "distinctive-prompt-body-4f2a9c";

        LlmResponse response = mock.complete(new LlmRequest("local-test-model", content));

        assertThat(response.content()).doesNotContain(content);
    }

    @Test
    void noConfigurationOrCredentialsRequired() {
        assertThat(new MockLlmProvider()).isNotNull();
        assertThat(MockLlmProvider.class.getDeclaredFields()).isEmpty();
        assertThat(Arrays.stream(MockLlmProvider.class.getDeclaredConstructors())
                        .anyMatch(constructor -> constructor.getParameterCount() == 0))
                .isTrue();
    }

    @Test
    void holdsNoGatewayAuditOrDetectionCapabilities() {
        for (var field : MockLlmProvider.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("mock must stay stateless, with no gateway/audit/detection wiring")
                    .doesNotContain("audit", "pii", "gateway", "Controller", "Registry", "Detector");
        }
        assertThat(MockLlmProvider.class.getPackageName())
                .doesNotContain("audit", "pii");
    }

    @Test
    void requestAndResponseCarryNoGatewayInternals() {
        Set<String> requestComponents = Arrays.stream(LlmRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        Set<String> responseComponents = Arrays.stream(LlmResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertThat(requestComponents).containsExactlyInAnyOrder("model", "content");
        assertThat(responseComponents).containsExactlyInAnyOrder("model", "content");
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> mock.complete(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request must not be null");
    }

    @Test
    void blankModelIsRejected() {
        assertThatThrownBy(() -> new LlmRequest(null, "summarize this."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmRequest("   ", "summarize this."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmResponse("   ", "text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmResponse("local-test-model", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullContentIsNormalizedToEmpty() {
        LlmRequest request = new LlmRequest("local-test-model", null);

        assertThat(request.content()).isEmpty();
        assertThat(mock.complete(request)).isEqualTo(mock.complete(new LlmRequest("local-test-model", "")));
    }
}

package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for provider usage metadata: the {@link LlmUsage} value type
 * itself (small, immutable, provider-reported counts with unknown preserved
 * as unknown) and the {@link MockLlmProvider} contract (always unknown
 * usage — never a character-count disguised as tokens).
 */
class LlmUsageTest {

    private final MockLlmProvider mock = new MockLlmProvider();

    @Test
    void usageCarriesExactlyThreeOptionalCounts() {
        var components = Arrays.stream(LlmUsage.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertThat(components).containsExactlyInAnyOrder("promptTokens", "completionTokens", "totalTokens");
    }

    @Test
    void unknownHasNoCounts() {
        LlmUsage usage = LlmUsage.unknown();

        assertThat(usage.promptTokens()).isNull();
        assertThat(usage.completionTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
        assertThat(usage.isUnknown()).isTrue();
    }

    @Test
    void knownCountsArePreserved() {
        LlmUsage usage = new LlmUsage(12L, 34L, 46L);

        assertThat(usage.promptTokens()).isEqualTo(12L);
        assertThat(usage.completionTokens()).isEqualTo(34L);
        assertThat(usage.totalTokens()).isEqualTo(46L);
        assertThat(usage.isUnknown()).isFalse();
    }

    @Test
    void partiallyKnownUsageIsNotUnknown() {
        assertThat(new LlmUsage(12L, null, null).isUnknown()).isFalse();
        assertThat(new LlmUsage(null, 34L, null).isUnknown()).isFalse();
        assertThat(new LlmUsage(null, null, 46L).isUnknown()).isFalse();
    }

    @Test
    void negativeCountsAreRejected() {
        assertThatThrownBy(() -> new LlmUsage(-1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmUsage(null, -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmUsage(null, null, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseNormalizesNullUsageToUnknown() {
        LlmResponse response = new LlmResponse("test-model", "text", null);

        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().isUnknown()).isTrue();
    }

    @Test
    void twoArgumentResponseCarriesUnknownUsage() {
        LlmResponse response = new LlmResponse("test-model", "text");

        assertThat(response.usage()).isEqualTo(LlmUsage.unknown());
        assertThat(response.usage().isUnknown()).isTrue();
    }

    @Test
    void mockUsageIsUnknown() {
        LlmResponse response = mock.complete(new LlmRequest("local-test-model", "summarize this."));

        assertThat(response.usage()).isEqualTo(LlmUsage.unknown());
        assertThat(response.usage().isUnknown()).isTrue();
    }

    @Test
    void mockNeverDerivesTokenCountsFromContentLength() {
        LlmResponse shortResponse = mock.complete(new LlmRequest("local-test-model", "x"));
        LlmResponse longResponse =
                mock.complete(new LlmRequest("local-test-model", "x".repeat(10_000)));

        assertThat(shortResponse.usage().isUnknown()).isTrue();
        assertThat(longResponse.usage().isUnknown()).isTrue();
        assertThat(shortResponse.usage()).isEqualTo(longResponse.usage());
    }
}

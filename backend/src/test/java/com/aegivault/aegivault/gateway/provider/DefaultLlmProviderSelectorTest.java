package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link DefaultLlmProviderSelector} (no Spring
 * context, no I/O, no network): every valid model resolves to the
 * single configured provider, with no URLs, credentials, or network
 * configuration involved.
 */
class DefaultLlmProviderSelectorTest {

    private final MockLlmProvider mock = new MockLlmProvider();

    private final DefaultLlmProviderSelector selector = new DefaultLlmProviderSelector(mock);

    @Test
    void validModelResolvesToMockProvider() {
        assertThat(selector.select("local-test-model")).isSameAs(mock);
    }

    @Test
    void everyValidModelResolvesToTheSameMockProvider() {
        assertThat(selector.select("model-a")).isSameAs(mock);
        assertThat(selector.select("model-b")).isSameAs(mock);
        assertThat(selector.select("model-a")).isSameAs(selector.select("model-b"));
    }

    @Test
    void blankModelIsRejectedWithoutReturningAProvider() {
        assertThatThrownBy(() -> selector.select(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector.select("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectorHoldsNoNetworkOrCredentialConfiguration() {
        assertThat(DefaultLlmProviderSelector.class.getDeclaredFields()).hasSize(1);
        assertThat(DefaultLlmProviderSelector.class.getDeclaredFields()[0].getType())
                .isEqualTo(LlmProvider.class);
    }
}

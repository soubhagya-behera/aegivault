package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * OLLAMA mode through the real application context: startup succeeds without
 * any live Ollama server (the provider performs no I/O until a completion is
 * requested), exactly one {@link LlmProvider} bean is active and it is the
 * {@link OllamaLlmProvider}, and the selector returns that provider for every
 * model name — the caller never chooses a provider.
 */
@SpringBootTest(
        properties = {
            "aegivault.gateway.provider=OLLAMA",
            "aegivault.gateway.ollama.base-url=http://localhost:11434",
            "aegivault.gateway.ollama.connect-timeout=5s",
            "aegivault.gateway.ollama.read-timeout=60s"
        })
class OllamaProviderWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmProviderSelector selector;

    @Autowired
    private OllamaProperties ollamaProperties;

    @Test
    void contextStartsWithoutALiveOllamaServer() {
        // Context startup is the assertion: nothing here requires a running
        // Ollama instance, and no request is attempted during wiring.
        assertThat(context.getBeanNamesForType(LlmProvider.class)).hasSize(1);
    }

    @Test
    void exactlyOneProviderIsActiveAndItIsOllama() {
        assertThat(context.getBean(LlmProvider.class))
                .isInstanceOf(OllamaLlmProvider.class)
                .isNotInstanceOf(MockLlmProvider.class);
    }

    @Test
    void selectorReturnsTheActiveOllamaProvider() {
        assertThat(selector.select("local-test-model"))
                .isSameAs(context.getBean(LlmProvider.class))
                .isInstanceOf(OllamaLlmProvider.class);
    }

    @Test
    void modelNamesNeverChangeTheSelectedProvider() {
        LlmProvider active = context.getBean(LlmProvider.class);

        assertThat(selector.select("llama3")).isSameAs(active);
        assertThat(selector.select("MOCK")).isSameAs(active);
        assertThat(selector.select("gpt-4")).isSameAs(active);
    }

    @Test
    void ollamaConfigurationKeepsLocalhostDefaults() {
        assertThat(ollamaProperties.getBaseUrl()).isEqualTo("http://localhost:11434");
        assertThat(ollamaProperties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(ollamaProperties.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
}

package com.aegivault.aegivault.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Configuration and wiring tests for configuration-driven provider selection
 * on a minimal Spring context (no database, no web server, no network, and no
 * Ollama server): exactly one {@link LlmProvider} bean exists per
 * configuration, the real {@link DefaultLlmProviderSelector} receives exactly
 * that provider, model names never influence selection, and an unsupported
 * provider value fails fast at startup naming the offending property.
 */
class LlmProviderConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProviderWiring.class);

    /** Minimal provider wiring: the real configuration and selector under test. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({LlmProviderProperties.class, OllamaProperties.class})
    @Import({LlmProviderConfiguration.class, DefaultLlmProviderSelector.class})
    static class ProviderWiring {
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append('\n');
        }
        return messages.toString();
    }

    @Test
    void defaultProviderIsMock() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            assertThat(context.getBean(LlmProviderProperties.class).getProvider())
                    .isEqualTo(LlmProviderType.MOCK);
            context.assertThat().hasSingleBean(LlmProvider.class);
            context.assertThat().getBean(LlmProvider.class)
                    .isInstanceOf(MockLlmProvider.class)
                    .isNotInstanceOf(OllamaLlmProvider.class);
        });
    }

    @Test
    void explicitMockSelectsTheMockProvider() {
        runner.withPropertyValues("aegivault.gateway.provider=MOCK").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(LlmProvider.class);
            context.assertThat().getBean(LlmProvider.class)
                    .isInstanceOf(MockLlmProvider.class)
                    .isNotInstanceOf(OllamaLlmProvider.class);
        });
    }

    @Test
    void explicitOllamaSelectsTheOllamaProvider() {
        runner.withPropertyValues("aegivault.gateway.provider=OLLAMA").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(LlmProvider.class);
            context.assertThat().getBean(LlmProvider.class)
                    .isInstanceOf(OllamaLlmProvider.class)
                    .isNotInstanceOf(MockLlmProvider.class);
        });
    }

    @Test
    void unsupportedProviderValueFailsFastAtStartupWithoutSecrets() {
        runner.withPropertyValues("aegivault.gateway.provider=OPENAI").run(context -> {
            context.assertThat().hasFailed();
            assertThat(failureMessages(context.getStartupFailure()))
                    .as("an unsupported provider value must name the offending property and leak nothing else")
                    .contains("aegivault.gateway.provider")
                    .doesNotContain("jwt-secret", "password");
        });
    }

    @Test
    void selectorReceivesExactlyTheActiveProvider() {
        runner.withPropertyValues("aegivault.gateway.provider=MOCK").run(context -> {
            LlmProvider active = context.getBean(LlmProvider.class);
            assertThat(context.getBean(LlmProviderSelector.class).select("model-a"))
                    .isSameAs(active)
                    .isInstanceOf(MockLlmProvider.class);
        });
        runner.withPropertyValues("aegivault.gateway.provider=OLLAMA").run(context -> {
            LlmProvider active = context.getBean(LlmProvider.class);
            assertThat(context.getBean(LlmProviderSelector.class).select("model-a"))
                    .isSameAs(active)
                    .isInstanceOf(OllamaLlmProvider.class);
        });
    }

    @Test
    void modelNameNeverInfluencesProviderSelection() {
        runner.withPropertyValues("aegivault.gateway.provider=OLLAMA").run(context -> {
            LlmProvider active = context.getBean(LlmProvider.class);
            LlmProviderSelector selector = context.getBean(LlmProviderSelector.class);

            assertThat(selector.select("llama3")).isSameAs(active);
            assertThat(selector.select("MOCK")).isSameAs(active);
            assertThat(selector.select("gpt-4")).isSameAs(active);
            assertThat(selector.select("OLLAMA")).isSameAs(active);
        });
        runner.withPropertyValues("aegivault.gateway.provider=MOCK").run(context -> {
            LlmProvider active = context.getBean(LlmProvider.class);
            LlmProviderSelector selector = context.getBean(LlmProviderSelector.class);

            assertThat(selector.select("llama3")).isSameAs(active);
            assertThat(selector.select("OLLAMA")).isSameAs(active);
        });
    }

    @Test
    void configurationDeclaresExactlyOneProviderBeanMethod() {
        long providerBeanMethods = Arrays.stream(LlmProviderConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> LlmProvider.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(providerBeanMethods)
                .as("provider wiring must expose exactly one LlmProvider bean, never an ambiguous pair")
                .isEqualTo(1);
    }
}

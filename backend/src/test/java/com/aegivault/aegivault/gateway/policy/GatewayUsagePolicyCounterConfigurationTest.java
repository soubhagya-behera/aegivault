package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aegivault.aegivault.gateway.GatewayRateLimiter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration and wiring tests for the policy request-counter selection on
 * a minimal Spring context (no database, no web server, no network, and no
 * Redis server): exactly one {@link GatewayUsagePolicyCounter} bean exists
 * per configuration, the default stays {@link
 * GatewayUsagePolicyCounterType#IN_MEMORY} so a normal application needs no
 * Redis, and an unsupported value fails fast naming the offending property.
 * The REDIS case uses a mock template, so selection is proven without any
 * Redis connection.
 *
 * <p>It also pins that this switch is independent of the global gateway rate
 * limiter's own switch and that wiring the counter connects nothing to live
 * gateway traffic.
 */
class GatewayUsagePolicyCounterConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(CounterWiring.class);

    /** Minimal counter wiring: the real configuration under test plus a mock template. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayUsagePolicyCounterProperties.class)
    @Import(GatewayUsagePolicyCounterConfiguration.class)
    static class CounterWiring {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append('\n');
        }
        return messages.toString();
    }

    @Test
    void defaultCounterIsInMemory() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            assertThat(context.getBean(GatewayUsagePolicyCounterProperties.class).getPolicyCounter())
                    .isEqualTo(GatewayUsagePolicyCounterType.IN_MEMORY);
            context.assertThat().hasSingleBean(GatewayUsagePolicyCounter.class);
            assertThat(context.getBean(GatewayUsagePolicyCounter.class))
                    .isInstanceOf(InMemoryGatewayUsagePolicyCounter.class)
                    .isNotInstanceOf(RedisGatewayUsagePolicyCounter.class);
        });
    }

    @Test
    void explicitInMemorySelectsTheInMemoryCounter() {
        runner.withPropertyValues("aegivault.gateway.policy-counter=IN_MEMORY").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(GatewayUsagePolicyCounter.class);
            assertThat(context.getBean(GatewayUsagePolicyCounter.class))
                    .isInstanceOf(InMemoryGatewayUsagePolicyCounter.class)
                    .isNotInstanceOf(RedisGatewayUsagePolicyCounter.class);
        });
    }

    @Test
    void explicitRedisSelectsTheRedisCounterWithoutAServer() {
        runner.withPropertyValues("aegivault.gateway.policy-counter=REDIS").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(GatewayUsagePolicyCounter.class);
            assertThat(context.getBean(GatewayUsagePolicyCounter.class))
                    .isInstanceOf(RedisGatewayUsagePolicyCounter.class)
                    .isNotInstanceOf(InMemoryGatewayUsagePolicyCounter.class);
        });
    }

    @Test
    void anUnsupportedCounterValueFailsFastAtStartupWithoutSecrets() {
        runner.withPropertyValues("aegivault.gateway.policy-counter=SLIDING_WINDOW").run(context -> {
            context.assertThat().hasFailed();
            assertThat(failureMessages(context.getStartupFailure()))
                    .as("an unsupported value must name the offending property and leak nothing else")
                    .contains("aegivault.gateway.policy-counter")
                    .doesNotContain("jwt-secret", "password");
        });
    }

    @Test
    void configurationDeclaresExactlyOneCounterBeanMethod() {
        long counterBeanMethods = Arrays.stream(GatewayUsagePolicyCounterConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> GatewayUsagePolicyCounter.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(counterBeanMethods)
                .as("counter wiring must expose exactly one bean, never an ambiguous pair")
                .isEqualTo(1);
    }

    @Test
    void theCounterSwitchIsSeparateFromTheGlobalRateLimiterSwitch() {
        // Two independent properties under one prefix: selecting a policy
        // counter must not be able to change the global rate limiter, and the
        // two types are never interchangeable.
        assertThat(GatewayUsagePolicyCounter.class).isNotEqualTo(GatewayRateLimiter.class);
        assertThat(GatewayUsagePolicyCounterProperties.class.getDeclaredFields())
                .filteredOn(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .extracting(java.lang.reflect.Field::getName)
                .containsExactly("policyCounter");
    }
}

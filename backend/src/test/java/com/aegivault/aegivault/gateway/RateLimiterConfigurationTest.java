package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration and wiring tests for configuration-driven rate-limiter
 * selection on a minimal Spring context (no database, no web server, no
 * network, and no Redis server): exactly one {@link GatewayRateLimiter}
 * bean exists per configuration, the default stays
 * {@link RateLimiterType#IN_MEMORY}, and an unsupported value fails fast
 * at startup naming the offending property. The REDIS case uses a mock
 * template, so selection is proven without any Redis connection.
 */
class RateLimiterConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LimiterWiring.class);

    /** Minimal limiter wiring: the real configuration under test plus a mock template. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimiterProperties.class)
    @Import(RateLimiterConfiguration.class)
    static class LimiterWiring {

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
    void defaultLimiterIsInMemory() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            assertThat(context.getBean(RateLimiterProperties.class).getRateLimiter())
                    .isEqualTo(RateLimiterType.IN_MEMORY);
            context.assertThat().hasSingleBean(GatewayRateLimiter.class);
            context.assertThat().getBean(GatewayRateLimiter.class)
                    .isInstanceOf(InMemoryGatewayRateLimiter.class)
                    .isNotInstanceOf(RedisGatewayRateLimiter.class);
        });
    }

    @Test
    void explicitInMemorySelectsTheInMemoryLimiter() {
        runner.withPropertyValues("aegivault.gateway.rate-limiter=IN_MEMORY").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(GatewayRateLimiter.class);
            context.assertThat().getBean(GatewayRateLimiter.class)
                    .isInstanceOf(InMemoryGatewayRateLimiter.class)
                    .isNotInstanceOf(RedisGatewayRateLimiter.class);
        });
    }

    @Test
    void explicitRedisSelectsTheRedisLimiterWithoutAServer() {
        runner.withPropertyValues("aegivault.gateway.rate-limiter=REDIS").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(GatewayRateLimiter.class);
            context.assertThat().getBean(GatewayRateLimiter.class)
                    .isInstanceOf(RedisGatewayRateLimiter.class)
                    .isNotInstanceOf(InMemoryGatewayRateLimiter.class);
        });
    }

    @Test
    void unsupportedLimiterValueFailsFastAtStartupWithoutSecrets() {
        runner.withPropertyValues("aegivault.gateway.rate-limiter=TOKEN_BUCKET").run(context -> {
            context.assertThat().hasFailed();
            assertThat(failureMessages(context.getStartupFailure()))
                    .as("an unsupported limiter value must name the offending property and leak nothing else")
                    .contains("aegivault.gateway.rate-limiter")
                    .doesNotContain("jwt-secret", "password");
        });
    }

    @Test
    void configurationDeclaresExactlyOneLimiterBeanMethod() {
        long limiterBeanMethods = Arrays.stream(RateLimiterConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> GatewayRateLimiter.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(limiterBeanMethods)
                .as("limiter wiring must expose exactly one GatewayRateLimiter bean, never an ambiguous pair")
                .isEqualTo(1);
    }
}

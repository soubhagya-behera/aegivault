package com.aegivault.aegivault.gateway.policy.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aegivault.aegivault.gateway.GatewayRateLimiter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyCounter;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration and wiring tests for the token-budget selection and the two
 * coordinators, on a minimal Spring context (no database, no web server, no
 * network, and no Redis server): exactly one {@link GatewayTokenBudget} bean
 * exists per configuration, the default stays
 * {@link GatewayTokenBudgetType#IN_MEMORY} so a normal application needs no
 * Redis, an unsupported value fails fast naming the offending property, and
 * both coordinators are built over that one budget. The REDIS case uses a mock
 * template, so selection is proven without any Redis connection.
 *
 * <p>It also pins that this switch is independent of the global gateway rate
 * limiter's and of the request-policy counter's own switches.
 */
class GatewayTokenBudgetConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BudgetWiring.class);

    /** Minimal wiring: the real configurations under test plus mock collaborators. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayTokenBudgetProperties.class)
    @Import({GatewayTokenBudgetConfiguration.class, GatewayTokenBudgetEnforcementConfiguration.class})
    static class BudgetWiring {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        GatewayUsagePolicyResolver gatewayUsagePolicyResolver() {
            return mock(GatewayUsagePolicyResolver.class);
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
    void defaultBudgetIsInMemory() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            assertThat(context.getBean(GatewayTokenBudgetProperties.class).getTokenBudget())
                    .isEqualTo(GatewayTokenBudgetType.IN_MEMORY);
            context.assertThat().hasSingleBean(GatewayTokenBudget.class);
            assertThat(context.getBean(GatewayTokenBudget.class))
                    .isInstanceOf(InMemoryGatewayTokenBudget.class)
                    .isNotInstanceOf(RedisGatewayTokenBudget.class);
        });
    }

    @Test
    void explicitRedisSelectsTheRedisBudgetWithoutAServer() {
        runner.withPropertyValues("aegivault.gateway.token-budget=REDIS").run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(GatewayTokenBudget.class);
            assertThat(context.getBean(GatewayTokenBudget.class))
                    .isInstanceOf(RedisGatewayTokenBudget.class)
                    .isNotInstanceOf(InMemoryGatewayTokenBudget.class);
        });
    }

    @Test
    void anUnsupportedBudgetValueFailsFastAtStartupWithoutSecrets() {
        runner.withPropertyValues("aegivault.gateway.token-budget=HOURLY").run(context -> {
            context.assertThat().hasFailed();
            assertThat(failureMessages(context.getStartupFailure()))
                    .as("an unsupported value must name the offending property and leak nothing else")
                    .contains("aegivault.gateway.token-budget")
                    .doesNotContain("jwt-secret", "password");
        });
    }

    @Test
    void bothCoordinatorsAreWiredOverTheOneSelectedBudget() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            // Exactly one of each, so the completion service can never see an
            // ambiguous collaborator; and both are built from the single budget
            // bean, which is what makes a reservation visible to the settlement
            // that closes it.
            context.assertThat().hasSingleBean(GatewayTokenBudget.class);
            context.assertThat().hasSingleBean(GatewayTokenBudgetEnforcementService.class);
            context.assertThat().hasSingleBean(GatewayTokenBudgetSettlementService.class);
        });
    }

    @Test
    void theBudgetWiringExposesExactlyOneBudgetBeanMethod() {
        long budgetBeanMethods = Arrays.stream(GatewayTokenBudgetConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> GatewayTokenBudget.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(budgetBeanMethods)
                .as("budget wiring must expose exactly one bean, never an ambiguous pair")
                .isEqualTo(1);
    }

    @Test
    void theBudgetSwitchIsSeparateFromTheOtherTwoGatewaySwitches() {
        // Three independent properties under one prefix, guarding three
        // different quantities; selecting one can never change another.
        assertThat(GatewayTokenBudget.class)
                .isNotEqualTo(GatewayRateLimiter.class)
                .isNotEqualTo(GatewayUsagePolicyCounter.class);
        assertThat(GatewayTokenBudgetProperties.class.getDeclaredFields())
                .filteredOn(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .extracting(java.lang.reflect.Field::getName)
                .containsExactly("tokenBudget");
    }
}

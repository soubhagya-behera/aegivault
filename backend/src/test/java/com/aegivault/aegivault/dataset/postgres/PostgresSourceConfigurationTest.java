package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wiring tests for the configured PostgreSQL source, on a minimal Spring
 * context (no database, no web server, no network): the properties bind under
 * their documented prefix, the defaults are safe for a checkout that has no
 * source configured, and the bean is exposed as the abstraction rather than as
 * the {@code DriverManager} implementation.
 *
 * <p>A missing or unusable source configuration must not stop the application
 * from starting: nothing connects until discovery asks it to, so this proves the
 * wiring adds no startup coupling.
 */
class PostgresSourceConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SourceWiring.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PostgresSourceProperties.class)
    @Import(PostgresSourceConfiguration.class)
    static class SourceWiring {}

    @Test
    void theDefaultsAreSafeAndNoSourceBeanExistsUntilOneIsConfigured() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            PostgresSourceProperties properties = context.getBean(PostgresSourceProperties.class);
            assertThat(properties.getHost()).isEqualTo("localhost");
            assertThat(properties.getPort()).isEqualTo(5432);
            assertThat(properties.getSchema()).isEqualTo("public");
            assertThat(properties.getConnectTimeoutSeconds()).isEqualTo(5);
            // A checkout with no source configured has nothing to inject and
            // still boots: an absent optional integration is not a startup
            // failure, which matters because this milestone has no endpoint.
            context.assertThat().doesNotHaveBean(PostgresDataSource.class);
        });
    }

    @Test
    void aConfiguredSourceIsExposedAsTheAbstraction() {
        runner.withPropertyValues(
                        "aegivault.dataset.postgres.host=reporting.internal",
                        "aegivault.dataset.postgres.port=6543",
                        "aegivault.dataset.postgres.database=analytics",
                        "aegivault.dataset.postgres.username=aegivault_readonly",
                        "aegivault.dataset.postgres.schema=analytics_core",
                        "aegivault.dataset.postgres.connect-timeout-seconds=7")
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    PostgresSourceProperties properties = context.getBean(PostgresSourceProperties.class);
                    assertThat(properties.getHost()).isEqualTo("reporting.internal");
                    assertThat(properties.getPort()).isEqualTo(6543);
                    assertThat(properties.getDatabase()).isEqualTo("analytics");
                    assertThat(properties.getUsername()).isEqualTo("aegivault_readonly");
                    assertThat(properties.getSchema()).isEqualTo("analytics_core");
                    assertThat(properties.getConnectTimeoutSeconds()).isEqualTo(7);
                    // Exposed as the abstraction, so no caller reaches a JDBC URL
                    // or a credential through the bean.
                    context.assertThat().hasSingleBean(PostgresDataSource.class);
                    assertThat(context.getBean(PostgresDataSource.class).schemaName())
                            .isEqualTo("analytics_core");
                });
    }

    @Test
    void aConfiguredButUnusableSourceFailsAtStartupRatherThanOnFirstUse() {
        runner.withPropertyValues(
                        "aegivault.dataset.postgres.database=analytics",
                        "aegivault.dataset.postgres.username=aegivault_readonly",
                        "aegivault.dataset.postgres.connect-timeout-seconds=0")
                .run(context -> {
                    context.assertThat().hasFailed();
                    // A misconfigured source is a configuration error, reported at
                    // startup instead of waiting for the first discovery call.
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("connectTimeoutSeconds");
                });
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append('\n');
        }
        return messages.toString();
    }
}

package com.aegivault.aegivault.dataset.postgres;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single configured {@link PostgresDataSource} from
 * {@link PostgresSourceProperties}.
 *
 * <p>Nothing here connects to anything: the bean is constructed from
 * configuration and only opens a connection when discovery asks it to.
 *
 * <p><strong>The bean exists only when a source is configured.</strong> The
 * source's database is the condition, so a checkout with no source configured
 * has no {@link PostgresDataSource} to inject and boots exactly as before — a
 * missing optional integration can never become a startup failure. A source that
 * <em>is</em> configured but unusable is a different matter and fails loudly at
 * startup, naming the offending field, because a silently misconfigured source
 * that only fails when someone first calls discovery is worse than a clear boot
 * error.
 *
 * <p>The bean is exposed as the {@link PostgresDataSource} abstraction, never as
 * the {@code DriverManager} implementation, so callers cannot reach a JDBC URL
 * or a credential through it.
 */
@Configuration(proxyBeanMethods = false)
public class PostgresSourceConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "aegivault.dataset.postgres", name = "database")
    PostgresDataSource postgresDataSource(PostgresSourceProperties properties) {
        return new DriverManagerPostgresDataSource(properties);
    }

    /**
     * The bounded row stream, over the same connection boundary.
     *
     * <p>Conditional on exactly the same property as the source itself, so a
     * checkout with no source configured still has no row-reading bean to
     * inject. The configured bounds are validated here, which means a source
     * configured with an out-of-range row limit or fetch size fails at startup
     * rather than on the first read.
     */
    @Bean
    @ConditionalOnProperty(prefix = "aegivault.dataset.postgres", name = "database")
    PostgresTableRowSource postgresTableRowSource(PostgresSourceProperties properties) {
        return new JdbcPostgresTableRowSource(
                new PostgresRowLimits(properties.getMaxRows(), properties.getFetchSize()));
    }
}

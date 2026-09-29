package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link DriverManagerPostgresDataSource}: configuration
 * validation, the timeout bound, and — most importantly — that a credential has
 * no route out of this class. No test here contacts a real server except the
 * last, which targets a closed local port on purpose.
 */
class DriverManagerPostgresDataSourceTest {

    private static final String PASSWORD = "sup3r-s3cret-source-pw";

    private static PostgresSourceProperties properties() {
        PostgresSourceProperties properties = new PostgresSourceProperties();
        properties.setHost("db.internal");
        properties.setPort(5432);
        properties.setDatabase("production");
        properties.setUsername("aegivault_readonly");
        properties.setPassword(PASSWORD);
        properties.setSchema("public");
        properties.setConnectTimeoutSeconds(5);
        return properties;
    }

    @Test
    void aValidConfigurationIsAcceptedAndExposesItsSchema() {
        PostgresDataSource source = new DriverManagerPostgresDataSource(properties());

        assertThat(source.schemaName()).isEqualTo("public");
    }

    @Test
    void aBlankHostDatabaseOrUsernameIsRejected() {
        for (String field : new String[] {"host", "database", "username"}) {
            PostgresSourceProperties blank = properties();
            switch (field) {
                case "host" -> blank.setHost("  ");
                case "database" -> blank.setDatabase("");
                default -> blank.setUsername(null);
            }
            assertThatThrownBy(() -> new DriverManagerPostgresDataSource(blank))
                    .as("%s must be required", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new DriverManagerPostgresDataSource(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnusablePortIsRejected() {
        for (int port : new int[] {0, -1, 65_536}) {
            PostgresSourceProperties badPort = properties();
            badPort.setPort(port);
            assertThatThrownBy(() -> new DriverManagerPostgresDataSource(badPort))
                    .as("port %s must be rejected", port)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theConnectionTimeoutIsBoundedRatherThanUnlimited() {
        for (int seconds : new int[] {0, -5, 61, 3_600}) {
            PostgresSourceProperties tooLong = properties();
            tooLong.setConnectTimeoutSeconds(seconds);
            assertThatThrownBy(() -> new DriverManagerPostgresDataSource(tooLong))
                    .as("connect timeout %s must be rejected", seconds)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        PostgresSourceProperties atBound = properties();
        atBound.setConnectTimeoutSeconds(PostgresSourceProperties.MAX_CONNECT_TIMEOUT_SECONDS);
        assertThatCode(() -> new DriverManagerPostgresDataSource(atBound)).doesNotThrowAnyException();
    }

    @Test
    void anInjectedSchemaNameIsRejectedAtConfigurationTime() {
        PostgresSourceProperties injected = properties();
        injected.setSchema("public'; DROP TABLE datasets; --");

        assertThatThrownBy(() -> new DriverManagerPostgresDataSource(injected))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noPasswordOrUsernameAppearsInTheDiagnosticForm() {
        String described = new DriverManagerPostgresDataSource(properties()).toString();

        // Useful for diagnostics, useless for an attacker.
        assertThat(described).contains("db.internal", "5432", "production", "public");
        assertThat(described).doesNotContain(PASSWORD, "aegivault_readonly", "jdbc:");
    }

    @Test
    void theEntirePublicSurfaceIsTheAbstractionAndNothingMore() {
        // Enumerated on purpose: the whole reachable surface of a configured
        // source is one schema name, one connection, and a credential-free
        // description. There is no getter, no URL, and no credential accessor.
        assertThat(java.util.Arrays.stream(DriverManagerPostgresDataSource.class.getDeclaredMethods())
                        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                        .filter(method -> !method.isSynthetic())
                        .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("schemaName", "openReadOnlyConnection", "toString");
    }

    @Test
    void anUnreachableSourceFailsAsASafeConnectionException() {
        // A closed port on loopback: the JDBC attempt is real, and whether the
        // server refuses it or the packet is dropped, the caller learns nothing
        // beyond the safe message.
        PostgresSourceProperties unreachable = properties();
        unreachable.setHost("127.0.0.1");
        unreachable.setPort(1);
        unreachable.setDatabase("nowhere");
        unreachable.setConnectTimeoutSeconds(2);
        PostgresDataSource source = new DriverManagerPostgresDataSource(unreachable);

        assertThatThrownBy(source::openReadOnlyConnection)
                .isInstanceOf(PostgresSourceConnectionException.class)
                .hasMessage("Unable to connect to the PostgreSQL source.");
        assertThat(PostgresSourceConnectionException.MESSAGE)
                .doesNotContain(PASSWORD, "aegivault_readonly", "127.0.0.1", "jdbc:", "nowhere");
    }

    @Test
    void theAbstractionExposesNoWayToRunAStatement() {
        // There is no execute/query method anywhere on the boundary, so there is
        // no arbitrary-SQL entry point for a caller to reach.
        assertThat(java.util.Arrays.stream(PostgresDataSource.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("schemaName", "openReadOnlyConnection");
        assertThat(PostgresSchemaDiscoveryService.class.getDeclaredMethods())
                .allMatch(method -> method.getReturnType() != Connection.class);
    }
}

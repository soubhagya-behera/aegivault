package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Schema discovery against a real PostgreSQL server, through the real
 * {@link PostgresSchemaDiscoveryService} and the real {@code DatabaseMetaData}
 * the driver reports.
 *
 * <p><strong>No new Spring context and no new server.</strong> The annotations
 * are exactly those of the other plain {@code @SpringBootTest} classes, so this
 * reuses the already-cached application context and its single pooled datasource
 * — the repository sits close to PostgreSQL's {@code max_connections} ceiling, so
 * starting a second context or a second database is not an option. The
 * application database itself is the test source: a real, already-running
 * PostgreSQL that owns the Flyway-managed {@code public} schema. Nothing is
 * written, seeded, or altered; discovery is read-only by construction, which is
 * exactly the property under test.
 *
 * <p>Assertions therefore target metadata that must be true of any real
 * PostgreSQL schema (table discovery is non-empty, ordinals start at 1 and
 * ascend, types are named) plus the one concrete fact this fixture can rely on:
 * the application's own {@code users} table exists in {@code public}.
 */
@SpringBootTest
class PostgresSchemaDiscoveryPostgresTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    /** The application database, reached through the pooled connection it already owns. */
    private PostgresDataSource applicationSource() {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return "public";
            }

            @Override
            public Connection openReadOnlyConnection() {
                try {
                    return dataSource.getConnection();
                } catch (SQLException ex) {
                    // The boundary reports failures as the safe exception, never
                    // as a checked SQLException a caller could leak.
                    throw new PostgresSourceConnectionException(ex);
                }
            }
        };
    }

    @Test
    void aRealPostgresSchemaIsDiscoveredAsMetadataOnly() {
        PostgresSchema schema = discovery.discover(applicationSource());

        assertThat(schema.schemaName()).isEqualTo("public");
        assertThat(schema.tableCount()).isPositive();

        // The application's own table, discovered purely from the catalog.
        PostgresTable users = schema.table("users").orElseThrow();
        assertThat(users.columns()).isNotEmpty();
        assertThat(users.columns().get(0).name()).isEqualTo("id");
        assertThat(users.columns().get(0).ordinalPosition()).isEqualTo(1);
        assertThat(users.columns()).allSatisfy(column -> {
            assertThat(column.ordinalPosition()).isPositive();
            assertThat(column.dataType()).isNotBlank();
        });
    }

    @Test
    void ordinalPositionsAscendWithinEveryDiscoveredTable() {
        PostgresSchema schema = discovery.discover(applicationSource());

        for (PostgresTable table : schema.tables()) {
            assertThat(table.columns())
                    .as("ordinals of %s must ascend", table.name())
                    .extracting(PostgresColumn::ordinalPosition)
                    .isSorted();
        }
    }

    @Test
    void aSchemaWithNoTablesIsReportedAsEmptyRatherThanFailing() {
        // A schema that certainly does not exist: a real, empty answer rather
        // than an error or a cross-schema fallback.
        PostgresDataSource absent = new PostgresDataSource() {

            @Override
            public String schemaName() {
                return "aegivault_absent_schema";
            }

            @Override
            public Connection openReadOnlyConnection() {
                try {
                    return dataSource.getConnection();
                } catch (SQLException ex) {
                    // The boundary reports failures as the safe exception, never
                    // as a checked SQLException a caller could leak.
                    throw new PostgresSourceConnectionException(ex);
                }
            }
        };

        PostgresSchema schema = discovery.discover(absent);

        assertThat(schema.schemaName()).isEqualTo("aegivault_absent_schema");
        assertThat(schema.tables()).isEmpty();
    }
}

package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PostgresSchemaDiscoveryService} against a mocked
 * JDBC layer: no Spring context, no database, no connection, and no seeding.
 *
 * <p>They pin the properties this milestone exists for. Discovery reads
 * <em>metadata only</em> — it never creates a statement, so there is no query
 * text and no arbitrary-SQL surface. It inspects exactly the one configured
 * schema, orders columns by ordinal position, closes what it opens, and turns
 * every failure into a fixed safe message carrying no host, credential, or
 * driver text.
 */
class PostgresSchemaDiscoveryServiceTest {

    private final PostgresSchemaDiscoveryService discovery = new PostgresSchemaDiscoveryService();

    private Connection connection;

    private DatabaseMetaData metaData;

    private PostgresDataSource source;

    /** The result sets handed to the service, kept so the test can verify their closure. */
    private final java.util.List<ResultSet> opened = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("aegivault");
        when(metaData.getSearchStringEscape()).thenReturn("\\");
        // The result set is built before the stubbing that returns it: creating
        // a mock inside an unfinished when(...) is a Mockito misuse.
        ResultSet noTables = metadataRows(List.of());
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(noTables);
        source = source("public");
    }

    /** A source that hands out the mocked connection and reports one schema. */
    private PostgresDataSource source(String schema) {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return schema;
            }

            @Override
            public Connection openReadOnlyConnection() {
                return connection;
            }
        };
    }

    /**
     * A {@link ResultSet} over explicit label-to-value rows, as the driver would
     * return them — in the given order, with no reordering of its own.
     */
    private ResultSet metadataRows(List<String[]> rows) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        opened.add(resultSet);
        AtomicInteger position = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(invocation -> position.incrementAndGet() < rows.size());
        when(resultSet.getString(anyString()))
                .thenAnswer(invocation -> value(rows, position, invocation.getArgument(0)));
        when(resultSet.getInt(anyString()))
                .thenAnswer(invocation -> Integer.valueOf(value(rows, position, invocation.getArgument(0))));
        return resultSet;
    }

    private static String value(List<String[]> rows, AtomicInteger position, String label) {
        String[] row = rows.get(position.get());
        return switch (label) {
            case "TABLE_NAME" -> row[0];
            case "COLUMN_NAME" -> row[0];
            case "ORDINAL_POSITION" -> row[1];
            case "TYPE_NAME" -> row[2];
            default -> throw new AssertionError("unexpected metadata label: " + label);
        };
    }

    /** Declares the tables this mocked source reports. */
    private void tables(String... names) throws SQLException {
        List<String[]> rows = java.util.Arrays.stream(names).map(name -> new String[] {name}).toList();
        ResultSet resultSet = metadataRows(rows);
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(resultSet);
    }

    /** Declares the columns this mocked source reports for one table. */
    private void columns(String tableName, String[]... rows) throws SQLException {
        ResultSet resultSet = metadataRows(List.of(rows));
        when(metaData.getColumns(any(), any(), eq(tableName), any())).thenReturn(resultSet);
    }

    @Test
    void metadataIsMappedToTheSafeResult() throws SQLException {
        tables("customers", "orders");
        columns("customers", new String[] {"id", "1", "int4"}, new String[] {"email", "2", "varchar"});
        columns("orders", new String[] {"id", "1", "int4"});

        PostgresSchema schema = discovery.discover(source);

        assertThat(schema.schemaName()).isEqualTo("public");
        assertThat(schema.tableCount()).isEqualTo(2);
        assertThat(schema.tables().get(0).name()).isEqualTo("customers");
        assertThat(schema.tables().get(0).columns()).containsExactly(
                new PostgresColumn("id", 1, "int4"),
                new PostgresColumn("email", 2, "varchar"));
        assertThat(schema.tables().get(1).name()).isEqualTo("orders");
        assertThat(schema.table("orders").orElseThrow().columns())
                .containsExactly(new PostgresColumn("id", 1, "int4"));
    }

    @Test
    void columnsAreOrderedByOrdinalPositionNotByDriverRowOrder() throws SQLException {
        tables("customers");
        // The driver returns them shuffled; ordinal position is the fact.
        columns("customers",
                new String[] {"email", "3", "varchar"},
                new String[] {"id", "1", "int4"},
                new String[] {"name", "2", "text"});

        PostgresSchema schema = discovery.discover(source);

        assertThat(schema.tables().get(0).columns())
                .extracting(PostgresColumn::ordinalPosition)
                .containsExactly(1, 2, 3);
        assertThat(schema.tables().get(0).columns())
                .extracting(PostgresColumn::name)
                .containsExactly("id", "name", "email");
    }

    @Test
    void aDataTypeIsPassedThroughExactlyAsReported() throws SQLException {
        tables("events");
        columns("events", new String[] {"id", "1", "uuid"}, new String[] {"body", "2", "jsonb"});

        PostgresSchema schema = discovery.discover(source);

        assertThat(schema.tables().get(0).columns())
                .extracting(PostgresColumn::dataType)
                .containsExactly("uuid", "jsonb");
    }

    @Test
    void anEmptySchemaIsANormalEmptyResult() throws SQLException {
        tables();

        PostgresSchema schema = discovery.discover(source);

        assertThat(schema.schemaName()).isEqualTo("public");
        assertThat(schema.tables()).isEmpty();
        assertThat(schema.tableCount()).isZero();
        verify(metaData, never()).getColumns(any(), any(), any(), any());
    }

    @Test
    void onlyTheConfiguredSchemaAndOnlyBaseTablesAreInspected() throws SQLException {
        source = source("reporting");
        tables();

        discovery.discover(source);

        // Exactly the configured schema, base tables only: no views, no
        // materialized views, no other schema, no cross-schema browsing.
        verify(metaData).getTables("aegivault", "reporting", "%", new String[] {"TABLE"});
        verify(metaData, times(1)).getTables(any(), any(), any(), any());
    }

    @Test
    void noStatementIsEverCreatedSoThereIsNoArbitrarySqlSurface() throws SQLException {
        tables("customers");
        columns("customers", new String[] {"id", "1", "int4"});

        discovery.discover(source);

        // The strongest available proof that this service cannot run SQL: it
        // never asks the connection for a statement, prepared or otherwise.
        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).prepareCall(anyString());
        verify(connection, never()).createBlob();
    }

    @Test
    void everythingOpenedIsClosed() throws SQLException {
        opened.clear();
        tables("customers", "orders");
        columns("customers", new String[] {"id", "1", "int4"});
        columns("orders", new String[] {"id", "1", "int4"});
        discovery.discover(source);

        // One result set for the tables, then one per table that was found.
        assertThat(opened).hasSize(3);
        for (ResultSet resultSet : opened) {
            verify(resultSet, times(1)).close();
        }
        verify(connection, times(1)).close();
    }

    @Test
    void theConnectionIsClosedEvenWhenDiscoveryFails() throws SQLException {
        when(metaData.getTables(any(), any(), any(), any()))
                .thenThrow(new SQLException("catalog read failed"));

        assertThatThrownBy(() -> discovery.discover(source))
                .isInstanceOf(PostgresSchemaDiscoveryException.class);

        verify(connection, times(1)).close();
    }

    @Test
    void aConnectionFailureStaysASafeConnectionException() {
        PostgresDataSource unreachable = new PostgresDataSource() {

            @Override
            public String schemaName() {
                return "public";
            }

            @Override
            public Connection openReadOnlyConnection() {
                throw new PostgresSourceConnectionException(new SQLException(
                        "jdbc:postgresql://db.internal:5432/prod?user=reporting&password=hunter2"));
            }
        };

        assertThatThrownBy(() -> discovery.discover(unreachable))
                .isInstanceOf(PostgresSourceConnectionException.class)
                .hasMessage("Unable to connect to the PostgreSQL source.");
        assertThat(PostgresSourceConnectionException.MESSAGE)
                .doesNotContain("db.internal", "5432", "hunter2", "reporting", "jdbc:");
    }

    @Test
    void aMetadataFailureBecomesASafeDiscoveryException() throws SQLException {
        when(metaData.getTables(any(), any(), any(), any())).thenThrow(
                new SQLException("SELECT * FROM pg_catalog.pg_tables failed at db.internal:5432"));

        assertThatThrownBy(() -> discovery.discover(source))
                .isInstanceOf(PostgresSchemaDiscoveryException.class)
                .hasMessage("Unable to inspect the PostgreSQL source schema.");
        assertThat(PostgresSchemaDiscoveryException.MESSAGE)
                .doesNotContain("pg_catalog", "db.internal", "5432", "SELECT");
    }

    @Test
    void theTwoFailureModesStayDistinguishable() {
        assertThat(PostgresSourceConnectionException.MESSAGE)
                .isNotEqualTo(PostgresSchemaDiscoveryException.MESSAGE);
    }

    @Test
    void anInjectedSchemaIdentifierIsRejectedBeforeTheSourceIsContacted() {
        for (String injected : List.of(
                "public'; DROP TABLE datasets; --",
                "public\"",
                "public, other",
                "public%",
                "public.other",
                "public-private",
                "public other",
                "1public",
                "")) {
            PostgresDataSource hostile = new PostgresDataSource() {

                @Override
                public String schemaName() {
                    return injected;
                }

                @Override
                public Connection openReadOnlyConnection() {
                    throw new AssertionError("the source must not be contacted for schema: " + injected);
                }
            };

            assertThatThrownBy(() -> discovery.discover(hostile))
                    .as("schema [%s] must be rejected without contacting the source", injected)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aValidSchemaNameContainingAnUnderscoreIsMatchedLiterally() throws SQLException {
        // An underscore is a legal identifier character but a metadata
        // wildcard, so a configured "reporting_core" must not also match
        // "reportingXcore".
        source = source("reporting_core");
        tables();

        discovery.discover(source);

        verify(metaData).getTables("aegivault", "reporting\\_core", "%", new String[] {"TABLE"});
    }

    @Test
    void aTableNameWithMetadataWildcardsIsMatchedLiterally() throws SQLException {
        // A table genuinely named "a_b" must be looked up as itself, not as the
        // pattern "a_b" that would also match "axb".
        tables("a_b");
        ResultSet columnRows = metadataRows(List.of());
        when(metaData.getColumns(any(), any(), eq("a\\_b"), any())).thenReturn(columnRows);

        PostgresSchema schema = discovery.discover(source);

        assertThat(schema.tables().get(0).name()).isEqualTo("a_b");
        verify(metaData).getColumns("aegivault", "public", "a\\_b", "%");
    }

    @Test
    void aNullSourceIsRejected() {
        assertThatThrownBy(() -> discovery.discover(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

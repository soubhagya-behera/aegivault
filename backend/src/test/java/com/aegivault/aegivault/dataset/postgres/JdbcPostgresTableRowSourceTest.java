package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit tests for {@link JdbcPostgresTableRowSource} against a mocked JDBC
 * layer: no Spring context, no database, and no network.
 *
 * <p>They pin the properties this milestone exists for. The one statement the
 * source can build is narrow and quoted, a hostile identifier is refused before
 * any SQL exists, rows arrive one at a time, the row ceiling stops the stream
 * and says so, and no failure message carries a row value, the SQL, or a
 * credential.
 *
 * <p>All values are obviously synthetic.
 */
class JdbcPostgresTableRowSourceTest {

    /** Obviously synthetic; no test here uses anything resembling real data. */
    private static final String SYNTHETIC = "synthetic-value";

    private final List<PostgresTableRow> delivered = new ArrayList<>();

    private Connection connection;

    private PreparedStatement statement;

    private ResultSet rows;

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(connection.prepareStatement(any())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        delivered.clear();
    }

    /** A source over the mocked connection, reporting one configured schema. */
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

    /** A source that fails the test if it is contacted at all. */
    private PostgresDataSource neverContacted(String schema) {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return schema;
            }

            @Override
            public Connection openReadOnlyConnection() {
                throw new AssertionError("the source must not be contacted");
            }
        };
    }

    /** Stubs the result set with the given rows, in the given order. */
    private void rowsOf(List<Object[]> values, int columnCount) throws SQLException {
        AtomicInteger position = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(invocation -> position.incrementAndGet() < values.size());
        for (int column = 1; column <= columnCount; column++) {
            int index = column;
            when(rows.getObject(index)).thenAnswer(
                    invocation -> values.get(position.get())[index - 1]);
        }
    }

    private static PostgresTable table(String name, PostgresColumn... columns) {
        return new PostgresTable(name, List.of(columns));
    }

    private static final PostgresTable CUSTOMERS = table("customers",
            new PostgresColumn("id", 1, "int4"),
            new PostgresColumn("email", 2, "varchar"));

    private PostgresRowStreamResult stream(int maxRows, int fetchSize) {
        return new JdbcPostgresTableRowSource(new PostgresRowLimits(maxRows, fetchSize))
                .streamRows(source("public"), CUSTOMERS, delivered::add);
    }

    @Test
    void aDiscoveredTableIsStreamedOneRowAtATime() throws SQLException {
        List<Object[]> values = new ArrayList<>();
        values.add(new Object[] {1, SYNTHETIC + "-1"});
        values.add(new Object[] {2, SYNTHETIC + "-2"});
        values.add(new Object[] {3, SYNTHETIC + "-3"});
        rowsOf(values, 2);

        PostgresRowStreamResult result = stream(100, 50);

        assertThat(result.rowsRead()).isEqualTo(3);
        assertThat(result.columnsRead()).isEqualTo(2);
        assertThat(result.rowLimitReached()).isFalse();
        assertThat(delivered).hasSize(3);
        // Row values keep the discovered column order, position for position.
        assertThat(delivered.get(0).values()).containsExactly(1, SYNTHETIC + "-1");
        assertThat(delivered.get(2).values()).containsExactly(3, SYNTHETIC + "-3");
        assertThat(delivered.get(0).columns()).isEqualTo(CUSTOMERS.columns());
    }

    @Test
    void theStatementIsTheOneNarrowQuotedSelectAndNothingElse() throws SQLException {
        List<Object[]> values = new ArrayList<>();
        values.add(new Object[] {1, SYNTHETIC});
        rowsOf(values, 2);

        new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(source("analytics_core"), CUSTOMERS, delivered::add);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        // Every identifier validated against the strict grammar and then quoted;
        // the only clauses are SELECT and FROM.
        assertThat(sql.getValue()).isEqualTo("SELECT \"id\", \"email\" FROM \"analytics_core\".\"customers\"");
        assertThat(sql.getValue())
                .doesNotContain("WHERE", "ORDER BY", "LIMIT", "OFFSET", "JOIN", "GROUP BY", ";", "--", "*");
    }

    @Test
    void aHostileTableNameIsRejectedBeforeAnySqlIsBuilt() {
        for (String hostile : List.of(
                "customers'; DROP TABLE datasets; --",
                "customers\"",
                "customers, other",
                "other.customers",
                "customers public",
                "customers-public",
                "customers%",
                "1customers")) {
            PostgresTable injected = table(hostile, new PostgresColumn("id", 1, "int4"));

            assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                    .streamRows(neverContacted("public"), injected, delivered::add))
                    .as("table [%s] must be refused before any SQL", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // A blank name is refused even earlier, by the discovery record itself,
        // so it can never be turned into an identifier at all.
        assertThatThrownBy(() -> table("  ", new PostgresColumn("id", 1, "int4")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(delivered).isEmpty();
    }

    @Test
    void aHostileColumnNameRefusesTheWholeTable() {
        // A partly selectable table would be a silently incomplete read, so a
        // table with one unquotable column is refused entirely.
        PostgresTable injected = table("customers",
                new PostgresColumn("id", 1, "int4"),
                new PostgresColumn("email\" FROM users --", 2, "varchar"));

        assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(neverContacted("public"), injected, delivered::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(delivered).isEmpty();
    }

    @Test
    void aHostileSchemaNameIsRejectedBeforeTheSourceIsContacted() {
        assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(neverContacted("public'; DROP TABLE datasets; --"), CUSTOMERS, delivered::add))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTableWithNoColumnsOrNoIdentityIsRejected() {
        assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(neverContacted("public"), table("empty"), delivered::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(neverContacted("public"), null, delivered::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(null, CUSTOMERS, delivered::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcPostgresTableRowSource(PostgresRowLimits.defaults())
                .streamRows(source("public"), CUSTOMERS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void anEmptyTableProducesZeroRowsAndNoTruncation() throws SQLException {
        rowsOf(new ArrayList<>(), 2);

        PostgresRowStreamResult result = stream(100, 50);

        assertThat(result.rowsRead()).isZero();
        assertThat(result.columnsRead()).isEqualTo(2);
        assertThat(result.rowLimitReached()).isFalse();
        assertThat(delivered).isEmpty();
    }
}

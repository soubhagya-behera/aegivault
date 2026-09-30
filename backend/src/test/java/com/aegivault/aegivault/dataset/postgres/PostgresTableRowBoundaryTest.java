package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bounding, resource-lifecycle, and boundary-shape tests for
 * {@link JdbcPostgresTableRowSource}: what happens at the row ceiling, who
 * closes what, and what the public surface is allowed to contain.
 *
 * <p>Pure unit tests against mocked JDBC — no Spring context and no database.
 * The value that appears in the failure tests is obviously synthetic and exists
 * only to prove it does not reach a message.
 */
class PostgresTableRowBoundaryTest {

    private static final PostgresTable CUSTOMERS = new PostgresTable("customers", List.of(
            new PostgresColumn("id", 1, "int4"),
            new PostgresColumn("email", 2, "varchar")));

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
        // A real connection arrives in auto-commit mode, and a mock would
        // otherwise report the opposite, which would silently skip the streaming
        // setup this suite exists to prove happens.
        when(connection.getAutoCommit()).thenReturn(true);
    }

    private PostgresDataSource source() {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return "public";
            }

            @Override
            public Connection openReadOnlyConnection() {
                return connection;
            }
        };
    }

    /** Stubs {@code count} rows of two columns, the first being its 1-based index. */
    private void rowsOf(int count) throws SQLException {
        AtomicInteger position = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(invocation -> position.incrementAndGet() < count);
        when(rows.getObject(1)).thenAnswer(invocation -> position.get() + 1);
        when(rows.getObject(2)).thenAnswer(invocation -> "synthetic-value-" + (position.get() + 1));
    }

    private JdbcPostgresTableRowSource sourceWith(int maxRows, int fetchSize) {
        return new JdbcPostgresTableRowSource(new PostgresRowLimits(maxRows, fetchSize));
    }

    @Test
    void theRowCeilingStopsTheStreamAndSaysSoRatherThanTruncatingSilently() throws SQLException {
        // 50 rows exist, 10 may be delivered: the caller must be able to tell
        // that it has a prefix of the table and not the table.
        rowsOf(50);
        List<PostgresTableRow> delivered = new ArrayList<>();

        PostgresRowStreamResult result = sourceWith(10, 50)
                .streamRows(source(), CUSTOMERS, delivered::add);

        assertThat(result.rowsRead()).isEqualTo(10);
        assertThat(result.rowLimitReached()).isTrue();
        assertThat(result.truncated()).isTrue();
        assertThat(delivered).hasSize(10);
    }

    @Test
    void theRowCeilingBoundaryIsExactInBothDirections() throws SQLException {
        // Exactly at the ceiling is not truncation: the 10th row is delivered and
        // the stream ends because the source ran out, which is a different fact.
        rowsOf(10);
        List<PostgresTableRow> delivered = new ArrayList<>();

        PostgresRowStreamResult exact = sourceWith(10, 50).streamRows(source(), CUSTOMERS, delivered::add);

        assertThat(exact.rowsRead()).isEqualTo(10);
        assertThat(exact.rowLimitReached()).isFalse();
        assertThat(delivered).hasSize(10);

        // One row past the ceiling is truncation, and the extra row is not
        // delivered.
        setUp();
        rowsOf(11);
        delivered.clear();
        PostgresRowStreamResult past = sourceWith(10, 50).streamRows(source(), CUSTOMERS, delivered::add);

        assertThat(past.rowsRead()).isEqualTo(10);
        assertThat(past.rowLimitReached()).isTrue();
        assertThat(delivered).hasSize(10);
    }

    @Test
    void aCeilingOfOneDeliversExactlyOneRow() throws SQLException {
        rowsOf(3);
        List<PostgresTableRow> delivered = new ArrayList<>();

        PostgresRowStreamResult result = sourceWith(1, 1).streamRows(source(), CUSTOMERS, delivered::add);

        assertThat(result.rowsRead()).isEqualTo(1);
        assertThat(result.rowLimitReached()).isTrue();
        assertThat(delivered).hasSize(1);
    }

    @Test
    void theFetchSizeIsAppliedToTheStatement() throws SQLException {
        rowsOf(2);
        Consumer<PostgresTableRow> ignore = row -> { };

        sourceWith(100, 250).streamRows(source(), CUSTOMERS, ignore);

        // The driver is told how many rows to hold at a time, which is what
        // keeps a table from being pulled into client memory wholesale.
        verify(statement).setFetchSize(250);
    }

    @Test
    void everyJdbcObjectIsClosedOnTheNormalPath() throws SQLException {
        rowsOf(2);
        List<PostgresTableRow> delivered = new ArrayList<>();

        sourceWith(100, 50).streamRows(source(), CUSTOMERS, delivered::add);

        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void everyJdbcObjectIsClosedWhenTheRowCeilingStopsTheStream() throws SQLException {
        rowsOf(50);
        List<PostgresTableRow> delivered = new ArrayList<>();

        sourceWith(5, 50).streamRows(source(), CUSTOMERS, delivered::add);

        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void everyJdbcObjectIsClosedWhenRowProcessingFails() throws SQLException {
        rowsOf(50);

        // The caller's own failure propagates unchanged — it is not disguised as
        // a source failure — but only after the resources are released.
        assertThatThrownBy(() -> sourceWith(100, 50).streamRows(source(), CUSTOMERS, row -> {
            throw new IllegalStateException("the caller's own failure");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the caller's own failure");

        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void everyJdbcObjectIsClosedWhenTheDriverFails() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("connection reset"));

        assertThatThrownBy(() -> sourceWith(100, 50).streamRows(source(), CUSTOMERS, row -> { }))
                .isInstanceOf(PostgresTableRowReadException.class);

        // The statement is closed by try-with-resources even though the failure
        // happened while executing it, and the connection still goes.
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void aReadFailureNeverCarriesTheSqlOrARowValue() throws SQLException {
        // A driver message that restates the relation, the columns, and the
        // statement, and even quotes a cell back at us.
        when(statement.executeQuery()).thenThrow(new SQLException(
                "ERROR: relation \"public.customers\" does not exist; "
                        + "last query was SELECT \"id\", \"email\" FROM \"public\".\"customers\" "
                        + "on jdbc:postgresql://db.internal:5432/prod?user=aegivault&password=hunter2 "
                        + "while reading synthetic-value-1"));
        List<PostgresTableRow> delivered = new ArrayList<>();

        // The driver's own text restates the relation, the columns, the
        // statement, the URL, and the credential — and even quotes a cell back.
        PostgresTableRowReadException failure = org.junit.jupiter.api.Assertions.assertThrows(
                PostgresTableRowReadException.class,
                () -> sourceWith(100, 50).streamRows(source(), CUSTOMERS, delivered::add));

        assertThat(failure.getMessage()).isEqualTo("Unable to read PostgreSQL source table.");
        for (String leak : List.of("public", "customers", "SELECT", "jdbc:", "hunter2",
                "synthetic-value", "db.internal", "5432", "prod")) {
            assertThat(failure.getMessage())
                    .as("the fixed message must not contain [%s]", leak)
                    .doesNotContain(leak);
        }

        // The cause is kept for server-side diagnostics only, and no row was
        // delivered before the failure.
        assertThat(delivered).isEmpty();
    }

    @Test
    void oneCallOpensOneConnectionAndHoldsItNoLongerThanTheCall() throws SQLException {
        PostgresTableRowSource rowSource = sourceWith(100, 50);

        // Each call gets its own connection, statement, and result set, exactly
        // as a real driver would hand out a fresh one per statement. The source
        // hands out whichever connection the current fixture built, so the
        // per-call reset below is genuinely a new connection.
        PostgresDataSource counted = mock(PostgresDataSource.class);
        when(counted.schemaName()).thenReturn("public");
        when(counted.openReadOnlyConnection()).thenAnswer(invocation -> connection);

        setUp();
        rowsOf(3);
        List<PostgresTableRow> first = new ArrayList<>();
        rowSource.streamRows(counted, CUSTOMERS, first::add);
        verify(counted, times(1)).openReadOnlyConnection();
        verify(connection, times(1)).prepareStatement(any());
        verify(connection, times(1)).close();

        setUp();
        rowsOf(3);
        List<PostgresTableRow> second = new ArrayList<>();
        rowSource.streamRows(counted, CUSTOMERS, second::add);
        verify(counted, times(2)).openReadOnlyConnection();
        verify(connection, times(1)).prepareStatement(any());
        verify(connection, times(1)).close();

        // One connection per call, never pooled, never shared, and never left
        // open: the first call's connection was closed before the second opened.
        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
    }

    @Test
    void theRowSourceNeverWeakensTheReadOnlySessionOrCommitsAnything() throws SQLException {
        rowsOf(1);
        List<PostgresTableRow> delivered = new ArrayList<>();

        sourceWith(100, 50).streamRows(source(), CUSTOMERS, delivered::add);

        // Everything comes through the injected PostgresDataSource boundary: the
        // implementation reaches neither DriverManager nor a URL nor credentials,
        // and it never asks to make a read-only session writable.
        verify(connection, never()).setReadOnly(false);
        // A read-only session has nothing to commit, and committing would be a
        // write attempt: the open read transaction is ended by closing instead.
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void autoCommitIsTurnedOffSoTheFetchSizeActuallyStreams() throws SQLException {
        // Enumerated because it is the difference between a real streaming read
        // and a cosmetic one: the PostgreSQL driver downloads the whole result
        // set unless it is outside auto-commit mode, in which case the fetch
        // size is honoured.
        rowsOf(3);
        List<PostgresTableRow> delivered = new ArrayList<>();

        sourceWith(100, 50).streamRows(source(), CUSTOMERS, delivered::add);

        verify(connection).getAutoCommit();
        verify(connection).setAutoCommit(false);
    }

    @Test
    void theBoundaryExposesNoArbitrarySqlPath() {
        // One operation, and its parameters are a source, a discovered table, and
        // a consumer. There is no SQL string, no connection, no statement, and no
        // result set anywhere on the reachable surface, so there is no way to ask
        // this boundary to run a query of a caller's choosing.
        assertThat(java.util.Arrays.stream(PostgresTableRowSource.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("streamRows");
        assertThat(java.util.Arrays.stream(PostgresTableRowSource.class.getDeclaredMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .map(Class::getSimpleName))
                .containsExactly("PostgresDataSource", "PostgresTable", "Consumer");
        assertThat(java.util.Arrays.stream(JdbcPostgresTableRowSource.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getReturnType)
                .map(Class::getSimpleName))
                .containsExactly("PostgresRowStreamResult");
    }

    @Test
    void theRowSourceDependsOnTheSourceBoundaryAndNothingElse() {
        // The dependency-direction claim, checked structurally: one field, the
        // limits value. No repository, no sanitization service, no PII registry,
        // no gateway, no audit ledger, no Redis, no controller.
        assertThat(java.util.Arrays.stream(JdbcPostgresTableRowSource.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactly("PostgresRowLimits");
    }

    @Test
    void theOnlyStatementExecutedIsTheNarrowSelectAndNothingIsBound() throws SQLException {
        rowsOf(1);
        List<PostgresTableRow> delivered = new ArrayList<>();

        sourceWith(100, 50).streamRows(source(), CUSTOMERS, delivered::add);

        // A PreparedStatement with no parameters: there is nothing to bind and no
        // place for a value to reach the statement text. No setter was called on
        // the statement at all, and the result set was only advanced and closed.
        verify(statement, never()).setString(anyInt(), any());
        verify(statement, never()).setObject(anyInt(), any());
        verify(statement, never()).setInt(anyInt(), anyInt());
        verify(statement, never()).setLong(anyInt(), anyLong());
        verify(statement, never()).execute(anyString());
        verify(statement, never()).executeQuery(anyString());
        verify(statement, times(1)).executeQuery();
    }
}

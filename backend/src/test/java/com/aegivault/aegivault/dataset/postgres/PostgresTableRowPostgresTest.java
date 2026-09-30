package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Row streaming against a real PostgreSQL server: one real base table, created
 * and dropped by this test, streamed through the real
 * {@link JdbcPostgresTableRowSource} and the real PostgreSQL driver.
 *
 * <p><strong>No new Spring context and no new server.</strong> The annotations
 * are exactly those of {@link PostgresSchemaDiscoveryPostgresTest} plus
 * {@code PER_CLASS} lifecycle (needed only so {@code @BeforeAll} can reach the
 * injected datasource), so this reuses the already-cached application context
 * and its single pooled datasource — the repository sits close to PostgreSQL's
 * {@code max_connections} ceiling, so a second context or a second database is
 * not an option. The application database is the test source, and the fixture is
 * written with direct JDBC against that pooled datasource rather than through
 * the row source, which keeps the test honest: the seeding code has nothing to
 * do with the code under test.
 *
 * <p><strong>The fixture is synthetic and obviously so.</strong> It holds
 * generic placeholders ({@code synthetic-label-N}, {@code .invalid} addresses,
 * {@code 555-01xx} numbers) that resemble no real record, and it is dropped in
 * {@link #dropFixture()}. Nothing here creates a production-like sensitive
 * dataset, and no value is logged: assertions compare values structurally and
 * never print them.
 *
 * <p>The streaming path itself writes nothing — it holds the source's read-only
 * session — and {@link #aReadOnlySourceSessionStillRejectsWrites()} proves the
 * server refuses a write on that connection.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTableRowPostgresTest {

    /** Fixture name: plainly a test artefact, not an application table. */
    private static final String FIXTURE = "aegivault_row_stream_fixture";

    /** A second schema holding a same-named table, to catch a cross-schema read. */
    private static final String OTHER_SCHEMA = "aegivault_row_stream_other";

    private static final int WIDE_ROW_COUNT = 5;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    @BeforeAll
    void createFixture() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + FIXTURE);
            statement.execute("DROP SCHEMA IF EXISTS " + OTHER_SCHEMA + " CASCADE");
            statement.execute("""
                    CREATE TABLE aegivault_row_stream_fixture (
                        id          integer     NOT NULL,
                        label       text        NOT NULL,
                        contact     varchar(120),
                        amount      numeric(12,2),
                        active      boolean     NOT NULL,
                        score       double precision,
                        payload     jsonb,
                        created_on  date,
                        reference   uuid
                    )""");
            // A wider, emptier table for the limit and empty-result cases.
            statement.execute("""
                    CREATE TABLE aegivault_row_stream_wide (
                        id     integer NOT NULL,
                        marker text    NOT NULL
                    )""");
            statement.execute("CREATE SCHEMA " + OTHER_SCHEMA);
            statement.execute("CREATE TABLE " + OTHER_SCHEMA + "." + FIXTURE
                    + " (id integer NOT NULL, marker text)");
            statement.execute("INSERT INTO " + OTHER_SCHEMA + "." + FIXTURE + " VALUES (1, 'other-schema-row')");
        }
    }

    @AfterAll
    void dropFixture() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS aegivault_row_stream_wide");
            statement.execute("DROP TABLE IF EXISTS " + FIXTURE);
            statement.execute("DROP SCHEMA IF EXISTS " + OTHER_SCHEMA + " CASCADE");
        } catch (SQLException ignored) {
            // Best effort: the fixture is a test artefact with an unmistakable
            // name, and a failed clean-up must not fail a green suite.
        }
    }

    /**
     * The application database as a PostgreSQL source, reaching it through the
     * pooled connection it already owns.
     *
     * <p>The connection is handed out read-only, exactly as
     * {@link DriverManagerPostgresDataSource} does, so the streaming path is
     * exercised against a genuine read-only PostgreSQL session.
     */
    private PostgresDataSource applicationSource(String schema) {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return schema;
            }

            @Override
            public Connection openReadOnlyConnection() {
                try {
                    Connection connection = dataSource.getConnection();
                    connection.setReadOnly(true);
                    return connection;
                } catch (SQLException ex) {
                    throw new PostgresSourceConnectionException(ex);
                }
            }
        };
    }

    private JdbcPostgresTableRowSource rowSource(int maxRows, int fetchSize) {
        return new JdbcPostgresTableRowSource(new PostgresRowLimits(maxRows, fetchSize));
    }

    /**
     * Discovers the fixture through the real metadata path, so the table the row
     * source is given is genuinely one discovery produced rather than a
     * hand-written stand-in.
     */
    private PostgresTable discoverFixture(String tableName) {
        return discoverIn("public", tableName);
    }

    /** Discovers a table in one specific schema, through the real metadata path. */
    private PostgresTable discoverIn(String schema, String tableName) {
        return discovery.discover(applicationSource(schema)).table(tableName).orElseThrow();
    }

    /** Truncates the fixture and seeds {@code count} obviously synthetic rows. */
    private void seedWideTable(int count) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE aegivault_row_stream_wide");
            for (int index = 1; index <= count; index++) {
                statement.execute("INSERT INTO aegivault_row_stream_wide VALUES ("
                        + index + ", 'synthetic-marker-" + index + "')");
            }
        }
    }

    /** Seeds the wide fixture with NULLs, empty strings, and common types. */
    private void seedTypedRow() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + FIXTURE);
            statement.execute("INSERT INTO " + FIXTURE + " VALUES ("
                    + "1, 'synthetic-label-1', 'first.contact@example.invalid', 12.50, true, 1.5, "
                    + "'{\"kind\":\"synthetic\"}', DATE '2024-01-15', "
                    + "'00000000-0000-0000-0000-000000000001')");
            // A second row whose nullable columns are all NULL, which is the case
            // a naive string-based reader would silently turn into "" or "null".
            statement.execute("INSERT INTO " + FIXTURE + " VALUES ("
                    + "2, 'synthetic-label-2', NULL, NULL, false, NULL, NULL, NULL, NULL)");
        }
    }

    @Test
    void oneRealTableIsStreamedCorrectlyWithItsColumnOrderPreserved() throws SQLException {
        seedTypedRow();
        PostgresTable fixture = discoverFixture(FIXTURE);
        List<PostgresTableRow> rows = new ArrayList<>();

        PostgresRowStreamResult result = rowSource(100, 10)
                .streamRows(applicationSource("public"), fixture, rows::add);

        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(result.columnsRead()).isEqualTo(9);
        assertThat(result.rowLimitReached()).isFalse();
        assertThat(rows).hasSize(2);

        // The discovered ORDINAL_POSITION order is preserved end to end: the
        // columns each row carries are exactly the discovered ones, in order.
        assertThat(rows.get(0).columns()).isEqualTo(fixture.columns());
        assertThat(rows.get(0).columns()).extracting(PostgresColumn::name)
                .containsExactly("id", "label", "contact", "amount", "active",
                        "score", "payload", "created_on", "reference");
        assertThat(rows.get(0).columns()).extracting(PostgresColumn::ordinalPosition)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);

        // Values line up with their columns by position.
        assertThat(rows.get(0).value("id")).isEqualTo(1);
        assertThat(rows.get(0).value("label")).isEqualTo("synthetic-label-1");
        assertThat(rows.get(0).value("active")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void rowOrderFollowsTheDatabaseWithoutClaimingAnyOrder() throws SQLException {
        seedWideTable(WIDE_ROW_COUNT);
        PostgresTable wide = discoverFixture("aegivault_row_stream_wide");
        List<Integer> ids = new ArrayList<>();
        List<String> paired = new ArrayList<>();

        rowSource(100, 2).streamRows(applicationSource("public"), wide, row -> {
            ids.add((Integer) row.valueAt(0));
            paired.add(row.valueAt(0) + ":" + row.valueAt(1));
        });

        // No ORDER BY is issued, so the contract is only that the rows arrive in
        // the database's own result order — never that they are sorted. Both
        // facts are asserted: every id arrived exactly once, and each row kept
        // its own id/marker pairing, which is what a torn positional read breaks.
        assertThat(ids).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        for (int id = 1; id <= WIDE_ROW_COUNT; id++) {
            assertThat(paired).contains(id + ":synthetic-marker-" + id);
        }
    }

    @Test
    void nullColumnValuesArePreservedAsNull() throws SQLException {
        seedTypedRow();
        List<PostgresTableRow> rows = new ArrayList<>();

        rowSource(100, 10)
                .streamRows(applicationSource("public"), discoverFixture(FIXTURE), rows::add);

        PostgresTableRow nulls = rows.stream()
                .filter(row -> Integer.valueOf(2).equals(row.value("id")))
                .findFirst()
                .orElseThrow();
        // A SQL NULL stays null rather than becoming "", "null", or a missing
        // element: the value is present and it is null.
        for (String column : List.of("contact", "amount", "score", "payload", "created_on", "reference")) {
            assertThat(nulls.value(column)).as("%s must read as null", column).isNull();
        }
        // The non-null columns of that same row are unaffected.
        assertThat(nulls.value("label")).isEqualTo("synthetic-label-2");
        assertThat(nulls.value("active")).isEqualTo(Boolean.FALSE);
        assertThat(nulls.values()).hasSize(9);
    }

    @Test
    void commonPostgresTypesKeepTheirOwnJdbcTypes() throws SQLException {
        seedTypedRow();
        List<PostgresTableRow> rows = new ArrayList<>();

        rowSource(100, 10)
                .streamRows(applicationSource("public"), discoverFixture(FIXTURE), rows::add);

        PostgresTableRow first = rows.get(0);
        // The driver's own mapping is passed through unchanged, which is the
        // point: int4 -> Integer, text/varchar -> String, bool -> Boolean,
        // numeric -> BigDecimal, float8 -> Double, date -> java.sql.Date,
        // uuid -> UUID; and jsonb arrives as the driver's own type rather than
        // being guessed at or coerced to text by this layer.
        assertThat(first.value("id")).isInstanceOf(Integer.class);
        assertThat(first.value("label")).isInstanceOf(String.class);
        assertThat(first.value("contact")).isInstanceOf(String.class);
        assertThat(first.value("amount")).isInstanceOf(java.math.BigDecimal.class);
        assertThat(first.value("amount")).isEqualTo(new java.math.BigDecimal("12.50"));
        assertThat(first.value("active")).isInstanceOf(Boolean.class);
        assertThat(first.value("score")).isInstanceOf(Double.class);
        assertThat(first.value("created_on")).isInstanceOf(java.sql.Date.class);
        assertThat(first.value("created_on").toString()).isEqualTo("2024-01-15");
        assertThat(first.value("reference")).isInstanceOf(UUID.class);
        assertThat(first.value("reference"))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(first.value("payload")).isNotNull();
        assertThat(first.value("payload").toString()).contains("synthetic");
    }

    @Test
    void anEmptyTableProducesZeroRows() throws SQLException {
        seedWideTable(0);
        List<PostgresTableRow> rows = new ArrayList<>();

        PostgresRowStreamResult result = rowSource(100, 10)
                .streamRows(applicationSource("public"),
                        discoverFixture("aegivault_row_stream_wide"), rows::add);

        assertThat(result.rowsRead()).isZero();
        assertThat(result.columnsRead()).isEqualTo(2);
        assertThat(result.rowLimitReached()).isFalse();
        assertThat(rows).isEmpty();
    }

    @Test
    void theRowLimitIsEnforcedAgainstARealTableAndReported() throws SQLException {
        seedWideTable(WIDE_ROW_COUNT);
        List<PostgresTableRow> rows = new ArrayList<>();

        PostgresRowStreamResult result = rowSource(2, 10)
                .streamRows(applicationSource("public"),
                        discoverFixture("aegivault_row_stream_wide"), rows::add);

        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(rows).hasSize(2);
        // Truncation is exposed, never silent: the caller can tell it received a
        // prefix of a five-row table rather than the table.
        assertThat(result.rowLimitReached()).isTrue();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void theRowLimitBoundaryIsExactAgainstARealTable() throws SQLException {
        seedWideTable(WIDE_ROW_COUNT);
        PostgresTable wide = discoverFixture("aegivault_row_stream_wide");

        // Exactly at the ceiling: the last row is delivered and the stream ends
        // because the source ran out, which is not truncation.
        List<PostgresTableRow> atLimit = new ArrayList<>();
        PostgresRowStreamResult exact = rowSource(WIDE_ROW_COUNT, 10)
                .streamRows(applicationSource("public"), wide, atLimit::add);
        assertThat(exact.rowsRead()).isEqualTo(WIDE_ROW_COUNT);
        assertThat(atLimit).hasSize(WIDE_ROW_COUNT);
        assertThat(exact.rowLimitReached()).isFalse();

        // One row past the ceiling: truncation, and the extra row is not read.
        List<PostgresTableRow> pastLimit = new ArrayList<>();
        PostgresRowStreamResult past = rowSource(WIDE_ROW_COUNT - 1, 10)
                .streamRows(applicationSource("public"), wide, pastLimit::add);
        assertThat(past.rowsRead()).isEqualTo(WIDE_ROW_COUNT - 1);
        assertThat(pastLimit).hasSize(WIDE_ROW_COUNT - 1);
        assertThat(past.rowLimitReached()).isTrue();
    }

    @Test
    void aSmallFetchSizeStillReadsEveryRowCorrectly() throws SQLException {
        // A fetch size of 1 is the worst case for the driver: the table arrives
        // one row at a time, and the result must be identical to a single-batch
        // read. This is the real proof that the fetch size is a transport
        // concern and not a row-count limit.
        seedWideTable(WIDE_ROW_COUNT);
        PostgresTable wide = discoverFixture("aegivault_row_stream_wide");
        List<Integer> oneAtATime = new ArrayList<>();

        PostgresRowStreamResult result = rowSource(WIDE_ROW_COUNT, 1)
                .streamRows(applicationSource("public"), wide,
                        row -> oneAtATime.add((Integer) row.valueAt(0)));

        assertThat(result.rowsRead()).isEqualTo(WIDE_ROW_COUNT);
        assertThat(result.rowLimitReached()).isFalse();
        assertThat(oneAtATime).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void theSourceSessionIsReadOnlyAndTheServerRejectsAWriteOnIt() throws SQLException {
        // The guarantee is enforced by the server, not by trusting the client: on
        // the same read-only session the row source uses, a write fails with
        // PostgreSQL's read-only-transaction error, and the table is unchanged.
        //
        // Auto-commit is turned off first, exactly as the row source does. That
        // is not incidental: PostgreSQL enforces read-only per transaction, so
        // in auto-commit mode each statement is its own transaction and the
        // read-only session is not actually applied. Running the read inside one
        // transaction is what makes the guarantee real.
        seedTypedRow();
        long before = countFixtureRows();

        try (Connection connection = applicationSource("public").openReadOnlyConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO " + FIXTURE + " (id, label, active) VALUES (99, 'x', true)"))
                        .isInstanceOf(SQLException.class)
                        // 25006 read_only_sql_transaction: the server refused.
                        .matches(failure -> "25006".equals(
                                ((SQLException) failure).getSQLState()));
            }
        }

        assertThat(countFixtureRows()).isEqualTo(before);
    }

    private long countFixtureRows() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + FIXTURE);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void theReadIsIsolatedToItsOwnSchema() {
        // The same table name exists in two schemas, with different shapes. Each
        // read must reach the table its own source names: a read that fell back
        // to another schema, or leaked across, would not match the metadata it
        // was given.
        List<PostgresTableRow> fromPublic = new ArrayList<>();
        rowSource(100, 10).streamRows(applicationSource("public"),
                discoverIn("public", FIXTURE), fromPublic::add);
        assertThat(fromPublic).hasSize(2);
        assertThat(fromPublic.get(0).columns()).hasSize(9);

        List<PostgresTableRow> fromOther = new ArrayList<>();
        rowSource(100, 10).streamRows(applicationSource(OTHER_SCHEMA),
                discoverIn(OTHER_SCHEMA, FIXTURE), fromOther::add);
        assertThat(fromOther).hasSize(1);
        assertThat(fromOther.get(0).columns()).hasSize(2);
        assertThat(fromOther.get(0).value("marker")).isEqualTo("other-schema-row");
    }

    @Test
    void anUnknownTableProducesTheSafeReadException() {
        // A perfectly valid identifier that is not a table: the failure arrives
        // as the fixed safe message, and nothing about the SQL, the schema, or
        // the driver's own text reaches the caller.
        PostgresTable absent = new PostgresTable("aegivault_absent_table",
                List.of(new PostgresColumn("id", 1, "int4")));

        assertThatThrownBy(() -> rowSource(100, 10)
                .streamRows(applicationSource("public"), absent, row -> { }))
                .isInstanceOf(PostgresTableRowReadException.class)
                .hasMessage("Unable to read PostgreSQL source table.");
    }

    @Test
    void noArbitrarySqlPathExistsOnTheLiveBoundary() {
        // Enumerated against the abstraction the application would inject: the
        // reachable surface is one streaming operation over a discovered table,
        // and no method returns a JDBC object or accepts SQL text.
        assertThat(java.util.Arrays.stream(PostgresTableRowSource.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("streamRows");
        assertThat(java.util.Arrays.stream(PostgresTableRowSource.class.getDeclaredMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .map(Class::getSimpleName))
                .containsExactly("PostgresDataSource", "PostgresTable", "Consumer");
    }
}

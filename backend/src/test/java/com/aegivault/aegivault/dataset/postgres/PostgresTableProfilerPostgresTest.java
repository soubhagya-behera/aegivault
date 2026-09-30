package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Profiling a real PostgreSQL table with the real row source and the real PII
 * engine: one synthetic table, created and dropped by this test, profiled
 * through the real {@link PostgresTableProfiler}.
 *
 * <p><strong>No new Spring context and no new pool.</strong> The annotations are
 * those of {@link PostgresSchemaDiscoveryPostgresTest} plus {@code PER_CLASS}
 * lifecycle, so the already-cached application context is reused — the repository
 * deliberately keeps the suite's connection footprint small. The table is written
 * and dropped with direct JDBC against the pooled datasource, never through the
 * code under test, and the table handed to the profiler is genuinely the one
 * {@link PostgresSchemaDiscoveryService} discovered.
 *
 * <p><strong>Everything in the fixture is obviously synthetic</strong> and
 * resembles no real record: {@code .invalid} addresses, {@code 555-01xx} numbers,
 * and a well-known test card number. No value is logged: assertions compare
 * counts and type names, never print a value.
 *
 * <p><strong>Nothing is persisted or exposed.</strong> The profile is asserted in
 * memory and discarded; this test calls no save method, no endpoint, and no
 * sanitizer.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTableProfilerPostgresTest {

    /** Fixture name: plainly a test artefact, not an application table. */
    private static final String FIXTURE = "aegivault_profile_fixture";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000f11e");

    /** Obviously synthetic test values, used only inside this fixture. */
    private static final String EMAIL_A = "first.synthetic@example.invalid";

    private static final String EMAIL_B = "second.synthetic@example.invalid";

    private static final String EMAIL_C = "third.synthetic@example.invalid";

    /**
     * Obviously synthetic phone values, in the shape the existing PhoneDetector
     * recognises (ten digits starting 6-9): sequential placeholders, not
     * anyone's number.
     */
    private static final String PHONE_A = "9876543210";

    private static final String PHONE_B = "9876543211";

    private static final String CARD = "4111111111111111";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    @BeforeAll
    void createFixture() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + FIXTURE);
            statement.execute("""
                    CREATE TABLE aegivault_profile_fixture (
                        id      integer     NOT NULL,
                        email   varchar(120),
                        phone   varchar(40),
                        card    varchar(40),
                        note    text
                    )""");
        }
    }

    @AfterAll
    void dropFixture() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + FIXTURE);
        } catch (SQLException ignored) {
            // Best effort: the fixture has an unmistakable test-artefact name and
            // a failed clean-up must not fail a green suite.
        }
    }

    /** The application database as a read-only PostgreSQL source. */
    private PostgresDataSource applicationSource() {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return "public";
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

    /** The real engine with the real detectors, as the application wires them. */
    private PiiColumnProfiler columnProfiler() {
        return new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector(), new CreditCardDetector())));
    }

    private PostgresTableProfiler profiler(int maxRows) {
        return new PostgresTableProfiler(
                new JdbcPostgresTableRowSource(new PostgresRowLimits(maxRows, 50)),
                columnProfiler());
    }

    /** Discovers the fixture through the real metadata path. */
    private PostgresTable fixture() {
        return discovery.discover(applicationSource()).table(FIXTURE).orElseThrow();
    }

    private void seed(String... rows) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + FIXTURE);
            for (String row : rows) {
                statement.execute("INSERT INTO " + FIXTURE + " VALUES (" + row + ")");
            }
        }
    }

    private static ColumnProfile column(DatasetProfile profile, String name) {
        return profile.columns().stream()
                .filter(candidate -> candidate.columnName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rowsStreamIntoTheExistingProfilerAndExistingDetectorsSeeThem() throws SQLException {
        seed("1, '" + EMAIL_A + "', '" + PHONE_A + "', '" + CARD + "', 'clean note'",
                "2, '" + EMAIL_B + "', '" + PHONE_B + "', NULL, NULL");

        DatasetProfile profile = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        // Detection is the existing engine's: this milestone contributes only the
        // ability to read values out of a table.
        assertThat(column(profile, "email").detectedTypes()).containsExactly(PiiType.EMAIL);
        assertThat(column(profile, "email").detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(column(profile, "phone").detectedTypes()).containsExactly(PiiType.PHONE);
        assertThat(column(profile, "phone").detectionCounts()).containsEntry(PiiType.PHONE, 2);
        assertThat(column(profile, "card").detectedTypes()).containsExactly(PiiType.CREDIT_CARD);
        assertThat(column(profile, "card").detectionCounts()).containsEntry(PiiType.CREDIT_CARD, 1);
    }

    @Test
    void cleanValuesProduceNoPiiAndValuesStayInTheirOwnColumn() throws SQLException {
        seed("1, NULL, NULL, NULL, 'public note'",
                "2, NULL, NULL, NULL, 'another note'");

        DatasetProfile profile = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        // Clean and NULL columns report nothing at all: no value crossed sideways
        // into another column's profile.
        assertThat(column(profile, "note").detectedTypes()).isEmpty();
        assertThat(column(profile, "note").suppliedValueCount()).isEqualTo(2);
        assertThat(column(profile, "note").analyzableValueCount()).isEqualTo(2);
        assertThat(column(profile, "email").detectedTypes()).isEmpty();
        assertThat(column(profile, "id").detectedTypes()).isEmpty();
    }

    @Test
    void sqlNullIsPreservedAsNullAndNeverProfiledAsText() throws SQLException {
        seed("1, NULL, NULL, NULL, NULL");

        DatasetProfile profile = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        ColumnProfile email = column(profile, "email");
        // Supplied, because a row was read, but not analyzable: a SQL NULL is
        // absent data, not the string "null" that every detector would see.
        assertThat(email.suppliedValueCount()).isEqualTo(1);
        assertThat(email.analyzedValueCount()).isEqualTo(1);
        assertThat(email.analyzableValueCount()).isZero();
        assertThat(email.detectedTypes()).isEmpty();
        assertThat(email.detectionRates()).isEmpty();
    }

    @Test
    void anEmptyTableProducesAValidProfileWithNoObservations() throws SQLException {
        seed();

        DatasetProfile profile = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        // No rows is a normal outcome, never a failure, and the discovered
        // columns are still present with honest zero counts.
        assertThat(profile.columns()).hasSize(5);
        assertThat(profile.totalColumns()).isEqualTo(5);
        for (ColumnProfile candidate : profile.columns()) {
            assertThat(candidate.suppliedValueCount()).isZero();
            assertThat(candidate.analyzedValueCount()).isZero();
            assertThat(candidate.analyzableValueCount()).isZero();
            assertThat(candidate.detectedTypes()).isEmpty();
        }
    }

    @Test
    void profilingIsBoundedByTheRowStreamLimit() throws SQLException {
        seed("1, '" + EMAIL_A + "', NULL, NULL, NULL",
                "2, '" + EMAIL_B + "', NULL, NULL, NULL",
                "3, '" + EMAIL_C + "', NULL, NULL, NULL");

        // Only two of the three rows may be read, so only two may be profiled.
        DatasetProfile profile = profiler(2).profile(DATASET_ID, applicationSource(), fixture());

        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(2);
        assertThat(email.analyzableValueCount()).isEqualTo(2);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
    }

    @Test
    void columnsRemainInOrdinalOrder() throws SQLException {
        seed("1, '" + EMAIL_A + "', NULL, NULL, NULL");

        DatasetProfile profile = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        // The table's declared column order, preserved end to end.
        assertThat(profile.columns()).extracting(ColumnProfile::columnName)
                .containsExactly("id", "email", "phone", "card", "note");
    }

    @Test
    void multipleRowsAggregateCountsAndRatesCorrectly() throws SQLException {
        seed("1, '" + EMAIL_A + "', NULL, NULL, NULL",
                "2, '" + EMAIL_B + "', NULL, NULL, NULL",
                "3, 'clean-value', NULL, NULL, NULL",
                "4, NULL, NULL, NULL, NULL");

        DatasetProfile profile = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(4);
        assertThat(email.analyzedValueCount()).isEqualTo(4);
        // Three analyzable values: two emails and one clean string; the NULL is
        // supplied but excluded from the denominator, as the engine defines.
        assertThat(email.analyzableValueCount()).isEqualTo(3);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(email.detectionRates().get(PiiType.EMAIL)).isEqualTo(2.0 / 3.0);
    }

    @Test
    void repeatedProfilingOfTheSameTableProducesEquivalentProfiles() throws SQLException {
        seed("1, '" + EMAIL_A + "', '" + PHONE_A + "', NULL, NULL",
                "2, '" + EMAIL_B + "', NULL, NULL, NULL");

        DatasetProfile first = profiler(100).profile(DATASET_ID, applicationSource(), fixture());
        DatasetProfile second = profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        // Determinism end to end, from a real table through the real driver and
        // the real engine.
        assertThat(second).isEqualTo(first);
    }

    @Test
    void profilingLeavesTheSourceAndItsDataUntouched() throws SQLException {
        seed("1, '" + EMAIL_A + "', NULL, NULL, NULL");
        long before = countFixtureRows();

        profiler(100).profile(DATASET_ID, applicationSource(), fixture());

        // Profiling reads: it neither writes nor alters the source, and it
        // persists nothing of what it saw.
        assertThat(countFixtureRows()).isEqualTo(before);
    }

    private long countFixtureRows() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM " + FIXTURE);
                var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}

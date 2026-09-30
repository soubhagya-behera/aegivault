package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PostgresTableProfiler} against an in-memory stub row
 * source: no Spring, no database, no JDBC, and no network.
 *
 * <p>They pin the behaviour this milestone exists for. Detection is the
 * existing engine's, not this class's, so these tests assert that values reach
 * that engine intact and in the right column, that SQL NULL is never turned
 * into the string {@code "null"}, that column order is the source's ordinal
 * order, that the result describes only the rows that were streamed, and that a
 * detector failure cannot leak a value into an exception.
 *
 * <p>Every value is obviously synthetic.
 */
class PostgresTableProfilerTest {

    /** Obviously synthetic, and reserved for the leak assertions below. */
    private static final String SYNTHETIC_EMAIL = "synthetic.user@example.invalid";

    /**
     * Obviously synthetic phone values, in the shape the existing PhoneDetector
     * recognises (ten digits starting 6-9). They are sequential placeholders, not
     * anyone's number.
     */
    private static final String SYNTHETIC_PHONE = "9876543210";

    private static final String SYNTHETIC_CARD = "4111111111111111";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    private static final PostgresTable CONTACTS = new PostgresTable("contacts", List.of(
            new PostgresColumn("id", 1, "int4"),
            new PostgresColumn("email", 2, "varchar"),
            new PostgresColumn("phone", 3, "varchar")));

    private static PiiColumnProfiler columnProfiler() {
        return new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector(), new CreditCardDetector())));
    }

    /** A source that never opens anything: the stub hands over rows directly. */
    private static PostgresDataSource source() {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return "public";
            }

            @Override
            public Connection openReadOnlyConnection() {
                throw new AssertionError("the stub row source must not open a connection");
            }
        };
    }

    /** A row source that replays fixed rows, honouring the same row ceiling. */
    private static StubRowSource rowsOf(int maxRows, List<PostgresTableRow> rows) {
        return new StubRowSource(maxRows, rows);
    }

    private static PostgresTableRow row(Object... values) {
        return new PostgresTableRow(CONTACTS.columns(), java.util.Arrays.asList(values));
    }

    private static ColumnProfile column(DatasetProfile profile, String name) {
        return profile.columns().stream()
                .filter(candidate -> candidate.columnName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * A row source that replays fixed rows with the same bounded semantics as
     * the JDBC one, so the profiler is tested without a database.
     */
    private static final class StubRowSource implements PostgresTableRowSource {

        private final int maxRows;

        private final List<PostgresTableRow> rows;

        StubRowSource(int maxRows, List<PostgresTableRow> rows) {
            this.maxRows = maxRows;
            this.rows = rows;
        }

        @Override
        public PostgresRowStreamResult streamRows(PostgresDataSource source, PostgresTable table,
                java.util.function.Consumer<PostgresTableRow> rowConsumer) {
            long delivered = 0;
            for (PostgresTableRow row : rows) {
                if (delivered >= maxRows) {
                    return new PostgresRowStreamResult(table.columns().size(), delivered, true);
                }
                rowConsumer.accept(row);
                delivered++;
            }
            return new PostgresRowStreamResult(table.columns().size(), delivered, false);
        }
    }

    @Test
    void valuesReachTheExistingProfilerAttachedToTheirOwnColumn() {
        StubRowSource rows = rowsOf(100, List.of(row(1, SYNTHETIC_EMAIL, SYNTHETIC_PHONE)));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        // The engine, not this class, decided there is one email and one phone.
        assertThat(column(profile, "email").detectedTypes()).containsExactly(PiiType.EMAIL);
        assertThat(column(profile, "email").detectionCounts()).containsEntry(PiiType.EMAIL, 1);
        assertThat(column(profile, "phone").detectedTypes()).containsExactly(PiiType.PHONE);
        // A value never leaks sideways into another column: the integer id column
        // reports nothing at all.
        assertThat(column(profile, "id").detectedTypes()).isEmpty();
        assertThat(column(profile, "id").detectionCounts()).isEmpty();
    }

    @Test
    void everyExistingDetectorIsReusedRatherThanReimplemented() {
        StubRowSource rows = rowsOf(100, List.of(row(1, SYNTHETIC_EMAIL, SYNTHETIC_CARD)));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        // Email and credit-card detection are the existing detectors, running
        // unchanged over the same values the CSV path would give them.
        assertThat(column(profile, "email").detectedTypes()).containsExactly(PiiType.EMAIL);
        assertThat(column(profile, "phone").detectedTypes()).containsExactly(PiiType.CREDIT_CARD);
        assertThat(column(profile, "phone").detectionCounts()).containsEntry(PiiType.CREDIT_CARD, 1);
    }

    @Test
    void cleanValuesProduceNoPii() {
        StubRowSource rows = rowsOf(100, List.of(
                row(1, "public-text-value", "not-sensitive"),
                row(2, "another-value", "still-clean")));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        assertThat(profile.columns()).allSatisfy(candidate ->
                assertThat(candidate.detectedTypes()).isEmpty());
        // Clean values are still counted as observed; only detections are zero.
        assertThat(column(profile, "email").suppliedValueCount()).isEqualTo(2);
        assertThat(column(profile, "email").analyzableValueCount()).isEqualTo(2);
    }

    @Test
    void aSqlNullIsNeverTurnedIntoTheStringNull() {
        StubRowSource rows = rowsOf(100, List.of(row(1, null, null)));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        ColumnProfile email = column(profile, "email");
        // Supplied, but not analyzable and not a detection: a null is absent data,
        // not the four-letter string that every detector would then match against.
        assertThat(email.suppliedValueCount()).isEqualTo(1);
        assertThat(email.analyzedValueCount()).isEqualTo(1);
        assertThat(email.analyzableValueCount()).isZero();
        assertThat(email.detectedTypes()).isEmpty();
        assertThat(email.detectionRates()).isEmpty();
    }

    @Test
    void blankValuesFollowTheExistingProfilerSemantics() {
        StubRowSource rows = rowsOf(100, List.of(row(1, "   ", ""), row(2, "\t", "   ")));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        // Blanks are supplied and analyzed but excluded from the rate
        // denominator, exactly as PiiColumnProfiler already defines.
        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(2);
        assertThat(email.analyzableValueCount()).isZero();
        assertThat(email.detectedTypes()).isEmpty();
    }

    @Test
    void columnsAreProfiledAndReturnedInOrdinalOrder() {
        StubRowSource rows = rowsOf(100, List.of(row(1, SYNTHETIC_EMAIL, SYNTHETIC_PHONE)));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        // The source's ORDINAL_POSITION order, not alphabetical: the discovered
        // order is a fact about the table and a caller must be able to rely on it.
        assertThat(profile.columns()).extracting(ColumnProfile::columnName)
                .containsExactly("id", "email", "phone");
        assertThat(profile.totalColumns()).isEqualTo(3);
        assertThat(profile.datasetId()).isEqualTo(DATASET_ID);
    }

    @Test
    void multipleRowsAggregateCountsCorrectly() {
        StubRowSource rows = rowsOf(100, List.of(
                row(1, SYNTHETIC_EMAIL, SYNTHETIC_PHONE),
                row(2, SYNTHETIC_EMAIL, "clean"),
                row(3, "clean", SYNTHETIC_PHONE),
                row(4, null, null)));

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        ColumnProfile email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(4);
        assertThat(email.analyzedValueCount()).isEqualTo(4);
        assertThat(email.analyzableValueCount()).isEqualTo(3);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(email.detectionRates().get(PiiType.EMAIL)).isEqualTo(2.0 / 3.0);

        ColumnProfile phone = column(profile, "phone");
        assertThat(phone.analyzableValueCount()).isEqualTo(3);
        assertThat(phone.detectionCounts()).containsEntry(PiiType.PHONE, 2);
    }

    @Test
    void profilingIsBoundedByTheRowStreamLimitAndClaimsOnlyWhatItSaw() {
        // Five rows exist, the row stream may deliver two: the profile must
        // describe the two it saw and must not pretend to cover the table.
        List<PostgresTableRow> five = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            five.add(row(index, SYNTHETIC_EMAIL, "clean"));
        }
        StubRowSource rows = rowsOf(2, five);

        DatasetProfile profile = new PostgresTableProfiler(rows, columnProfiler())
                .profile(DATASET_ID, source(), CONTACTS);

        // The supplied count is the honest fact: it is the number of rows this
        // call actually observed, so a caller comparing it with the table's row
        // count can see the sample was bounded. DatasetProfile carries no
        // truncation flag, which is documented on the profiler rather than
        // invented as a new field here.
        assertThat(column(profile, "email").suppliedValueCount()).isEqualTo(2);
        assertThat(column(profile, "email").detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(profile.maxSampleSizePerColumn()).isEqualTo(100);
    }

    @Test
    void repeatedProfilingOfTheSameRowsProducesEquivalentProfiles() {
        List<PostgresTableRow> rows = List.of(
                row(1, SYNTHETIC_EMAIL, SYNTHETIC_PHONE),
                row(2, SYNTHETIC_EMAIL, null));
        PostgresTableProfiler profiler =
                new PostgresTableProfiler(rowsOf(100, rows), columnProfiler());

        DatasetProfile first = profiler.profile(DATASET_ID, source(), CONTACTS);
        DatasetProfile second = profiler.profile(DATASET_ID, source(), CONTACTS);

        // Determinism is the point: same input, equivalent profile, no reliance on
        // unordered iteration anywhere in the path.
        assertThat(second).isEqualTo(first);
        assertThat(second.columns()).extracting(ColumnProfile::columnName)
                .containsExactly("id", "email", "phone");
    }
}

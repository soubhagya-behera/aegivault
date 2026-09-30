package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the row-streaming model: the row is immutable, the bounds are
 * ranges rather than suggestions, and — the point that matters most here, now
 * that real values can exist in memory — the result and the row cannot leak a
 * cell into a message.
 *
 * <p>No Spring, no database, and no connection. Synthetic values only, chosen
 * to be obviously non-sensitive so a test failure cannot read like a real
 * record.
 */
class PostgresTableRowModelTest {

    private static final List<PostgresColumn> COLUMNS = List.of(
            new PostgresColumn("id", 1, "int4"),
            new PostgresColumn("label", 2, "text"));

    /** Obviously synthetic, obviously not a real record of anything. */
    private static final String SYNTHETIC_LABEL = "synthetic-placeholder";

    @Test
    void aRowCarriesItsColumnMetadataAndOrderedValues() {
        PostgresTableRow row = new PostgresTableRow(COLUMNS, List.of(7, SYNTHETIC_LABEL));

        assertThat(row.columnCount()).isEqualTo(2);
        assertThat(row.columns()).containsExactlyElementsOf(COLUMNS);
        assertThat(row.values()).containsExactly(7, SYNTHETIC_LABEL);
        assertThat(row.column(0)).isEqualTo(new PostgresColumn("id", 1, "int4"));
        assertThat(row.valueAt(1)).isEqualTo(SYNTHETIC_LABEL);
        assertThat(row.value("id")).isEqualTo(7);
        assertThat(row.value("label")).isEqualTo(SYNTHETIC_LABEL);
    }

    @Test
    void aRowIsImmutableInBothDirections() {
        List<PostgresColumn> columns = new ArrayList<>(COLUMNS);
        List<Object> values = new ArrayList<>(List.of(1, SYNTHETIC_LABEL));
        PostgresTableRow row = new PostgresTableRow(columns, values);

        // The caller's lists are copied, so later mutation cannot reach the row.
        columns.add(new PostgresColumn("late", 3, "text"));
        values.add("late value");
        assertThat(row.columnCount()).isEqualTo(2);

        // And the row's own lists reject mutation, so a consumer that tries to
        // edit a row it was handed cannot do it.
        assertThatThrownBy(() -> row.values().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.columns().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRowMustBeAsWideAsItsColumnMetadata() {
        assertThatThrownBy(() -> new PostgresTableRow(null, List.of(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostgresTableRow(COLUMNS, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostgresTableRow(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresTableRow(COLUMNS, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresTableRow(COLUMNS, List.of(1, "a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSqlNullIsAValueLikeAnyOther() {
        // The point of using an unmodifiable copy rather than List.copyOf: a SQL
        // NULL is a real value and must survive row construction intact.
        PostgresTableRow row = new PostgresTableRow(COLUMNS, java.util.Arrays.asList(null, SYNTHETIC_LABEL));

        assertThat(row.valueAt(0)).isNull();
        assertThat(row.value("id")).isNull();
        assertThat(row.columnCount()).isEqualTo(2);
    }

    @Test
    void positionsAndColumnNamesAreBoundsChecked() {
        PostgresTableRow row = new PostgresTableRow(COLUMNS, List.of(1, SYNTHETIC_LABEL));

        assertThatThrownBy(() -> row.valueAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> row.valueAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> row.column(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> row.valueAt(9)).isInstanceOf(IndexOutOfBoundsException.class);
        // An unknown column is refused rather than answered with a null that
        // would be indistinguishable from a SQL NULL.
        assertThatThrownBy(() -> row.value("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> row.value(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRowNeverPrintsItsValues() {
        // The accidental-leak guard: an exception message, a log line, a test
        // failure, or an IDE value inspector all go through toString(), so a
        // row that withholds its values there cannot leak one by accident.
        PostgresTableRow row = new PostgresTableRow(
                COLUMNS, List.of(1, "person.name@example.invalid"));

        assertThat(row.toString())
                .contains("columnCount=2", "id", "label", "withheld")
                .doesNotContain("person.name@example.invalid");
    }

    @Test
    void theStreamResultReportsTruncationRatherThanHidingIt() {
        PostgresRowStreamResult complete = new PostgresRowStreamResult(2, 5, false);
        PostgresRowStreamResult bounded = new PostgresRowStreamResult(2, 5, true);

        assertThat(complete.columnsRead()).isEqualTo(2);
        assertThat(complete.rowsRead()).isEqualTo(5);
        assertThat(complete.truncated()).isFalse();
        assertThat(bounded.truncated()).isTrue();

        assertThatThrownBy(() -> new PostgresRowStreamResult(0, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresRowStreamResult(2, -1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStreamResultCarriesNoRowDataOrSourceDetail() {
        // Structural counts only: the outcome of reading production data should
        // not itself describe the source, and there is nowhere here for a value,
        // a name, or a credential to travel.
        List<String> components = new ArrayList<>();
        for (var component : PostgresRowStreamResult.class.getRecordComponents()) {
            components.add(component.getType().getSimpleName() + " " + component.getName());
        }

        assertThat(components).containsExactlyInAnyOrder(
                "int columnsRead", "long rowsRead", "boolean rowLimitReached");
        assertThat(new PostgresRowStreamResult(3, 7, false).toString())
                .doesNotContain("public", "users", "password", "jdbc:");
    }

    @Test
    void theRowLimitsAreRangesRatherThanSuggestions() {
        for (int rows : new int[] {0, -1, PostgresRowLimits.MAX_MAX_ROWS + 1}) {
            assertThatThrownBy(() -> new PostgresRowLimits(rows, 100))
                    .as("row ceiling %s must be rejected", rows)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int fetch : new int[] {0, -1, PostgresRowLimits.MAX_FETCH_SIZE + 1}) {
            assertThatThrownBy(() -> new PostgresRowLimits(10, fetch))
                    .as("fetch size %s must be rejected", fetch)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Exactly at each bound is accepted: the limits are inclusive.
        assertThat(new PostgresRowLimits(PostgresRowLimits.MAX_MAX_ROWS, PostgresRowLimits.MAX_FETCH_SIZE))
                .isNotNull();
    }

    @Test
    void theDefaultBoundsAreConservative() {
        PostgresRowLimits defaults = PostgresRowLimits.defaults();

        assertThat(defaults.maxRows()).isEqualTo(1_000);
        assertThat(defaults.fetchSize()).isEqualTo(100);
        assertThat(defaults.maxRows()).isLessThanOrEqualTo(10_000);
        assertThat(defaults.fetchSize()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void theReadFailureMessageIsFixedAndSafe() {
        // One message for every failure, including a table that does not exist:
        // the exception is not an existence oracle and leaks no SQL, identifier,
        // credential, driver text, or row value.
        PostgresTableRowReadException failure = new PostgresTableRowReadException(
                new java.sql.SQLException("ERROR: relation \"public.secret_table\" does not exist"));

        assertThat(failure).hasMessage("Unable to read PostgreSQL source table.");
        assertThat(PostgresTableRowReadException.MESSAGE)
                .doesNotContain("public", "secret_table", "relation", "does not exist", "SELECT", "jdbc:");
    }
}

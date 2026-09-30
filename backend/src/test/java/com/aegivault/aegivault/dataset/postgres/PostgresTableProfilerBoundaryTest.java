package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The boundary tests for {@link PostgresTableProfiler}: how a JDBC value becomes
 * profiling text, what an empty table does, which exception a caller sees, and
 * what the class is allowed to depend on.
 *
 * <p>Pure unit tests — no Spring, no database, no JDBC. The values exist only to
 * prove the conversion is faithful and that none of them can escape into a
 * message.
 */
class PostgresTableProfilerBoundaryTest {

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    /** Obviously synthetic; used to prove it never reaches an exception message. */
    private static final String SYNTHETIC_EMAIL = "synthetic.user@example.invalid";

    private static final PostgresTable ONE_COLUMN = new PostgresTable("contacts",
            List.of(new PostgresColumn("email", 1, "varchar")));

    private static PiiColumnProfiler columnProfiler() {
        return new PiiColumnProfiler(new PiiDetectorRegistry(
                List.of(new EmailDetector(), new PhoneDetector(), new CreditCardDetector())));
    }

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

    /** A row source that replays exactly the values given, one column wide. */
    private static PostgresTableRowSource rowsOf(Object... values) {
        List<PostgresTableRow> rows = Arrays.stream(values)
                .map(value -> new PostgresTableRow(
                        ONE_COLUMN.columns(), Arrays.asList(value)))
                .toList();
        return new PostgresTableRowSource() {

            @Override
            public PostgresRowStreamResult streamRows(PostgresDataSource source, PostgresTable table,
                    Consumer<PostgresTableRow> rowConsumer) {
                rows.forEach(rowConsumer);
                return new PostgresRowStreamResult(1, rows.size(), false);
            }
        };
    }

    @Test
    void sqlNullBecomesNullAndNeverTheStringNull() {
        assertThat(PostgresTableProfiler.toProfilingText(null)).isNull();
    }

    @Test
    void textIsUsedExactlyAsTheDatabaseReturnedIt() {
        // No trimming, no case folding, no reformatting: the detector is shown
        // the value's own text form.
        assertThat(PostgresTableProfiler.toProfilingText("  Mixed Case Value  "))
                .isEqualTo("  Mixed Case Value  ");
        assertThat(PostgresTableProfiler.toProfilingText("")).isEmpty();
    }

    @Test
    void nonTextTypesUseTheirOwnTextForm() {
        // One deterministic strategy: everything that is not null, text, or
        // binary is rendered with its own toString.
        assertThat(PostgresTableProfiler.toProfilingText(42)).isEqualTo("42");
        assertThat(PostgresTableProfiler.toProfilingText(42L)).isEqualTo("42");
        assertThat(PostgresTableProfiler.toProfilingText(new BigDecimal("12.50"))).isEqualTo("12.50");
        assertThat(PostgresTableProfiler.toProfilingText(Boolean.TRUE)).isEqualTo("true");
        assertThat(PostgresTableProfiler.toProfilingText(1.5d)).isEqualTo("1.5");
        assertThat(PostgresTableProfiler.toProfilingText(LocalDate.of(2024, 1, 15)))
                .isEqualTo("2024-01-15");
        assertThat(PostgresTableProfiler.toProfilingText(UUID.fromString(
                "00000000-0000-0000-0000-000000000001")))
                .isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void binaryIsLeftUndecodedRatherThanFabricatedAsText() {
        // bytea is not text: decoding it would invent content the database never
        // held, so it is treated as having no analyzable value at all.
        assertThat(PostgresTableProfiler.toProfilingText(new byte[] {1, 2, 3})).isNull();
    }

    @Test
    void conversionIsDeterministicForTheSameValue() {
        Object value = new BigDecimal("7.25");
        assertThat(PostgresTableProfiler.toProfilingText(value))
                .isEqualTo(PostgresTableProfiler.toProfilingText(value));
    }

    @Test
    void anEmptyTableProducesAValidProfileWithZeroObservations() {
        DatasetProfile profile = new PostgresTableProfiler(rowsOf(), columnProfiler())
                .profile(DATASET_ID, source(), ONE_COLUMN);

        // No rows is a normal outcome, not a failure: the discovered column is
        // still present, with honest zero counts and no detections.
        assertThat(profile.columns()).hasSize(1);
        assertThat(profile.columns().get(0).columnName()).isEqualTo("email");
        assertThat(profile.columns().get(0).suppliedValueCount()).isZero();
        assertThat(profile.columns().get(0).analyzedValueCount()).isZero();
        assertThat(profile.columns().get(0).analyzableValueCount()).isZero();
        assertThat(profile.columns().get(0).detectionCounts()).isEmpty();
        assertThat(profile.columns().get(0).detectionRates()).isEmpty();
        assertThat(profile.columns().get(0).detectedTypes()).isEmpty();
    }

    @Test
    void aDetectorFailureCannotLeakTheValueItWasInspecting() {
        // A detector that quotes the value back in its own message: exactly the
        // leak this boundary exists to stop.
        PiiDetector leaky = new PiiDetector() {

            @Override
            public java.util.Optional<com.aegivault.aegivault.pii.PiiDetection> detect(String value) {
                throw new IllegalStateException("detector failed on value: " + value);
            }
        };
        PiiColumnProfiler failing = new PiiColumnProfiler(new PiiDetectorRegistry(List.of(leaky)));

        PostgresTableProfileException failure = org.junit.jupiter.api.Assertions.assertThrows(
                PostgresTableProfileException.class,
                () -> new PostgresTableProfiler(rowsOf(SYNTHETIC_EMAIL), failing)
                        .profile(DATASET_ID, source(), ONE_COLUMN));

        assertThat(failure.getMessage()).isEqualTo("Unable to profile the PostgreSQL source table.");
        // The safe message is fixed: none of the value, the SQL, the relation, the
        // URL, or the driver's own text escapes through it.
        for (String leak : List.of(SYNTHETIC_EMAIL, "public", "contacts", "SELECT", "jdbc:")) {
            assertThat(failure.getMessage()).doesNotContain(leak);
        }
    }

    @Test
    void aSourceFailureKeepsItsOwnDistinctException() {
        // Streaming failed, so profiling never started: the row-read exception
        // propagates unchanged rather than being masked as a profiling failure.
        PostgresTableRowSource failing = new PostgresTableRowSource() {

            @Override
            public PostgresRowStreamResult streamRows(PostgresDataSource source, PostgresTable table,
                    Consumer<PostgresTableRow> rowConsumer) {
                throw new PostgresTableRowReadException(
                        new SQLException("ERROR: relation \"public.contacts\" does not exist"));
            }
        };

        assertThatThrownBy(() -> new PostgresTableProfiler(failing, columnProfiler())
                .profile(DATASET_ID, source(), ONE_COLUMN))
                .isInstanceOf(PostgresTableRowReadException.class)
                .isNotInstanceOf(PostgresTableProfileException.class)
                .hasMessage("Unable to read PostgreSQL source table.");
    }

    @Test
    void aMissingArgumentIsRejectedBeforeAnythingIsRead() {
        PostgresTableProfiler profiler =
                new PostgresTableProfiler(rowsOf(SYNTHETIC_EMAIL), columnProfiler());

        assertThatThrownBy(() -> profiler.profile(null, source(), ONE_COLUMN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profiler.profile(DATASET_ID, null, ONE_COLUMN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> profiler.profile(DATASET_ID, source(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostgresTableProfiler(null, columnProfiler()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostgresTableProfiler(rowsOf(), null))
                .isInstanceOf(NullPointerException.class);
        // A table with no discovered columns cannot be profiled.
        assertThatThrownBy(() -> profiler.profile(DATASET_ID, source(),
                new PostgresTable("empty", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theProfilerDependsOnTheRowSourceAndTheExistingEngineAndNothingElse() {
        // Dependency direction, checked structurally: the row stream and the PII
        // engine, and no repository, sanitizer, gateway, audit ledger, or
        // controller.
        assertThat(java.util.Arrays.stream(PostgresTableProfiler.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder("PostgresTableRowSource", "PiiColumnProfiler");
    }

    @Test
    void theProfilerExposesOnlyOneOperationAndNoRawQueryPath() {
        // One operation taking a discovered table: no SQL string, no table-name
        // lookup, no WHERE, and no JDBC type anywhere on the reachable surface.
        assertThat(java.util.Arrays.stream(PostgresTableProfiler.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("profile");
        assertThat(java.util.Arrays.stream(PostgresTableProfiler.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .map(Class::getSimpleName))
                .containsExactly("UUID", "PostgresDataSource", "PostgresTable");
    }

    @Test
    void thePiiEngineDoesNotDependOnAnyPostgresClass() {
        // The one-way rule, asserted from the other side: profiling on top of a
        // database source must not leak database concepts into the engine.
        for (Class<?> type : List.of(PiiColumnProfiler.class, PiiDetectorRegistry.class,
                EmailDetector.class, PhoneDetector.class, CreditCardDetector.class)) {
            for (var field : type.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("%s must not reference %s", type.getSimpleName(), field.getType())
                        .doesNotContain("postgres");
            }
            for (var method : type.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName()).doesNotContain("postgres");
                }
                assertThat(method.getReturnType().getName()).doesNotContain("postgres");
            }
        }
    }
}

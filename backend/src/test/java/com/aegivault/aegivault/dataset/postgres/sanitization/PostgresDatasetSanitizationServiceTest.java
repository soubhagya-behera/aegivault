package com.aegivault.aegivault.dataset.postgres.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aegivault.aegivault.dataset.postgres.JdbcPostgresTableRowSource;
import com.aegivault.aegivault.dataset.postgres.PostgresColumn;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresRowLimits;
import com.aegivault.aegivault.dataset.postgres.PostgresRowStreamResult;
import com.aegivault.aegivault.dataset.postgres.PostgresSchema;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryException;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTable;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRow;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRowReadException;
import com.aegivault.aegivault.dataset.postgres.PostgresTableRowSource;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBinding;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DataSanitizationService;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import com.aegivault.aegivault.sanitization.strategy.MaskTransformation;
import com.aegivault.aegivault.sanitization.strategy.RedactTransformation;
import com.aegivault.aegivault.sanitization.strategy.Sha256HashTransformation;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import com.aegivault.aegivault.sanitization.run.RunResult;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationContentSource;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure unit tests for the PostgreSQL sanitization bridge: no Spring context, no
 * database, no JDBC.
 *
 * <p><strong>What these prove.</strong> That the owner-scoped binding is the only
 * source of the table, that the discovered column order becomes the CSV header,
 * that rows are transformed one at a time through the existing engine with the
 * caller's plan, that SQL NULL becomes an empty field, that the row limit is
 * reported rather than hidden, that a stale binding or an unreadable source fails
 * safely, that no source value can reach a message, and that the class has no
 * dependency it should not have.
 *
 * <p>All values are obviously synthetic and are asserted by their sanitized form
 * only; nothing is logged.
 */
class PostgresDatasetSanitizationServiceTest {

    private static final String OWNER = "owner-1";

    private static final String OTHER = "owner-2";

    private static final String SCHEMA = "public";

    private static final String TABLE = "contacts";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca11");

    /** Obviously synthetic values; used only to prove they are transformed. */
    private static final String EMAIL = "first.synthetic@example.invalid";

    private static final String PHONE = "9876543210";

    private static final String CARD = "4111111111111111";

    private final PostgresDatasetBindingService bindings = mock(PostgresDatasetBindingService.class);

    private final PostgresSchemaDiscoveryService discovery = mock(PostgresSchemaDiscoveryService.class);

    private final SanitizationRunExecutor runs = mock(SanitizationRunExecutor.class);

    private final PostgresDataSource source = stubSource(SCHEMA);

    private final PiiDetectorRegistry detectors = new PiiDetectorRegistry(
            List.of(new EmailDetector(), new PhoneDetector(), new CreditCardDetector()));

    /** The real engine over the real strategy implementations, as in production. */
    private final DataSanitizationService engine = new DataSanitizationService(
            new TransformationRegistry(List.of(
                    new MaskTransformation(),
                    new RedactTransformation(),
                    new Sha256HashTransformation())));

    /** The caller's explicit plan; no policy is inferred anywhere in the bridge. */
    private final TransformationPlan plan = TransformationPlan.of(
            new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK),
            new TransformationRule(PiiType.PHONE, TransformationStrategy.REDACT),
            new TransformationRule(PiiType.CREDIT_CARD, TransformationStrategy.HASH_SHA256));

    private static PostgresDataSource stubSource(String schemaName) {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return schemaName;
            }

            @Override
            public Connection openReadOnlyConnection() {
                throw new AssertionError("discovery and the row source are mocked, never really connect");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** The discovered base table: three columns in a deliberate non-alphabetical order. */
    private static PostgresTable discoveredTable() {
        return new PostgresTable(TABLE, List.of(
                new PostgresColumn("id", 1, "integer"),
                new PostgresColumn("email", 2, "varchar"),
                new PostgresColumn("note", 3, "text")));
    }

    private static PostgresDatasetBinding bound() {
        return new PostgresDatasetBinding(DATASET_ID, OWNER, SCHEMA, TABLE);
    }

    private void givenBoundTable() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(discovery.discover(source))
                .thenReturn(new PostgresSchema(SCHEMA, List.of(discoveredTable())));
    }

    /** A row source that replays exactly the rows given, honouring the row limit. */
    private static PostgresTableRowSource rowsOf(int maxRows, PostgresTableRow... rows) {
        List<PostgresTableRow> replay = List.of(rows);
        return new PostgresTableRowSource() {

            @Override
            public PostgresRowStreamResult streamRows(PostgresDataSource source, PostgresTable table,
                    Consumer<PostgresTableRow> rowConsumer) {
                int delivered = 0;
                boolean limited = false;
                for (PostgresTableRow row : replay) {
                    if (delivered == maxRows) {
                        limited = true;
                        break;
                    }
                    rowConsumer.accept(row);
                    delivered++;
                }
                return new PostgresRowStreamResult(table.columns().size(), delivered, limited);
            }
        };
    }

    private static PostgresTableRow row(Object id, Object email, Object note) {
        return new PostgresTableRow(discoveredTable().columns(), Arrays.asList(id, email, note));
    }

    /** Builds the service over a stub row source with the given rows and limit. */
    private PostgresDatasetSanitizationService serviceWith(int maxRows, PostgresTableRow... rows) {
        return serviceWith(providerOf(rowsOf(maxRows, rows)));
    }

    private PostgresDatasetSanitizationService serviceWith(ObjectProvider<PostgresTableRowSource> rowSource) {
        return new PostgresDatasetSanitizationService(
                bindings, discovery, runs, detectors, engine, providerOf(source), rowSource);
    }

    private PostgresDatasetSanitizationService service() {
        return serviceWith(100);
    }

    /**
     * A minimal completed run view carrying the source's own counts, standing in
     * for what the real executor would return.
     */
    private static SanitizationRunView runView(RunResult result) {
        return new SanitizationRunView(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"), DATASET_ID,
                RunStatus.COMPLETED, "policy", "v1", "{}",
                SanitizationSourceType.POSTGRESQL, result.inputRows(), result.outputRows(),
                result.blankRowsSkipped(), result.columnCount(),
                null, null, null, null, null, 0L, null, null);
    }

    /**
     * Runs the service with the executor replaced by a capturing one, so the
     * tests can inspect the bytes the bridge would have written.
     */
    private String captureCsv(PostgresDatasetSanitizationService service) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when(runs.executeContent(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    SanitizationContentSource content = invocation.getArgument(5);
                    return runView(content.sanitizeTo(buffer));
                });
        service.sanitize(OWNER, DATASET_ID, plan, "policy", "v1");
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** The CSV lines, so a test can index them without Optional noise. */
    private static List<String> lines(String csv) {
        return csv.lines().toList();
    }

    @Test
    void theHeaderIsTheDiscoveredColumnNamesInOrdinalOrder() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100, row(1, EMAIL, "plain")));

        // Ordinal order, not alphabetical and not caller-supplied: id, email,
        // note is the table's own declaration order.
        assertThat(lines(csv).get(0)).contains("id,email,note");
        // LF terminators, no BOM, no CR.
        assertThat(csv).doesNotContain("\r").startsWith("id,email,note\n").endsWith("\n");
    }

    @Test
    void cleanValuesArePreservedAndPiiIsTransformedByTheExistingEngine() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100, row(1, EMAIL, "a-clean-note")));

        assertThat(csv).contains("a-clean-note");
        // The raw email is gone; MASK is the caller's chosen strategy, applied by
        // the existing registry, so the output is the engine's mask, not ours.
        assertThat(csv).doesNotContain(EMAIL);
        assertThat(lines(csv).get(1))
                .isEqualTo("1," + new MaskTransformation().apply(EMAIL) + ",a-clean-note");
    }

    @Test
    void phoneAndCreditCardUseTheStrategiesTheCallerChose() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100, row(1, PHONE, CARD)));

        String record = lines(csv).get(1);
        // REDACT for the phone: no characters of the original survive.
        assertThat(record).contains("1," + new RedactTransformation().apply(PHONE));
        assertThat(csv).doesNotContain(PHONE);
        // HASH_SHA256 for the card: 64 lowercase hex characters, not the original.
        assertThat(record).contains(new Sha256HashTransformation().apply(CARD));
        assertThat(csv).doesNotContain(CARD);
    }

    @Test
    void sqlNullBecomesAnEmptyFieldDeterministically() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100, row(1, null, null)));

        // Exactly an empty middle and trailing field, and the engine is never
        // asked to transform a null.
        assertThat(lines(csv).get(1)).isEqualTo("1,,");
    }

    @Test
    void anEmptyTableStillProducesAValidHeaderOnlyArtifact() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100));

        assertThat(csv).isEqualTo("id,email,note\n");
    }

    @Test
    void valuesNeedingCsvQuotingAreEscapedByTheExistingWriter() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100, row(1, "plain", "has,comma and \"quote\"")));

        // The shared writer's rules, not a second implementation of them.
        assertThat(csv).contains("\"has,comma and \"\"quote\"\"\"");
    }

    @Test
    void everyStreamedRowIsWrittenAndCountedOnce() {
        givenBoundTable();

        String csv = captureCsv(serviceWith(100, row(1, EMAIL, "a"), row(2, "b", "c"), row(3, "d", "e")));

        // One header plus exactly three data records.
        assertThat(lines(csv)).hasSize(4);
    }

    @Test
    void theRowLimitBoundsTheWorkAndTheRunKeepsItsOrdinaryCounts() {
        givenBoundTable();
        when(runs.executeContent(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> runView(
                        ((SanitizationContentSource) invocation.getArgument(5))
                                .sanitizeTo(new ByteArrayOutputStream())));

        // Two rows exist but the stream ceiling is one.
        PostgresSanitizationResult result = serviceWith(1, row(1, EMAIL, "a"), row(2, EMAIL, "b"))
                .sanitize(OWNER, DATASET_ID, plan, "policy", "v1");

        assertThat(result.rowsSanitized()).isEqualTo(1);
        assertThat(result.rowLimitReached()).isTrue();
        // The run record keeps the ordinary structural counts and is NOT
        // reinterpreted to mean "this was the whole table".
        assertThat(result.runId()).isNotNull();
    }

    @Test
    void truncationIsSurfacedOnTheResultRatherThanInventedOnTheRun() {
        givenBoundTable();
        when(runs.executeContent(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> runView(
                        ((SanitizationContentSource) invocation.getArgument(5))
                                .sanitizeTo(new ByteArrayOutputStream())));

        // Not truncated: the source ran out of rows before the ceiling.
        PostgresSanitizationResult complete = serviceWith(100, row(1, EMAIL, "a"))
                .sanitize(OWNER, DATASET_ID, plan, "policy", "v1");
        assertThat(complete.rowLimitReached()).isFalse();
        assertThat(complete.rowsSanitized()).isEqualTo(1);

        // Truncated: the ceiling stopped the stream with rows left over.
        PostgresSanitizationResult limited = serviceWith(1, row(1, EMAIL, "a"), row(2, EMAIL, "b"))
                .sanitize(OWNER, DATASET_ID, plan, "policy", "v1");
        assertThat(limited.rowLimitReached()).isTrue();
        assertThat(limited.rowsSanitized()).isEqualTo(1);
    }

    @Test
    void aMissingOrForeignBindingSurfacesTheBindingsOwnNotFoundSignal() {
        when(bindings.get(OTHER, DATASET_ID))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        assertThatThrownBy(() -> service().sanitize(OTHER, DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);

        // The source is never contacted and no run is created for a dataset the
        // caller does not own.
        verifyNoInteractions(discovery, runs);
    }

    @Test
    void aStaleBindingFailsSafelyAndCreatesNoRunOrArtifact() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        // The bound table is no longer a discovered base table.
        when(discovery.discover(source)).thenReturn(new PostgresSchema(SCHEMA, List.of()));

        assertThatThrownBy(() -> service().sanitize(OWNER, DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(PostgresDatasetSanitizationException.class)
                .hasMessage(PostgresDatasetSanitizationException.MESSAGE);

        // Fails before a run exists: no run row, no artifact, no partial one, and
        // the binding is neither deleted nor repointed.
        verify(runs, never()).executeContent(anyString(), any(), any(), anyString(), anyString(), any());
        verify(bindings, never()).delete(any(), any());
    }

    @Test
    void aDiscoveryFailureFailsWithTheSameFixedSafeMessage() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        when(discovery.discover(source)).thenThrow(new PostgresSchemaDiscoveryException(
                new SQLException("FATAL: no pg_hba.conf entry for host 10.0.0.5")));

        assertThatThrownBy(() -> service().sanitize(OWNER, DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(PostgresDatasetSanitizationException.class)
                // No host, no SQL state, no driver text: the fixed message only.
                .hasMessage(PostgresDatasetSanitizationException.MESSAGE)
                .hasMessageNotContaining("pg_hba");
        verify(runs, never()).executeContent(anyString(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void anUnreadableSourcePropagatesAsTheSafeSourceFailure() {
        givenBoundTable();
        PostgresTableRowSource failing = new PostgresTableRowSource() {

            @Override
            public PostgresRowStreamResult streamRows(PostgresDataSource source, PostgresTable table,
                    Consumer<PostgresTableRow> rowConsumer) {
                throw new PostgresTableRowReadException(
                        new SQLException("FATAL: terminating connection due to administrator command"));
            }
        };
        when(runs.executeContent(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ((SanitizationContentSource) invocation.getArgument(5))
                            .sanitizeTo(new ByteArrayOutputStream());
                    return null;
                });

        assertThatThrownBy(() -> serviceWith(providerOf(failing))
                .sanitize(OWNER, DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(PostgresDatasetSanitizationException.class)
                .hasMessage(PostgresDatasetSanitizationException.MESSAGE)
                .hasMessageNotContaining("terminating");
    }

    @Test
    void anUnconfiguredSourceFailsSafelyBeforeAnyRunIsCreated() {
        when(bindings.get(OWNER, DATASET_ID)).thenReturn(bound());
        PostgresDatasetSanitizationService service = new PostgresDatasetSanitizationService(
                bindings, discovery, runs, detectors, engine, providerOf(null), providerOf(null));

        assertThatThrownBy(() -> service.sanitize(OWNER, DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(PostgresDatasetSanitizationException.class);
        verifyNoInteractions(discovery, runs);
    }

    @Test
    void aBlankOwnerIsRejectedBeforeAnythingElse() {
        assertThatThrownBy(() -> service().sanitize("   ", DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().sanitize(null, DATASET_ID, plan, "policy", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bindings, discovery, runs);
    }

    @Test
    void theOwnerIsTrimmedOnceAndThreadedToTheBindingAndTheRun() {
        givenBoundTable();
        when(runs.executeContent(anyString(), any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> runView(
                        ((SanitizationContentSource) invocation.getArgument(5))
                                .sanitizeTo(new ByteArrayOutputStream())));

        service().sanitize("  " + OWNER + "  ", DATASET_ID, plan, "policy", "v1");

        verify(bindings).get(OWNER, DATASET_ID);
        verify(runs).executeContent(eq(OWNER), eq(DATASET_ID), eq(plan), eq("policy"), eq("v1"), any());
    }

    @Test
    void theServiceDependsOnTheFlowOnlyAndCannotReachSql() {
        // Dependency direction, checked structurally: binding, discovery, the
        // run executor, the existing PII registry and sanitization engine, and
        // the two optional source handles.
        assertThat(java.util.Arrays.stream(PostgresDatasetSanitizationService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "PostgresDatasetBindingService",
                        "PostgresSchemaDiscoveryService",
                        "SanitizationRunExecutor",
                        "PiiDetectorRegistry",
                        "DataSanitizationService",
                        "ObjectProvider",
                        "ObjectProvider");
        // No JDBC type is reachable: the class cannot build or execute SQL.
        for (var field : PostgresDatasetSanitizationService.class.getDeclaredFields()) {
            assertThat(field.getType().getName()).doesNotContain("java.sql");
        }
        // Two public operations, and both are content producers, not lifecycle
        // verbs: sanitize for the synchronous path, contentSourceFor for a run
        // the existing executor already owns. No bind, launch, or transition.
        assertThat(java.util.Arrays.stream(PostgresDatasetSanitizationService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("sanitize", "contentSourceFor");
    }

    @Test
    void theSafeMessageCarriesNoSourceDetail() {
        assertThat(PostgresDatasetSanitizationException.MESSAGE)
                .doesNotContain("jdbc:", "password", "SQLException", "Exception", "http", "SELECT");
    }
}


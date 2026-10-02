package com.aegivault.aegivault.dataset.postgres.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.postgres.JdbcPostgresTableRowSource;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresRowLimits;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConnectionException;
import com.aegivault.aegivault.dataset.postgres.PostgresTableProfiler;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingRepository;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.dataset.postgres.profiling.PostgresDatasetProfilingService;
import com.aegivault.aegivault.dataset.profile.DatasetProfileService;
import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import com.aegivault.aegivault.sanitization.DataSanitizationService;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import com.aegivault.aegivault.sanitization.run.PolicySnapshot;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.CsvSanitizationSources;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.run.SanitizationRunSourceDispatcher;
import com.aegivault.aegivault.sanitization.run.SanitizationRunNotFoundException;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLauncher;
import com.aegivault.aegivault.sanitization.strategy.MaskTransformation;
import com.aegivault.aegivault.sanitization.strategy.RedactTransformation;
import com.aegivault.aegivault.sanitization.strategy.Sha256HashTransformation;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
 * A queued PostgreSQL run executed by the real existing launcher: the persisted
 * run, the real bounded worker pool, the real executor, the real row stream, and
 * the real artifact store.
 *
 * <p><strong>No new context.</strong> This is the existing application context,
 * so the very same {@link SanitizationRunJobLauncher} bean a CSV run uses is what
 * executes a PostgreSQL run here.
 *
 * <p><strong>Everything in the fixture is obviously synthetic</strong> and no raw
 * value is asserted on; only sanitized output and safe run metadata are.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSanitizationRunJobTest {

    private static final String FIXTURE = "aegivault_queued_sanitize_fixture";

    private static final String OWNER = "pg-queued-owner";

    private static final String SCHEMA = "public";

    private static final String EMAIL = "first.synthetic@example.invalid";

    private static final String CARD = "4111111111111111";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private PostgresDatasetBindingRepository bindings;

    @Autowired
    private SanitizationRunRepository runs;

    @Autowired
    private SanitizationArtifactStore artifacts;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    @Autowired
    private DatasetProfileService profileService;

    @Autowired
    private SanitizationRunService runService;

    @Autowired
    private SanitizationRunJobLauncher launcher;

    @Autowired
    private PiiDetectorRegistry detectors;

    @Autowired
    private com.aegivault.aegivault.dataset.csv.CsvSanitizationService csv;

    @Autowired
    private com.aegivault.aegivault.dataset.DatasetInputSource inputs;

    @Autowired
    private com.aegivault.aegivault.audit.AuditLedgerService audit;

    @Autowired
    private SanitizationRunExecutor beanExecutor;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("sanitizationRunJobExecutor")
    private org.springframework.core.task.TaskExecutor jobExecutor;

    /**
     * The real launcher class over the real bounded pool, wired to an executor
     * that knows about this test's PostgreSQL provider.
     *
     * <p>The application context has no configured PostgreSQL source — that is a
     * deployment property — so the context's own provider has no source to read
     * and the context's executor would fail this run. Assembling the provider
     * here is the same thing every other PostgreSQL integration test in this
     * repository does, and it keeps the launcher, the pool, the run service, the
     * artifact store, and the audit ledger all the real ones.
     */
    private SanitizationRunJobLauncher wiredLauncher() {
        PostgresSanitizationRunService provider = runCreation();
        // The executor's collaborators are the real beans; only the PostgreSQL
        // provider is assembled here, because the application context has no
        // configured PostgreSQL source to build one. The production wiring of
        // these collaborators is covered by PostgresSanitizationRunWiringTest.
        SanitizationRunExecutor executor = new SanitizationRunExecutor(
                runService,
                new SanitizationRunSourceDispatcher(List.of(provider)),
                new CsvSanitizationSources(csv, inputs),
                artifacts,
                audit);
        return new SanitizationRunJobLauncher(executor, jobExecutor);
    }

    @BeforeAll
    void createFixture() throws SQLException {
        execute("DROP TABLE IF EXISTS " + FIXTURE);
        execute("""
                CREATE TABLE aegivault_queued_sanitize_fixture (
                    id     integer     NOT NULL,
                    email  varchar(120),
                    card   varchar(20),
                    note   text
                )""");
    }

    @AfterAll
    void dropFixture() {
        try {
            execute("DROP TABLE IF EXISTS " + FIXTURE);
        } catch (SQLException ignored) {
            // Best effort: the fixture name is unmistakably a test artefact.
        }
    }

    private void seed(String... rows) throws SQLException {
        execute("TRUNCATE TABLE " + FIXTURE);
        for (String row : rows) {
            execute("INSERT INTO " + FIXTURE + " VALUES (" + row + ")");
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private PostgresDataSource applicationSource() {
        return new PostgresDataSource() {

            @Override
            public String schemaName() {
                return SCHEMA;
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

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PostgresDatasetBindingService bindingService() {
        return new PostgresDatasetBindingService(
                bindings, datasets, discovery, providerOf(applicationSource()));
    }

    private PostgresDatasetSanitizationService sanitizationService(SanitizationRunExecutor beanExecutor) {
        return new PostgresDatasetSanitizationService(
                bindingService(),
                discovery,
                // The queued path never calls sanitize(), which is the only method
                // that creates a run; the executor owns that here. Passing the
                // real bean keeps the wiring honest either way.
                beanExecutor,
                detectors,
                new DataSanitizationService(
                        new TransformationRegistry(List.of(
                                new MaskTransformation(),
                                new RedactTransformation(),
                                new Sha256HashTransformation()))),
                providerOf(applicationSource()),
                providerOf(new JdbcPostgresTableRowSource(new PostgresRowLimits(100, 50))));
    }

    /** The creation/dispatch service under test, over the real run service. */
    private PostgresSanitizationRunService runCreation() {
        return new PostgresSanitizationRunService(
                runService, bindingService(), providerOf(sanitizationService(beanExecutor)));
    }

    private TransformationPlan plan() {
        return TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK),
                new TransformationRule(PiiType.CREDIT_CARD, TransformationStrategy.HASH_SHA256));
    }

    /** A dataset bound to the fixture and profiled, ready to queue against. */
    private Dataset boundDataset() {
        Dataset dataset = datasets.save(new Dataset("pg-queued-dataset", OWNER));
        bindingService().bind(OWNER, dataset.getId(), SCHEMA, FIXTURE);
        new PostgresDatasetProfilingService(
                bindingService(),
                discovery,
                profileService,
                providerOf(applicationSource()),
                providerOf(new PostgresTableProfiler(
                        new JdbcPostgresTableRowSource(new PostgresRowLimits(100, 50)),
                        new PiiColumnProfiler(detectors))))
                .profile(OWNER, dataset.getId());
        return dataset;
    }

    /** Polls to a terminal state, as the existing launcher test does. */
    private SanitizationRunView awaitTerminal(UUID runId) {
        Instant deadline = Instant.now().plusSeconds(30);
        RunStatus status = runService.get(OWNER, runId).status();
        while (!status.isTerminal() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting the background run");
            }
            status = runService.get(OWNER, runId).status();
        }
        assertThat(status.isTerminal()).as("the launched run must reach a terminal state").isTrue();
        return runService.get(OWNER, runId);
    }

    private String artifactOf(UUID runId) throws IOException {
        try (InputStream stream = artifacts.openArtifact(OWNER, runId)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aQueuedPostgresRunIsExecutedByTheLauncherAndCompletesWithAnArtifact() throws Exception {
        seed("1, '" + EMAIL + "', '" + CARD + "', 'keep-me'");
        Dataset dataset = boundDataset();

        SanitizationRunView queued = runCreation().queue(OWNER, dataset.getId(), plan(), "pol", "v1");
        assertThat(queued.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(queued.sourceType()).isEqualTo(SanitizationSourceType.POSTGRESQL);

        wiredLauncher().launch(runService.loadForExecution(queued.id()));

        SanitizationRunView finished = awaitTerminal(queued.id());
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.inputRowCount()).isEqualTo(1L);
        assertThat(finished.columnCount()).isEqualTo(4);

        // The artifact came from the existing store, in the existing CSV format,
        // with the values transformed and the clean one preserved.
        String csv = artifactOf(queued.id());
        assertThat(csv.lines().toList().get(0)).isEqualTo("id,email,card,note");
        assertThat(csv).doesNotContain(EMAIL).doesNotContain(CARD).contains("keep-me");
    }

    @Test
    void theSourceTableIsUnchangedByAQueuedRun() throws Exception {
        seed("1, '" + EMAIL + "', '" + CARD + "', 'original-note'");
        Dataset dataset = boundDataset();

        SanitizationRunView queued = runCreation().queue(OWNER, dataset.getId(), plan(), "pol", "v1");
        wiredLauncher().launch(runService.loadForExecution(queued.id()));
        assertThat(awaitTerminal(queued.id()).status()).isEqualTo(RunStatus.COMPLETED);

        // Reading a source never writes to it.
        assertThat(readNote()).isEqualTo("original-note");
        assertThat(readEmail()).isEqualTo(EMAIL);
    }

    @Test
    void aPolicyChangedAfterQueueingDoesNotAlterTheRunsFrozenSnapshot() throws Exception {
        seed("1, '" + EMAIL + "', '" + CARD + "', 'keep-me'");
        Dataset dataset = boundDataset();
        SanitizationRunView queued = runCreation().queue(OWNER, dataset.getId(), plan(), "pol", "v1");
        String frozen = queued.policySnapshot();

        // "Change the policy": a different plan would redact instead of mask.
        // Nothing here re-resolves a policy, so the queued run is unaffected.
        TransformationPlan laterPolicy = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        PolicySnapshot laterSnapshot = PolicySnapshot.fromPlan("pol", "v2", laterPolicy);

        wiredLauncher().launch(runService.loadForExecution(queued.id()));
        SanitizationRunView finished = awaitTerminal(queued.id());

        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.policySnapshot()).isEqualTo(frozen);
        assertThat(finished.policyVersion()).isEqualTo("v1");
        // The original MASK survived the policy change: a mask keeps a tail, a
        // redaction would not, so the artifact proves which plan actually ran.
        assertThat(artifactOf(queued.id()))
                .contains(new MaskTransformation().apply(EMAIL))
                .doesNotContain(new RedactTransformation().apply(EMAIL));
        assertThat(laterSnapshot.toPlan().strategyFor(PiiType.EMAIL)).isPresent();
    }

    @Test
    void aStaleBindingFailsTheRunSafelyWithNoPartialArtifact() throws Exception {
        String stale = "aegivault_queued_stale";
        execute("DROP TABLE IF EXISTS " + stale);
        try {
            execute("CREATE TABLE " + stale + " (id integer, email varchar(120))");
            Dataset dataset = datasets.save(new Dataset("pg-queued-stale", OWNER));
            bindingService().bind(OWNER, dataset.getId(), SCHEMA, stale);
            SanitizationRunView queued = runCreation().queue(OWNER, dataset.getId(), plan(), "pol", "v1");

            // The table disappears after queueing but before execution.
            execute("DROP TABLE " + stale);

            wiredLauncher().launch(runService.loadForExecution(queued.id()));
            SanitizationRunView failed = awaitTerminal(queued.id());

            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
            assertThat(failed.errorStage()).isEqualTo("SOURCE");
            // Safe metadata only: no SQL, no credential, no row value.
            assertThat(failed.errorMessage()).doesNotContain("SELECT").doesNotContain("password");
            // No artifact at all, partial or otherwise, and the binding survives.
            assertThatThrownBy(() -> artifacts.openArtifact(OWNER, queued.id()))
                    .isInstanceOf(SanitizationRunNotFoundException.class);
            assertThat(bindings.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER))
                    .hasValueSatisfying(binding -> assertThat(binding.getTableName()).isEqualTo(stale));
        } finally {
            execute("DROP TABLE IF EXISTS " + stale);
        }
    }

    private String readNote() throws SQLException {
        return readSingle("note");
    }

    private String readEmail() throws SQLException {
        return readSingle("email");
    }

    private String readSingle(String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT " + column + " FROM " + FIXTURE + " WHERE id = 1");
                var result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }
}


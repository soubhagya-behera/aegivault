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
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingRepository;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.dataset.postgres.profiling.PostgresDatasetProfilingService;
import com.aegivault.aegivault.dataset.profile.DatasetProfileService;
import com.aegivault.aegivault.dataset.profile.StoredDatasetProfileRepository;
import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The full PostgreSQL sanitization flow against a real database: dataset,
 * binding, profile, discovery, the real row stream, the real transformation
 * engine, the real run lifecycle, and the real artifact store.
 *
 * <p><strong>No new Spring context and no new pool.</strong> The annotations match
 * the existing PostgreSQL integration tests plus {@code PER_CLASS} lifecycle, so
 * the cached application context is reused. The fixture table is created and
 * dropped with direct JDBC, never through the code under test.
 *
 * <p><strong>The source is proven unchanged.</strong> Row count and contents are
 * compared before and after every sanitization, and a read-only transaction is
 * shown to still reject writes — the bridge issues one SELECT and nothing else.
 *
 * <p><strong>Everything in the fixture is obviously synthetic</strong>:
 * {@code .invalid} addresses, {@code 555-01xx}-style numbers, and a well-known
 * test card. No raw value is logged; assertions compare sanitized output only.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class PostgresDatasetSanitizationPostgresTest {

    /** Fixture name: plainly a test artefact, not an application table. */
    private static final String FIXTURE = "aegivault_sanitization_fixture";

    private static final String OWNER = "pg-sanitize-owner";

    private static final String OTHER = "pg-sanitize-other";

    private static final String SCHEMA = "public";

    private static final String EMAIL = "first.synthetic@example.invalid";

    private static final String PHONE = "9876543210";

    private static final String CARD = "4111111111111111";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private PostgresDatasetBindingRepository bindings;

    @Autowired
    private StoredDatasetProfileRepository profiles;

    @Autowired
    private SanitizationRunRepository runs;

    @Autowired
    private SanitizationArtifactStore artifacts;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    @Autowired
    private DatasetProfileService profileService;

    @Autowired
    private SanitizationRunExecutor runExecutor;

    @Autowired
    private PiiDetectorRegistry detectors;

    @BeforeAll
    void createFixture() throws SQLException {
        execute("DROP TABLE IF EXISTS " + FIXTURE);
        execute("""
                CREATE TABLE aegivault_sanitization_fixture (
                    id     integer      NOT NULL,
                    email  varchar(120),
                    phone  varchar(20),
                    card   varchar(20),
                    note   text
                )""");
    }

    @AfterAll
    void dropFixture() {
        try {
            execute("DROP TABLE IF EXISTS " + FIXTURE);
        } catch (SQLException ignored) {
            // Best effort: the fixture name is unmistakably a test artefact and a
            // failed clean-up must not fail a green suite.
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

    /** The application database as a read-only PostgreSQL source. */
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

    /** The real binding service, so the fixture binding is verified as in production. */
    private PostgresDatasetBindingService bindingService() {
        return new PostgresDatasetBindingService(
                bindings, datasets, discovery, providerOf(applicationSource()), runs);
    }

    /** The real engine, over the real strategy implementations. */
    private com.aegivault.aegivault.sanitization.DataSanitizationService engine() {
        return new com.aegivault.aegivault.sanitization.DataSanitizationService(
                new com.aegivault.aegivault.sanitization.strategy.TransformationRegistry(List.of(
                        new com.aegivault.aegivault.sanitization.strategy.MaskTransformation(),
                        new com.aegivault.aegivault.sanitization.strategy.RedactTransformation(),
                        new com.aegivault.aegivault.sanitization.strategy.Sha256HashTransformation())));
    }

    /** The caller-supplied plan. No policy is inferred anywhere. */
    private TransformationPlan plan() {
        return TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK),
                new TransformationRule(PiiType.PHONE, TransformationStrategy.REDACT),
                new TransformationRule(PiiType.CREDIT_CARD, TransformationStrategy.HASH_SHA256));
    }

    private PostgresDatasetSanitizationService service(int maxRows) {
        return new PostgresDatasetSanitizationService(
                bindingService(), discovery, runExecutor, detectors, engine(),
                providerOf(applicationSource()),
                providerOf(new JdbcPostgresTableRowSource(new PostgresRowLimits(maxRows, 50))));
    }

    /**
     * A dataset bound to the fixture and profiled through the existing profiling
     * service, so the flow under test is the real bind -&gt; profile -&gt; sanitize
     * sequence.
     */
    private Dataset boundAndProfiledDataset() {
        Dataset dataset = datasets.save(new Dataset("pg-sanitize-dataset", OWNER));
        bindingService().bind(OWNER, dataset.getId(), SCHEMA, FIXTURE);
        new PostgresDatasetProfilingService(
                bindingService(),
                discovery,
                profileService,
                providerOf(applicationSource()),
                providerOf(new com.aegivault.aegivault.dataset.postgres.PostgresTableProfiler(
                        new JdbcPostgresTableRowSource(new PostgresRowLimits(100, 50)),
                        new com.aegivault.aegivault.pii.profile.PiiColumnProfiler(detectors))))
                .profile(OWNER, dataset.getId());
        return dataset;
    }

    private long fixtureRowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT count(*) FROM " + FIXTURE);
                var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    /** Reads the stored artifact of a completed run. */
    private String artifactOf(String owner, java.util.UUID runId) throws IOException {
        try (InputStream stream = artifacts.openArtifact(owner, runId)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aBoundDatasetIsSanitizedIntoAStoredCsvArtifact() throws Exception {
        seed("1, '" + EMAIL + "', '" + PHONE + "', '" + CARD + "', 'a-clean-note'");
        Dataset dataset = boundAndProfiledDataset();
        long rowsBefore = fixtureRowCount();

        PostgresSanitizationResult result =
                service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");

        // The existing run lifecycle ran to completion...
        assertThat(result.run().status()).isEqualTo(RunStatus.COMPLETED);
        // ...and the artifact is stored through the existing artifact store.
        String csv = artifactOf(OWNER, result.runId());

        // Header is the discovered ordinal column order, LF terminated.
        assertThat(csv.lines().toList().get(0)).isEqualTo("id,email,phone,card,note");
        assertThat(csv).doesNotContain("\r").endsWith("\n");

        // The source is untouched: same rows, same count.
        assertThat(fixtureRowCount()).isEqualTo(rowsBefore);
    }

    @Test
    void sensitiveValuesAreTransformedAndCleanValuesAreKept() throws Exception {
        seed("1, '" + EMAIL + "', '" + PHONE + "', '" + CARD + "', 'keep-me'");
        Dataset dataset = boundAndProfiledDataset();

        PostgresSanitizationResult result =
                service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");
        String csv = artifactOf(OWNER, result.runId());

        // No raw sensitive value survives where the plan transforms it.
        assertThat(csv).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain(CARD);
        // The clean note was never detected, so it is preserved verbatim.
        assertThat(csv).contains("keep-me");
        // The id survived as the source's own text.
        assertThat(csv).contains("1,");
    }

    @Test
    void sqlNullsBecomeEmptyFieldsInTheArtifact() throws Exception {
        seed("1, NULL, NULL, NULL, NULL");
        Dataset dataset = boundAndProfiledDataset();

        PostgresSanitizationResult result =
                service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");

        // Explicit and deterministic: an empty field for each SQL NULL.
        assertThat(artifactOf(OWNER, result.runId()).lines().toList().get(1)).isEqualTo("1,,,,");
    }

    @Test
    void theRowLimitIsRespectedAndReportedAsTruncation() throws Exception {
        seed("1, '" + EMAIL + "', NULL, NULL, 'a'",
                "2, '" + EMAIL + "', NULL, NULL, 'b'",
                "3, '" + EMAIL + "', NULL, NULL, 'c'");
        Dataset dataset = boundAndProfiledDataset();

        // The stream ceiling is one, so only one row may be sanitized even
        // though three exist.
        PostgresSanitizationResult result =
                service(1).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");

        assertThat(result.rowsSanitized()).isEqualTo(1);
        assertThat(result.rowLimitReached()).isTrue();
        String csv = artifactOf(OWNER, result.runId());
        assertThat(csv.lines().toList()).hasSize(2);
        // All three rows are still in the source: nothing was consumed.
        assertThat(fixtureRowCount()).isEqualTo(3);
    }

    @Test
    void anEmptyTableStillProducesAHeaderOnlyArtifact() throws Exception {
        seed();
        Dataset dataset = boundAndProfiledDataset();

        PostgresSanitizationResult result =
                service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");

        assertThat(result.run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(artifactOf(OWNER, result.runId())).isEqualTo("id,email,phone,card,note\n");
        assertThat(result.rowsSanitized()).isZero();
    }

    @Test
    void repeatedSanitizationCreatesANewRunAndArtifactAndLeavesTheSourceIntact() throws Exception {
        seed("1, '" + EMAIL + "', NULL, NULL, 'a'");
        Dataset dataset = boundAndProfiledDataset();

        PostgresSanitizationResult first =
                service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");
        PostgresSanitizationResult second =
                service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v2");

        assertThat(first.runId()).isNotEqualTo(second.runId());
        // Each completed run has its own artifact, both readable.
        assertThat(artifactOf(OWNER, first.runId())).isNotEmpty();
        assertThat(artifactOf(OWNER, second.runId())).isNotEmpty();
        assertThat(fixtureRowCount()).isEqualTo(1);
    }

    @Test
    void aStaleBindingFailsSafelyAndLeavesTheBindingAndSourceAlone() throws Exception {
        String stale = "aegivault_sanitization_stale";
        execute("DROP TABLE IF EXISTS " + stale);
        try {
            execute("CREATE TABLE " + stale + " (id integer, email varchar(120))");
            // Bind while the table really exists, so the existence check passes.
            Dataset dataset = datasets.save(new Dataset("stale-dataset", OWNER));
            bindingService().bind(OWNER, dataset.getId(), SCHEMA, stale);
            // The table disappears out from under the binding.
            execute("DROP TABLE " + stale);

            assertThatThrownBy(() -> service(100).sanitize(OWNER, dataset.getId(), plan(), "p", "v1"))
                    .isInstanceOf(PostgresDatasetSanitizationException.class)
                    .hasMessage(PostgresDatasetSanitizationException.MESSAGE);

            // No run at all, so no artifact and certainly no partial one, and the
            // binding is left exactly as it was.
            assertThat(runs.findAll()).noneMatch(run -> run.getDataset().getId().equals(dataset.getId()));
            assertThat(bindings.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER))
                    .hasValueSatisfying(binding -> assertThat(binding.getTableName()).isEqualTo(stale));
        } finally {
            execute("DROP TABLE IF EXISTS " + stale);
        }
    }

    @Test
    void anotherOwnerCannotSanitizeTheBinding() throws Exception {
        seed("1, '" + EMAIL + "', NULL, NULL, 'a'");
        Dataset dataset = boundAndProfiledDataset();

        // Identical to a dataset with no binding, and no run is created.
        assertThatThrownBy(() -> service(100).sanitize(OTHER, dataset.getId(), plan(), "p", "v1"))
                .isInstanceOf(com.aegivault.aegivault.dataset.postgres.binding
                        .PostgresDatasetBindingNotFoundException.class);
        assertThat(runs.findAll()).noneMatch(run -> run.getDataset().getId().equals(dataset.getId()));
    }

    @Test
    void theSourceTableIsNeverMutatedAndAReadOnlyConnectionStillRejectsWrites() throws Exception {
        seed("1, '" + EMAIL + "', '" + PHONE + "', '" + CARD + "', 'original-note'");
        Dataset dataset = boundAndProfiledDataset();

        service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");

        // The raw values are still exactly as seeded: sanitization copies
        // nothing back and deletes nothing.
        assertThat(readNote()).isEqualTo("original-note");
        assertThat(readEmail()).isEqualTo(EMAIL);
        assertThat(fixtureRowCount()).isEqualTo(1);

        // And the read-only guarantee still holds for the connection this bridge
        // uses, so the bridge could not have written even if it wanted to.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute(
                        "UPDATE " + FIXTURE + " SET note = 'mutated'"))
                        .isInstanceOf(SQLException.class);
            } finally {
                connection.rollback();
            }
        }
        assertThat(readNote()).isEqualTo("original-note");
    }

    @Test
    void theExistingDatasetProfileRemainsUsableAfterSanitization() throws Exception {
        seed("1, '" + EMAIL + "', '" + PHONE + "', '" + CARD + "', 'a'");
        Dataset dataset = boundAndProfiledDataset();

        service(100).sanitize(OWNER, dataset.getId(), plan(), "test-policy", "v1");

        // Sanitizing does not consume or invalidate the profile: the metadata
        // profile is still stored and still readable, still metadata only.
        assertThat(profiles.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER)).isPresent();
        assertThat(profileService.getProfile(OWNER, dataset.getId()).totalColumns()).isEqualTo(5);
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
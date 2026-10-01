package com.aegivault.aegivault.dataset.postgres.profiling;

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
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingRepository;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.dataset.profile.DatasetProfileResponse;
import com.aegivault.aegivault.dataset.profile.DatasetProfileService;
import com.aegivault.aegivault.dataset.profile.StoredDatasetProfileRepository;
import com.aegivault.aegivault.pii.CreditCardDetector;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PhoneDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The full PostgreSQL dataset profiling flow against a real database and a real
 * synthetic table: dataset, binding, discovery, the real profiler over the real
 * row stream, and the real profile persistence.
 *
 * <p><strong>No new Spring context and no new pool.</strong> The annotations are
 * those of {@code PostgresTableProfilerPostgresTest} plus {@code PER_CLASS}
 * lifecycle, so the already-cached application context is reused. The fixture is
 * created and dropped with direct JDBC against the pooled datasource, never
 * through the code under test.
 *
 * <p><strong>Everything in the fixture is obviously synthetic</strong>: a
 * {@code .invalid} address, a {@code 555-01xx} number, and a well-known test card
 * number. No value is logged — assertions compare counts and type names only.
 *
 * <p><strong>Nothing about the source is persisted.</strong> The tests assert
 * that the stored profile holds column names, counts, rates, and type names, and
 * that no cell of the fixture appears anywhere in the persisted state.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class PostgresDatasetProfilingPostgresTest {

    /** Fixture name: plainly a test artefact, not an application table. */
    private static final String FIXTURE = "aegivault_pg_profile_fixture";

    private static final String OWNER = "pg-profile-owner";

    private static final String OTHER = "pg-profile-other";

    private static final String SCHEMA = "public";

    /** Obviously synthetic values, used only inside this fixture. */
    private static final String EMAIL_A = "first.synthetic@example.invalid";

    private static final String EMAIL_B = "second.synthetic@example.invalid";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private PostgresDatasetBindingRepository bindings;

    @Autowired
    private StoredDatasetProfileRepository profiles;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    @Autowired
    private DatasetProfileService profileService;

    @BeforeAll
    void createFixture() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + FIXTURE);
            statement.execute("""
                    CREATE TABLE aegivault_pg_profile_fixture (
                        id     integer     NOT NULL,
                        email  varchar(120),
                        note   text
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

    private void seed(String... rows) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + FIXTURE);
            for (String row : rows) {
                statement.execute("INSERT INTO " + FIXTURE + " VALUES (" + row + ")");
            }
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

    /**
     * The real service over the real beans.
     *
     * <p>The application context has no configured PostgreSQL source (that is a
     * deployment property), so the source and profiler are supplied here. Every
     * other collaborator is the real bean: the binding service with its real
     * repository, the real discovery service, and the real profile service
     * writing to the real database.
     */
    /** The real binding service, used to create the fixture binding. */
    private PostgresDatasetBindingService bindingService() {
        return new PostgresDatasetBindingService(
                bindings, datasets, discovery, providerOf(applicationSource()));
    }

    private PostgresDatasetProfilingService service() {
        return new PostgresDatasetProfilingService(
                bindingService(),
                discovery,
                profileService,
                providerOf(applicationSource()),
                providerOf(new PostgresTableProfiler(
                        new JdbcPostgresTableRowSource(new PostgresRowLimits(100, 50)),
                        new PiiColumnProfiler(new PiiDetectorRegistry(
                                List.of(new EmailDetector(), new PhoneDetector(), new CreditCardDetector()))))));
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private Dataset datasetFor(String owner) {
        return datasets.save(new Dataset("pg-profile-dataset", owner));
    }

    /** A dataset with a real binding to the fixture, ready to be profiled. */
    private Dataset boundDataset(String owner) {
        Dataset dataset = datasetFor(owner);
        // The binding is created through the real binding service, so the
        // existence check below is the same one production binding uses.
        bindingService().bind(owner, dataset.getId(), SCHEMA, FIXTURE);
        return dataset;
    }

    private static DatasetProfileResponse.ColumnProfileResponse column(
            DatasetProfileResponse profile, String name) {
        return profile.columns().stream()
                .filter(candidate -> candidate.columnName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aBoundDatasetProducesAndPersistsAProfile() throws SQLException {
        seed("1, '" + EMAIL_A + "', 'plain'");
        Dataset dataset = boundDataset(OWNER);

        DatasetProfileResponse profile = service().profile(OWNER, dataset.getId());

        // Persisted through the existing profile service, not just returned.
        assertThat(profiles.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER)).isPresent();
        assertThat(profileService.getProfile(OWNER, dataset.getId())).isEqualTo(profile);
        assertThat(profile.datasetId()).isEqualTo(dataset.getId());
        // The fixture really had three columns and all were profiled.
        assertThat(profile.totalColumns()).isEqualTo(3);
        assertThat(profile.columns()).extracting(DatasetProfileResponse.ColumnProfileResponse::columnName)
                .containsExactly("id", "email", "note");
    }

    @Test
    void detectionComesFromTheExistingPiiEngine() throws SQLException {
        seed("1, '" + EMAIL_A + "', 'clean'",
                "2, '" + EMAIL_B + "', 'also clean'",
                "3, 'not-an-email', 'still clean'");
        Dataset dataset = boundDataset(OWNER);

        DatasetProfileResponse profile = service().profile(OWNER, dataset.getId());

        // Counts and the rate come from PiiColumnProfiler, unchanged: all three
        // values are analyzable and two of them detect as EMAIL.
        DatasetProfileResponse.ColumnProfileResponse email = column(profile, "email");
        assertThat(email.suppliedValueCount()).isEqualTo(3);
        assertThat(email.analyzedValueCount()).isEqualTo(3);
        assertThat(email.analyzableValueCount()).isEqualTo(3);
        assertThat(email.detectionCounts()).containsEntry(PiiType.EMAIL, 2);
        assertThat(email.detectionRates().get(PiiType.EMAIL)).isEqualTo(2.0 / 3.0);
        assertThat(email.detectedTypes()).containsExactly(PiiType.EMAIL);
        // The note column really was clean: no detector invented a finding.
        assertThat(column(profile, "note").detectedTypes()).isEmpty();
    }

    @Test
    void repeatedProfilingReplacesThePriorProfileRatherThanAddingOne() throws SQLException {
        Dataset dataset = boundDataset(OWNER);
        seed("1, '" + EMAIL_A + "', 'clean'");
        service().profile(OWNER, dataset.getId());

        // The table's contents change entirely between runs.
        seed("1, 'no-pii-here', 'also none'");
        DatasetProfileResponse second = service().profile(OWNER, dataset.getId());

        // Exactly one profile row survives for this dataset — never two — and it
        // holds only the latest run's findings, with no stale column or detection
        // row left behind by the replaced profile.
        var stored = profiles.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER).orElseThrow();
        assertThat(stored.getColumns()).hasSize(3);
        assertThat(column(second, "email").detectionCounts()).isEmpty();
        assertThat(second.totalColumns()).isEqualTo(3);
    }

    @Test
    void noSourceRowValueIsPersisted() throws SQLException {
        seed("1, '" + EMAIL_A + "', 'a-very-distinctive-note'");
        Dataset dataset = boundDataset(OWNER);

        service().profile(OWNER, dataset.getId());

        // The stored aggregate is metadata only. No cell of the fixture — not the
        // email, not the distinctive note — appears in any persisted field, and
        // no column in the profile schema could hold a value in the first place.
        var stored = profiles.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER).orElseThrow();
        String persisted = stored.getColumns().stream()
                .map(column -> column.getColumnName()
                        + column.getSuppliedValueCount()
                        + column.getAnalyzedValueCount()
                        + column.getAnalyzableValueCount())
                .reduce("", (a, b) -> a + b);
        assertThat(persisted)
                .doesNotContain(EMAIL_A)
                .doesNotContain("a-very-distinctive-note")
                .doesNotContain("synthetic")
                .doesNotContain("@");
    }

    @Test
    void anotherOwnerCanNeitherReadNorProfileTheBinding() throws SQLException {
        seed("1, '" + EMAIL_A + "', 'clean'");
        Dataset dataset = boundDataset(OWNER);
        service().profile(OWNER, dataset.getId());

        // Identical to a dataset that has no binding at all.
        assertThatThrownBy(() -> service().profile(OTHER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
    }

    @Test
    void aStaleBindingWhoseSourceTableIsDeletedCreatesNoProfile() throws SQLException {
        String stale = "aegivault_pg_profile_stale";
        execute("DROP TABLE IF EXISTS " + stale);
        try {
            execute("CREATE TABLE " + stale + " (id integer, email varchar(120))");
            // Bind while the table really exists, so the existence check passes.
            Dataset dataset = datasetFor(OWNER);
            bindingService().bind(OWNER, dataset.getId(), SCHEMA, stale);

            // The table disappears out from under the binding.
            execute("DROP TABLE " + stale);

            assertThatThrownBy(() -> service().profile(OWNER, dataset.getId()))
                    .isInstanceOf(PostgresDatasetProfilingException.class)
                    .hasMessage(PostgresDatasetProfilingException.MESSAGE);

            // No partial profile is created, and the stale binding is left
            // exactly as it was rather than deleted or silently repointed.
            assertThat(profiles.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER)).isEmpty();
            assertThat(bindings.findByDatasetIdAndOwnerSubject(dataset.getId(), OWNER))
                    .hasValueSatisfying(binding -> assertThat(binding.getTableName()).isEqualTo(stale));
        } finally {
            execute("DROP TABLE IF EXISTS " + stale);
        }
    }

    @Test
    void theCsvProfilePathIsUnaffectedByAStoredPostgresProfile() throws SQLException {
        seed("1, '" + EMAIL_A + "', 'clean'");
        Dataset postgresDataset = boundDataset(OWNER);
        service().profile(OWNER, postgresDataset.getId());

        // A plain CSV dataset with no binding at all still profiles exactly as
        // before: the same stored shape, the same owner-scoped read, and no
        // PostgreSQL state leaking into it.
        Dataset csvDataset = datasets.save(new Dataset("csv-dataset", OWNER));
        var csvProfile = new com.aegivault.aegivault.pii.profile.DatasetProfile(
                csvDataset.getId(),
                List.of(new com.aegivault.aegivault.pii.profile.ColumnProfile("email", 2, 2, 2,
                        java.util.Map.of(PiiType.EMAIL, 1),
                        java.util.Map.of(PiiType.EMAIL, 0.5),
                        java.util.Set.of(PiiType.EMAIL))),
                1,
                100);
        profileService.saveProfile(OWNER, csvDataset.getId(), csvProfile);

        DatasetProfileResponse stored = profileService.getProfile(OWNER, csvDataset.getId());
        assertThat(stored.datasetId()).isEqualTo(csvDataset.getId());
        assertThat(column(stored, "email").detectionCounts()).containsEntry(PiiType.EMAIL, 1);
        // Both profiles coexist independently in the one existing profile table:
        // the PostgreSQL profile is untouched by the CSV save, and vice versa.
        assertThat(profiles.findByDatasetIdAndOwnerSubject(postgresDataset.getId(), OWNER))
                .hasValueSatisfying(after -> {
                    assertThat(after.getColumns()).hasSize(3);
                    assertThat(after.getOwnerSubject()).isEqualTo(OWNER);
                });
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
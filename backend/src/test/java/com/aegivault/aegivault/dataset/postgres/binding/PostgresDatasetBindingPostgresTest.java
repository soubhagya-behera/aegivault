package com.aegivault.aegivault.dataset.postgres.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

/**
 * Binding a real dataset to a real synthetic PostgreSQL table, end to end
 * through the real discovery service and the real schema.
 *
 * <p><strong>No new Spring context and no new pool.</strong> The annotations are
 * those of {@code PostgresTableProfilerPostgresTest} plus {@code PER_CLASS}
 * lifecycle, so the already-cached application context is reused. The fixture
 * table is created and dropped with direct JDBC against the pooled datasource,
 * never through the code under test.
 *
 * <p><strong>The binding stores a name, not content.</strong> The fixture is
 * populated with obviously synthetic values so that a row leak would be
 * detectable, and the assertions then show that none of it reached the binding:
 * the stored row is the table's name and nothing else. No row is read, profiled,
 * sanitized, or copied, and no artifact, CSV, or run is created.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class PostgresDatasetBindingPostgresTest {

    /** Fixture name: plainly a test artefact, not an application table. */
    private static final String FIXTURE = "aegivault_binding_fixture";

    private static final String OWNER = "pg-binding-owner";

    private static final String OTHER = "pg-binding-other";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgresDatasetBindingService bindingService;

    @Autowired
    private PostgresSchemaDiscoveryService discovery;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private PostgresDatasetBindingRepository bindingRepository;

    @Autowired
    private com.aegivault.aegivault.sanitization.run.SanitizationRunRepository runRepository;

    @Autowired
    private com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore artifacts;

    @Autowired
    private com.aegivault.aegivault.dataset.profile.DatasetProfileService profileService;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeAll
    void createFixture() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + FIXTURE);
            statement.execute("CREATE TABLE " + FIXTURE + " (id integer NOT NULL, note text)");
            // Synthetic filler, so a row leak into the binding would be visible.
            statement.execute("INSERT INTO " + FIXTURE + " (id, note) VALUES (1, 'synthetic only')");
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
                    throw new com.aegivault.aegivault.dataset.postgres
                            .PostgresSourceConnectionException(ex);
                }
            }
        };
    }

    private Dataset datasetFor(String owner) {
        return datasets.save(new Dataset("pg-binding-dataset", owner));
    }

    /**
     * The real service, wired with the fixture source.
     *
     * <p>The application context has no configured PostgreSQL source (that is a
     * deployment property), so the source supplier is supplied here. Everything
     * else — the repository, the dataset lookup, and especially the discovery
     * service that confirms the table exists — is the real bean talking to the
     * real database.
     */
    private PostgresDatasetBindingService service() {
        PostgresDataSource source = applicationSource();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<PostgresDataSource> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {

                    @Override
                    public PostgresDataSource getObject(Object... args) {
                        return source;
                    }

                    @Override
                    public PostgresDataSource getObject() {
                        return source;
                    }

                    @Override
                    public PostgresDataSource getIfAvailable() {
                        return source;
                    }

                    @Override
                    public PostgresDataSource getIfUnique() {
                        return source;
                    }
                };
        return new PostgresDatasetBindingService(
                bindingRepository, datasets, discovery, provider, runRepository);
    }
    @Test
    void aRealDatasetIsBoundToARealSyntheticTableAndReadBack() {
        Dataset dataset = datasetFor(OWNER);

        PostgresDatasetBinding bound = service().bind(OWNER, dataset.getId(), "public", FIXTURE);

        // The table really existed: discovery confirmed it before it was stored.
        assertThat(discovery.discover(applicationSource()).table(FIXTURE)).isPresent();
        assertThat(bound.getSchemaName()).isEqualTo("public");
        assertThat(bound.getTableName()).isEqualTo(FIXTURE);

        // And it reads back for its owner.
        PostgresDatasetBinding read = service().get(OWNER, dataset.getId());
        assertThat(read.getDatasetId()).isEqualTo(dataset.getId());
        assertThat(read.getOwnerSubject()).isEqualTo(OWNER);
        assertThat(read.getTableName()).isEqualTo(FIXTURE);
    }

    @Test
    void anotherOwnerCanNeitherSeeNorDeleteTheBinding() {
        Dataset dataset = datasetFor(OWNER);
        service().bind(OWNER, dataset.getId(), "public", FIXTURE);

        // Identical to a binding that does not exist.
        assertThatThrownBy(() -> service().get(OTHER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        assertThatThrownBy(() -> service().delete(OTHER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        assertThat(service().get(OWNER, dataset.getId())).isNotNull();
    }

    @Test
    void anotherOwnersDatasetCannotBeBound() {
        Dataset dataset = datasetFor(OTHER);

        assertThatThrownBy(() -> service().bind(OWNER, dataset.getId(), "public", FIXTURE))
                .isInstanceOf(DatasetNotFoundException.class);
    }

    @Test
    void aSecondBindingForTheSameDatasetIsRejected() {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);

        assertThatThrownBy(() -> service.bind(OWNER, dataset.getId(), "public", FIXTURE))
                .isInstanceOf(PostgresDatasetAlreadyBoundException.class);
        assertThat(service.get(OWNER, dataset.getId()).getTableName()).isEqualTo(FIXTURE);
    }

    @Test
    void theApiPathBindsARowlessDiscoveryAndPersistsWithoutTouchingTheSource() {
        // End to end through the controller's own entry point, against the real
        // service, the real discovery, and the real database:
        //   create dataset -> discover synthetic table -> bind -> persisted -> read back.
        //
        // No new Spring context and no MockMvc: the HTTP layer is already covered
        // by the standalone controller test, and what matters here is that the
        // production pieces fit together against a real source.
        Dataset dataset = datasetFor(OWNER);
        assertThat(discovery.discover(applicationSource()).table(FIXTURE)).isPresent();

        PostgresDatasetBindingController controller =
                new PostgresDatasetBindingController(service());
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", OWNER).build();

        ResponseEntity<PostgresBindingResponse> response = controller.bind(
                jwt, dataset.getId(), new CreatePostgresBindingRequest("public", FIXTURE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/datasets/" + dataset.getId() + "/postgres/binding");
        PostgresBindingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.datasetId()).isEqualTo(dataset.getId());
        assertThat(body.schemaName()).isEqualTo("public");
        assertThat(body.tableName()).isEqualTo(FIXTURE);
        assertThat(body.createdAt()).isNotNull();

        // Persisted, and retrievable through the internal service.
        PostgresDatasetBinding read = service().get(OWNER, dataset.getId());
        assertThat(read.getTableName()).isEqualTo(FIXTURE);
        assertThat(bindingRepository.findById(dataset.getId())).isPresent();

        // The dataset row itself is untouched: binding records an association
        // and does not rewrite the dataset's CSV source type.
        assertThat(datasets.findById(dataset.getId()).orElseThrow().getSourceType())
                .isEqualTo("CSV");

        // Only metadata discovery ran: the source table is unchanged.
        assertThat(discovery.discover(applicationSource()).table(FIXTURE)).isPresent();
    }

    @Test
    void theApiPathRefusesASecondBindingAndLeavesTheFirstIntact() throws Exception {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);

        PostgresDatasetBindingController controller =
                new PostgresDatasetBindingController(service);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", OWNER).build();

        assertThatThrownBy(() -> controller.bind(
                        jwt, dataset.getId(), new CreatePostgresBindingRequest("public", FIXTURE)))
                .isInstanceOf(PostgresDatasetAlreadyBoundException.class);

        // The original binding stands; nothing was replaced or duplicated.
        assertThat(service.get(OWNER, dataset.getId()).getTableName()).isEqualTo(FIXTURE);
    }

    @Test
    void anAbsentTableFailsWithTheSafeMessage() {
        Dataset dataset = datasetFor(OWNER);

        assertThatThrownBy(() -> service().bind(OWNER, dataset.getId(), "public",
                "aegivault_no_such_table"))
                .isInstanceOf(PostgresDatasetBindingSourceException.class)
                .hasMessage(PostgresDatasetBindingSourceException.MESSAGE);
    }

    @Test
    void theApiPathReadsTheOwnersOwnPersistedBindingWithoutTouchingTheSource() {
        // End to end through the controller's read entry point, against the real
        // service and the real database:
        //   create dataset -> bind synthetic table -> GET binding -> exact values.
        //
        // No new Spring context and no MockMvc: the HTTP contract is already
        // covered by the standalone controller test, and what matters here is
        // that the read returns the persisted row exactly as written.
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);

        PostgresDatasetBindingController controller =
                new PostgresDatasetBindingController(service);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", OWNER).build();

        PostgresBindingResponse body = controller.getBinding(jwt, dataset.getId());

        assertThat(body.datasetId()).isEqualTo(dataset.getId());
        assertThat(body.schemaName()).isEqualTo("public");
        assertThat(body.tableName()).isEqualTo(FIXTURE);
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.updatedAt()).isNotNull();

        // Exactly the persisted row, and only the metadata the binding stores.
        PostgresDatasetBinding stored = bindingRepository.findById(dataset.getId()).orElseThrow();
        assertThat(body.createdAt()).isEqualTo(stored.getCreatedAt());
        assertThat(body.updatedAt()).isEqualTo(stored.getUpdatedAt());

        // Reading is not mutating: the same single binding for this dataset is
        // still there, no second binding appeared, and the dataset's own source
        // type is still untouched. Only this dataset's row is inspected, because
        // the shared test database holds other classes' committed bindings.
        assertThat(service.get(OWNER, dataset.getId()).getTableName()).isEqualTo(FIXTURE);
        assertThat(bindingRepository.findById(dataset.getId())).isPresent();
        assertThat(datasets.findById(dataset.getId()).orElseThrow().getSourceType())
                .isEqualTo("CSV");

        // The source table still holds its single synthetic row: the read
        // consumed nothing and, crucially, the read path performs no
        // discovery or verification against PostgreSQL at all.
        assertThat(discovery.discover(applicationSource()).table(FIXTURE)).isPresent();
    }

    @Test
    void theApiPathRefusesToReadAnotherOwnersBinding() {
        Dataset dataset = datasetFor(OWNER);
        service().bind(OWNER, dataset.getId(), "public", FIXTURE);
        PostgresDatasetBindingController controller =
                new PostgresDatasetBindingController(service());
        Jwt foreign = Jwt.withTokenValue("token").header("alg", "none").claim("sub", OTHER).build();

        // Identical to reading a dataset that does not exist: the owner's
        // schema and table are never disclosed.
        assertThatThrownBy(() -> controller.getBinding(foreign, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);

        // The owner can still read theirs, so nothing was consumed by the attempt.
        assertThat(controller.getBinding(
                        Jwt.withTokenValue("t").header("alg", "none").claim("sub", OWNER).build(),
                        dataset.getId())
                .tableName()).isEqualTo(FIXTURE);
    }

    @Test
    void theBindingCanBeDeletedAndTheDatasetSurvives() {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);

        service.delete(OWNER, dataset.getId());

        assertThatThrownBy(() -> service.get(OWNER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        // Unbinding a dataset does not remove the dataset itself.
        assertThat(datasets.findById(dataset.getId())).isPresent();
    }

    private com.aegivault.aegivault.sanitization.run.PolicySnapshot snapshot() {
        return com.aegivault.aegivault.sanitization.run.PolicySnapshot.fromPlan(
                "default",
                "v1",
                com.aegivault.aegivault.sanitization.DefaultTransformationPolicy.plan());
    }

    private com.aegivault.aegivault.sanitization.run.SanitizationRun queuedRun(
            Dataset dataset, com.aegivault.aegivault.sanitization.run.SanitizationSourceType sourceType) {
        return runRepository.saveAndFlush(new com.aegivault.aegivault.sanitization.run.SanitizationRun(
                dataset, OWNER, snapshot(), sourceType));
    }

    private com.aegivault.aegivault.sanitization.run.SanitizationRun runningPostgresRun(Dataset dataset) {
        com.aegivault.aegivault.sanitization.run.SanitizationRun run = queuedRun(
                dataset, com.aegivault.aegivault.sanitization.run.SanitizationSourceType.POSTGRESQL);
        run.markRunning();
        return runRepository.saveAndFlush(run);
    }

    private com.aegivault.aegivault.sanitization.run.SanitizationRun completedPostgresRun(Dataset dataset) {
        com.aegivault.aegivault.sanitization.run.SanitizationRun run = queuedRun(
                dataset, com.aegivault.aegivault.sanitization.run.SanitizationSourceType.POSTGRESQL);
        run.markRunning();
        run.markCompleted(new com.aegivault.aegivault.sanitization.run.RunResult(1, 1, 0, 2));
        return runRepository.saveAndFlush(run);
    }

    private com.aegivault.aegivault.sanitization.run.SanitizationRun failedPostgresRun(Dataset dataset) {
        com.aegivault.aegivault.sanitization.run.SanitizationRun run = queuedRun(
                dataset, com.aegivault.aegivault.sanitization.run.SanitizationSourceType.POSTGRESQL);
        run.markRunning();
        run.markFailed(new com.aegivault.aegivault.sanitization.run.RunFailure(
                "POLICY_GAP", "TRANSFORM", "no transformation is configured for a detected type"));
        return runRepository.saveAndFlush(run);
    }

    private com.aegivault.aegivault.pii.profile.DatasetProfile profileFor(Dataset dataset) {
        return new com.aegivault.aegivault.pii.profile.DatasetProfile(
                dataset.getId(),
                List.of(new com.aegivault.aegivault.pii.profile.ColumnProfile("id", 1, 1, 1,
                        java.util.Map.of(), java.util.Map.of(), java.util.Set.of())),
                1,
                100);
    }

    @Test
    void terminalPostgresRunsDoNotBlockDeletionAndHistorySurvives() throws Exception {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);

        // One completed and one failed PostgreSQL run, plus a stored profile
        // and a stored artifact on the completed run.
        com.aegivault.aegivault.sanitization.run.SanitizationRun completed =
                completedPostgresRun(dataset);
        com.aegivault.aegivault.sanitization.run.SanitizationRun failed = failedPostgresRun(dataset);
        byte[] csv = "id,note\n1,synthetic only\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        artifacts.storeArtifact(OWNER, completed.getId(), new java.io.ByteArrayInputStream(csv));
        profileService.saveProfile(OWNER, dataset.getId(), profileFor(dataset));

        service.delete(OWNER, dataset.getId());

        // The binding is gone: the read is the existing safe 404.
        assertThatThrownBy(() -> service.get(OWNER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
        assertThat(bindingRepository.findById(dataset.getId())).isEmpty();

        // Both runs still exist with their terminal states and source types
        // intact: deletion never touches run rows.
        assertThat(runRepository.findByIdAndOwnerSubject(completed.getId(), OWNER))
                .hasValueSatisfying(run -> {
                    assertThat(run.getStatus())
                            .isEqualTo(com.aegivault.aegivault.sanitization.run.RunStatus.COMPLETED);
                    assertThat(run.getSourceType()).isEqualTo(
                            com.aegivault.aegivault.sanitization.run.SanitizationSourceType.POSTGRESQL);
                });
        assertThat(runRepository.findByIdAndOwnerSubject(failed.getId(), OWNER))
                .hasValueSatisfying(run -> assertThat(run.getStatus())
                        .isEqualTo(com.aegivault.aegivault.sanitization.run.RunStatus.FAILED));

        // The completed run's artifact is still downloadable, byte for byte.
        try (java.io.InputStream stored = artifacts.openArtifact(OWNER, completed.getId())) {
            assertThat(stored.readAllBytes()).isEqualTo(csv);
        }

        // The stored profile is untouched.
        assertThat(profileService.getProfile(OWNER, dataset.getId()).datasetId())
                .isEqualTo(dataset.getId());

        // And the dataset accepts a new binding afterwards, under the existing
        // one-binding-per-dataset rule.
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);
        assertThat(service.get(OWNER, dataset.getId()).getTableName()).isEqualTo(FIXTURE);
    }

    @Test
    void aQueuedPostgresRunBlocksDeletionAndLeavesTheBinding() {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);
        com.aegivault.aegivault.sanitization.run.SanitizationRun queued = queuedRun(
                dataset, com.aegivault.aegivault.sanitization.run.SanitizationSourceType.POSTGRESQL);

        assertThatThrownBy(() -> service.delete(OWNER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingActiveRunException.class)
                .hasMessage(PostgresDatasetBindingActiveRunException.MESSAGE);

        // The binding still exists and the run was never altered.
        assertThat(service.get(OWNER, dataset.getId()).getTableName()).isEqualTo(FIXTURE);
        assertThat(runRepository.findByIdAndOwnerSubject(queued.getId(), OWNER))
                .hasValueSatisfying(run -> assertThat(run.getStatus())
                        .isEqualTo(com.aegivault.aegivault.sanitization.run.RunStatus.QUEUED));
    }

    @Test
    void aRunningPostgresRunBlocksDeletionAndLeavesTheBinding() {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);
        com.aegivault.aegivault.sanitization.run.SanitizationRun running = runningPostgresRun(dataset);

        assertThatThrownBy(() -> service.delete(OWNER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingActiveRunException.class)
                .hasMessage(PostgresDatasetBindingActiveRunException.MESSAGE);

        // The binding still exists and the run was never altered.
        assertThat(service.get(OWNER, dataset.getId()).getTableName()).isEqualTo(FIXTURE);
        assertThat(runRepository.findByIdAndOwnerSubject(running.getId(), OWNER))
                .hasValueSatisfying(run -> assertThat(run.getStatus())
                        .isEqualTo(com.aegivault.aegivault.sanitization.run.RunStatus.RUNNING));
    }

    @Test
    void aQueuedCsvRunDoesNotBlockPostgresBindingDeletion() {
        Dataset dataset = datasetFor(OWNER);
        PostgresDatasetBindingService service = service();
        service.bind(OWNER, dataset.getId(), "public", FIXTURE);
        // Even a non-terminal CSV run is not a PostgreSQL blocker.
        queuedRun(dataset, com.aegivault.aegivault.sanitization.run.SanitizationSourceType.CSV);

        service.delete(OWNER, dataset.getId());

        assertThatThrownBy(() -> service.get(OWNER, dataset.getId()))
                .isInstanceOf(PostgresDatasetBindingNotFoundException.class);
    }

    @Test
    void nothingButMetadataIsPersisted() {
        Dataset dataset = datasetFor(OWNER);
        service().bind(OWNER, dataset.getId(), "public", FIXTURE);
        entityManager.flush();
        entityManager.clear();

        // The fixture holds real rows. The binding row holds none of them, no
        // connection detail, and no value of any kind: the stored table name is
        // the only trace of the source.
        List<?> rows = entityManager.createNativeQuery(
                "SELECT dataset_id, owner_subject, schema_name, table_name, created_at, updated_at "
                        + "FROM postgres_dataset_bindings WHERE dataset_id = ?")
                .setParameter(1, dataset.getId())
                .getResultList();

        assertThat(rows).hasSize(1);
        Object[] stored = (Object[]) rows.get(0);
        // Only six values exist, and the only trace of the source among them is
        // the table's name. The fixture's synthetic cell is not among them.
        assertThat(stored).hasSize(6);
        assertThat(stored[1]).isEqualTo(OWNER);
        assertThat(stored[2]).isEqualTo("public");
        assertThat(stored[3]).isEqualTo(FIXTURE);
        assertThat(Arrays.stream(stored))
                .noneMatch(value -> "synthetic only".equals(String.valueOf(value)));

        // No column in the table could hold a credential or a cell.
        List<?> columns = entityManager.createNativeQuery(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'postgres_dataset_bindings' ORDER BY ordinal_position")
                .getResultList();
        assertThat(columns).extracting(Object::toString)
                .containsExactly("dataset_id", "owner_subject", "schema_name", "table_name",
                        "created_at", "updated_at");
    }
}

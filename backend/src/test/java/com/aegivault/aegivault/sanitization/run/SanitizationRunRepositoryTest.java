package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Proves the real persistence path for runs: Flyway V3 migration applies
 * against PostgreSQL, the {@link SanitizationRun} mapping validates, the
 * dataset FK and owner scoping behave, lifecycle state persists, optimistic
 * locking rejects stale updates, and the safety CHECKs hold. No embedded
 * database is used.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SanitizationRunRepositoryTest {

    @Autowired
    private SanitizationRunRepository runs;

    @Autowired
    private DatasetRepository datasets;

    @PersistenceContext
    private EntityManager entities;

    private Dataset dataset(String name, String owner) {
        return datasets.saveAndFlush(new Dataset(name, owner));
    }

    private PolicySnapshot snapshot() {
        return PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan());
    }

    private SanitizationRun run(Dataset dataset, String owner) {
        return runs.saveAndFlush(new SanitizationRun(dataset, owner, snapshot()));
    }

    @Test
    void persistAndRetrieveRun() {
        Dataset dataset = dataset("customers.csv", "owner-1");
        SanitizationRun saved = run(dataset, "owner-1");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        SanitizationRun found = runs.findById(saved.getId()).orElseThrow();
        assertThat(found.getDataset().getId()).isEqualTo(dataset.getId());
        assertThat(found.getOwnerSubject()).isEqualTo("owner-1");
        assertThat(found.getPolicyName()).isEqualTo("default");
        assertThat(found.getPolicyVersion()).isEqualTo("v1");
        assertThat(found.getPolicySnapshot()).isEqualTo(snapshot().toJson());
    }

    @Test
    void ownerScopedLookupFindsOwnRunOnly() {
        Dataset dataset = dataset("a.csv", "owner-1");
        SanitizationRun saved = run(dataset, "owner-1");

        assertThat(runs.findByIdAndOwnerSubject(saved.getId(), "owner-1")).isPresent();
        assertThat(runs.findByIdAndOwnerSubject(saved.getId(), "owner-2")).isEmpty();
        assertThat(runs.findByIdAndOwnerSubject(UUID.randomUUID(), "owner-1")).isEmpty();
    }

    @Test
    void datasetListingIsOwnerScoped() {
        Dataset first = dataset("one.csv", "owner-1");
        Dataset second = dataset("two.csv", "owner-1");
        Dataset foreign = dataset("foreign.csv", "owner-2");
        run(first, "owner-1");
        run(first, "owner-1");
        run(second, "owner-1");
        run(foreign, "owner-2");

        List<SanitizationRun> own = runs.findByDatasetIdAndOwnerSubject(first.getId(), "owner-1");
        assertThat(own).hasSize(2);

        assertThat(runs.findByDatasetIdAndOwnerSubject(second.getId(), "owner-1")).hasSize(1);
        assertThat(runs.findByDatasetIdAndOwnerSubject(first.getId(), "owner-2")).isEmpty();
        assertThat(runs.findByDatasetIdAndOwnerSubject(UUID.randomUUID(), "owner-1")).isEmpty();
    }

    @Test
    void lifecycleStatePersistsAcrossReloads() {
        SanitizationRun saved = run(dataset("a.csv", "owner-1"), "owner-1");
        UUID id = saved.getId();

        SanitizationRun loaded = runs.findById(id).orElseThrow();
        loaded.markRunning();
        runs.saveAndFlush(loaded);

        SanitizationRun running = runs.findById(id).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(running.getStartedAt()).isNotNull();
        assertThat(running.getVersion()).isEqualTo(1L);

        running.markCompleted(new RunResult(50L, 48L, 2L, 4));
        runs.saveAndFlush(running);

        SanitizationRun completed = runs.findById(id).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.getInputRowCount()).isEqualTo(50L);
        assertThat(completed.getOutputRowCount()).isEqualTo(48L);
        assertThat(completed.getBlankRowsSkipped()).isEqualTo(2L);
        assertThat(completed.getColumnCount()).isEqualTo(4);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getPolicySnapshot()).isEqualTo(snapshot().toJson());
    }

    @Test
    void failureMetadataPersists() {
        SanitizationRun saved = run(dataset("a.csv", "owner-1"), "owner-1");
        saved.markRunning();
        saved.markFailed(new RunFailure("POLICY_GAP", "TRANSFORM", "Type PHONE has no planned strategy."));
        runs.saveAndFlush(saved);

        SanitizationRun failed = runs.findById(saved.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("POLICY_GAP");
        assertThat(failed.getErrorStage()).isEqualTo("TRANSFORM");
        assertThat(failed.getErrorMessage()).isEqualTo("Type PHONE has no planned strategy.");
        assertThat(failed.getCompletedAt()).isNotNull();
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        SanitizationRun saved = run(dataset("a.csv", "owner-1"), "owner-1");
        UUID id = saved.getId();
        entities.clear();

        SanitizationRun first = runs.findById(id).orElseThrow();
        entities.clear();
        SanitizationRun second = runs.findById(id).orElseThrow();
        entities.clear();

        first.markRunning();
        runs.saveAndFlush(first);

        second.markRunning();
        assertThatThrownBy(() -> runs.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void datasetWithRunsCannotBeDeleted() {
        Dataset dataset = dataset("a.csv", "owner-1");
        run(dataset, "owner-1");
        entities.clear();

        assertThatThrownBy(() -> {
            datasets.delete(datasets.findById(dataset.getId()).orElseThrow());
            datasets.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runAgainstMissingDatasetIsRejected() {
        assertThatThrownBy(() -> entities.createNativeQuery(
                                "INSERT INTO sanitization_runs (dataset_id, owner_subject, status,"
                                        + " policy_name, policy_version, policy_snapshot, version)"
                                        + " VALUES ('" + UUID.randomUUID() + "', 'owner-1',"
                                        + " 'QUEUED', 'default', 'v1', '{}', 0)")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void unknownStatusIsRejected() {
        Dataset dataset = dataset("a.csv", "owner-1");

        assertThatThrownBy(() -> entities.createNativeQuery(
                                "INSERT INTO sanitization_runs (dataset_id, owner_subject, status,"
                                        + " policy_name, policy_version, policy_snapshot, version)"
                                        + " VALUES ('" + dataset.getId() + "', 'owner-1',"
                                        + " 'PAUSED', 'default', 'v1', '{}', 0)")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void errorFieldsOutsideFailedStateAreRejected() {
        Dataset dataset = dataset("a.csv", "owner-1");

        assertThatThrownBy(() -> entities.createNativeQuery(
                                "INSERT INTO sanitization_runs (dataset_id, owner_subject, status,"
                                        + " policy_name, policy_version, policy_snapshot,"
                                        + " error_code, error_stage, error_message, version)"
                                        + " VALUES ('" + dataset.getId() + "', 'owner-1',"
                                        + " 'QUEUED', 'default', 'v1', '{}',"
                                        + " 'IO_ERROR', 'WRITE', 'Output unavailable.', 0)")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void terminalStateWithoutCompletionTimeIsRejected() {
        Dataset dataset = dataset("a.csv", "owner-1");

        assertThatThrownBy(() -> entities.createNativeQuery(
                                "INSERT INTO sanitization_runs (dataset_id, owner_subject, status,"
                                        + " policy_name, policy_version, policy_snapshot,"
                                        + " error_code, error_stage, error_message, version)"
                                        + " VALUES ('" + dataset.getId() + "', 'owner-1',"
                                        + " 'FAILED', 'default', 'v1', '{}',"
                                        + " 'IO_ERROR', 'WRITE', 'Output unavailable.', 0)")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void negativeCountsAreRejected() {
        Dataset dataset = dataset("a.csv", "owner-1");

        assertThatThrownBy(() -> entities.createNativeQuery(
                                "INSERT INTO sanitization_runs (dataset_id, owner_subject, status,"
                                        + " policy_name, policy_version, policy_snapshot,"
                                        + " input_row_count, version)"
                                        + " VALUES ('" + dataset.getId() + "', 'owner-1',"
                                        + " 'QUEUED', 'default', 'v1', '{}', -1, 0)")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nullOwnerIsRejected() {
        Dataset dataset = dataset("a.csv", "owner-1");

        assertThatThrownBy(() -> entities.createNativeQuery(
                                "INSERT INTO sanitization_runs (dataset_id, owner_subject, status,"
                                        + " policy_name, policy_version, policy_snapshot, version)"
                                        + " VALUES ('" + dataset.getId() + "', NULL,"
                                        + " 'QUEUED', 'default', 'v1', '{}', 0)")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }
}

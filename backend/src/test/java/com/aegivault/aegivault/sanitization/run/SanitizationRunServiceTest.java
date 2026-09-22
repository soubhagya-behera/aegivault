package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-level run lifecycle against real PostgreSQL: creation freezes the
 * policy, ownership is enforced on every operation, transitions follow the
 * state machine, failure handling stays metadata-only, and a persisted run
 * never follows later policy changes. Each test rolls back.
 */
@SpringBootTest
@Transactional
class SanitizationRunServiceTest {

    @Autowired
    private SanitizationRunService service;

    @Autowired
    private SanitizationRunRepository runs;

    @Autowired
    private DatasetRepository datasets;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    private Dataset dataset(String owner) {
        return datasets.save(new Dataset("customers.csv", owner));
    }

    private TransformationPlan defaultPlan() {
        return DefaultTransformationPolicy.plan();
    }

    private SanitizationRunView create(String owner, Dataset dataset) {
        return service.createRun(owner, dataset.getId(), defaultPlan(), "default", "v1");
    }

    @Test
    void createRunPersistsQueuedRunWithFrozenPolicy() {
        String owner = owner();
        Dataset dataset = dataset(owner);

        SanitizationRunView view = create(owner, dataset);

        assertThat(view.id()).isNotNull();
        assertThat(view.datasetId()).isEqualTo(dataset.getId());
        assertThat(view.ownerSubject()).isEqualTo(owner);
        assertThat(view.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(view.policyName()).isEqualTo("default");
        assertThat(view.policyVersion()).isEqualTo("v1");
        assertThat(view.policySnapshot()).isEqualTo(
                PolicySnapshot.fromPlan("default", "v1", defaultPlan()).toJson());
        assertThat(view.inputRowCount()).isNull();
        assertThat(view.startedAt()).isNull();
        assertThat(view.completedAt()).isNull();
        assertThat(view.errorCode()).isNull();
        assertThat(view.version()).isEqualTo(0L);
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.updatedAt()).isNotNull();
    }

    @Test
    void createRunAgainstMissingDatasetIsRejected() {
        String owner = owner();

        assertThatThrownBy(() -> service.createRun(owner, UUID.randomUUID(), defaultPlan(), "default", "v1"))
                .isInstanceOf(ReferencedDatasetNotFoundException.class)
                .hasMessage("Dataset not found.");
    }

    @Test
    void createRunAgainstAnotherOwnersDatasetIsRejectedIdentically() {
        String ownerA = owner();
        String ownerB = owner();
        Dataset dataset = dataset(ownerA);
        UUID missing = UUID.randomUUID();

        String foreignMessage = null;
        try {
            service.createRun(ownerB, dataset.getId(), defaultPlan(), "default", "v1");
        } catch (ReferencedDatasetNotFoundException ex) {
            foreignMessage = ex.getMessage();
        }
        String missingMessage = null;
        try {
            service.createRun(ownerB, missing, defaultPlan(), "default", "v1");
        } catch (ReferencedDatasetNotFoundException ex) {
            missingMessage = ex.getMessage();
        }

        assertThat(foreignMessage).isNotNull();
        assertThat(foreignMessage).isEqualTo(missingMessage);
    }

    @Test
    void createRunRejectsInvalidInput() {
        String owner = owner();
        Dataset dataset = dataset(owner);

        assertThatThrownBy(() -> service.createRun("  ", dataset.getId(), defaultPlan(), "default", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createRun(owner, null, defaultPlan(), "default", "v1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.createRun(owner, dataset.getId(), null, "default", "v1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.createRun(owner, dataset.getId(), defaultPlan(), " ", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createRun(owner, dataset.getId(), defaultPlan(), "default", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getReturnsOwnRun() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));

        SanitizationRunView view = service.get(owner, created.id());

        assertThat(view).isEqualTo(created);
    }

    @Test
    void getOfMissingAndForeignRunIsIdentical() {
        String ownerA = owner();
        String ownerB = owner();
        SanitizationRunView foreign = create(ownerA, dataset(ownerA));

        String foreignMessage = null;
        try {
            service.get(ownerB, foreign.id());
        } catch (SanitizationRunNotFoundException ex) {
            foreignMessage = ex.getMessage();
        }
        String missingMessage = null;
        try {
            service.get(ownerB, UUID.randomUUID());
        } catch (SanitizationRunNotFoundException ex) {
            missingMessage = ex.getMessage();
        }

        assertThat(foreignMessage).isNotNull();
        assertThat(foreignMessage).isEqualTo(missingMessage);
        assertThat(foreignMessage).isEqualTo("Sanitization run not found.");
    }

    @Test
    void listByDatasetReturnsOnlyCallersRuns() {
        String ownerA = owner();
        String ownerB = owner();
        Dataset first = dataset(ownerA);
        Dataset second = dataset(ownerA);
        create(ownerA, first);
        create(ownerA, first);
        create(ownerA, second);

        assertThat(service.listByDataset(ownerA, first.getId())).hasSize(2);
        assertThat(service.listByDataset(ownerA, second.getId())).hasSize(1);
        assertThat(service.listByDataset(ownerB, first.getId())).isEmpty();
    }

    @Test
    void startRunMovesQueuedToRunning() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));

        SanitizationRunView started = service.startRun(owner, created.id());

        assertThat(started.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(started.startedAt()).isNotNull();
        assertThat(started.completedAt()).isNull();
        assertThat(started.version()).isEqualTo(1L);
    }

    @Test
    void startRunTwiceIsRejected() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));
        service.startRun(owner, created.id());

        assertThatThrownBy(() -> service.startRun(owner, created.id()))
                .isInstanceOf(InvalidRunTransitionException.class)
                .hasMessageContaining("RUNNING");

        assertThat(service.get(owner, created.id()).status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void completeRunRecordsCounts() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));
        service.startRun(owner, created.id());

        SanitizationRunView completed =
                service.completeRun(owner, created.id(), new RunResult(120L, 118L, 2L, 6));

        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.inputRowCount()).isEqualTo(120L);
        assertThat(completed.outputRowCount()).isEqualTo(118L);
        assertThat(completed.blankRowsSkipped()).isEqualTo(2L);
        assertThat(completed.columnCount()).isEqualTo(6);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.errorCode()).isNull();
        assertThat(completed.version()).isEqualTo(2L);
    }

    @Test
    void completeRunWithoutStartIsRejected() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));

        assertThatThrownBy(
                        () -> service.completeRun(owner, created.id(), new RunResult(1L, 1L, 0L, 1)))
                .isInstanceOf(InvalidRunTransitionException.class);

        assertThat(service.get(owner, created.id()).status()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void failRunRecordsSafeMetadata() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));
        service.startRun(owner, created.id());

        SanitizationRunView failed = service.failRun(
                owner, created.id(), new RunFailure("CSV_PARSE_ERROR", "TOKENIZE", "Row 7 is ragged."));

        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("CSV_PARSE_ERROR");
        assertThat(failed.errorStage()).isEqualTo("TOKENIZE");
        assertThat(failed.errorMessage()).isEqualTo("Row 7 is ragged.");
        assertThat(failed.completedAt()).isNotNull();
        assertThat(failed.inputRowCount()).isNull();
        assertThat(failed.version()).isEqualTo(2L);
    }

    @Test
    void failRunWithoutStartIsRejected() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));

        assertThatThrownBy(() -> service.failRun(
                        owner, created.id(), new RunFailure("IO_ERROR", "WRITE", "Output unavailable.")))
                .isInstanceOf(InvalidRunTransitionException.class);
    }

    @Test
    void terminalRunsAcceptNoFurtherTransitions() {
        String owner = owner();
        SanitizationRunView completed = create(owner, dataset(owner));
        service.startRun(owner, completed.id());
        service.completeRun(owner, completed.id(), new RunResult(1L, 1L, 0L, 1));

        assertThatThrownBy(() -> service.startRun(owner, completed.id()))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThatThrownBy(() -> service.completeRun(owner, completed.id(), new RunResult(1L, 1L, 0L, 1)))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThatThrownBy(() -> service.failRun(
                        owner, completed.id(), new RunFailure("IO_ERROR", "WRITE", "Output unavailable.")))
                .isInstanceOf(InvalidRunTransitionException.class);

        SanitizationRunView failed = create(owner, dataset(owner));
        service.startRun(owner, failed.id());
        service.failRun(owner, failed.id(), new RunFailure("IO_ERROR", "WRITE", "Output unavailable."));

        assertThatThrownBy(() -> service.completeRun(owner, failed.id(), new RunResult(1L, 1L, 0L, 1)))
                .isInstanceOf(InvalidRunTransitionException.class);
    }

    @Test
    void crossOwnerLifecycleOperationsAreRejected() {
        String ownerA = owner();
        String ownerB = owner();
        SanitizationRunView foreign = create(ownerA, dataset(ownerA));

        assertThatThrownBy(() -> service.startRun(ownerB, foreign.id()))
                .isInstanceOf(SanitizationRunNotFoundException.class);
        assertThatThrownBy(() -> service.completeRun(ownerB, foreign.id(), new RunResult(1L, 1L, 0L, 1)))
                .isInstanceOf(SanitizationRunNotFoundException.class);
        assertThatThrownBy(() -> service.failRun(
                        ownerB, foreign.id(), new RunFailure("IO_ERROR", "WRITE", "Output unavailable.")))
                .isInstanceOf(SanitizationRunNotFoundException.class);

        assertThat(service.get(ownerA, foreign.id()).status()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void persistedRunNeverFollowsLaterPolicyChanges() {
        String owner = owner();
        Dataset dataset = dataset(owner);
        TransformationPlan original = TransformationPlan.of(List.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL),
                new TransformationRule(PiiType.PHONE, TransformationStrategy.SYNTHETIC_PHONE)));
        SanitizationRunView created = service.createRun(owner, dataset.getId(), original, "default", "v1");
        String frozen = created.policySnapshot();

        TransformationPlan changed = TransformationPlan.of(List.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK),
                new TransformationRule(PiiType.PHONE, TransformationStrategy.REDACT),
                new TransformationRule(PiiType.ADDRESS, TransformationStrategy.KEEP)));
        service.createRun(owner, dataset.getId(), changed, "default", "v2");

        SanitizationRunView reloaded = service.get(owner, created.id());
        assertThat(reloaded.policySnapshot()).isEqualTo(frozen);
        assertThat(reloaded.policySnapshot())
                .isEqualTo(PolicySnapshot.fromPlan("default", "v1", original).toJson());
        assertThat(reloaded.policyVersion()).isEqualTo("v1");
    }

    @Test
    void persistedStateHoldsNoRawDataSecretsOrTraces() {
        String owner = owner();
        SanitizationRunView created = create(owner, dataset(owner));
        service.startRun(owner, created.id());
        service.failRun(
                owner, created.id(), new RunFailure("POLICY_GAP", "TRANSFORM", "Type JWT has no strategy."));

        SanitizationRun stored = runs.findById(created.id()).orElseThrow();

        assertThat(stored.getPolicySnapshot()).doesNotContain("sk-", "eyJ", "@", "password");
        assertThat(stored.getErrorMessage()).isEqualTo("Type JWT has no strategy.");
        assertThat(stored.getErrorMessage()).doesNotContain("at com.aegivault", "Exception in thread");
        assertThat(stored.getErrorCode()).isEqualTo("POLICY_GAP");
        assertThat(stored.getInputRowCount()).isNull();
    }

    @Test
    void adminHasNoOwnershipBypass() {
        String userOwner = owner();
        String adminOwner = owner();
        SanitizationRunView userRun = create(userOwner, dataset(userOwner));

        assertThatThrownBy(() -> service.get(adminOwner, userRun.id()))
                .isInstanceOf(SanitizationRunNotFoundException.class);
        assertThatThrownBy(() -> service.startRun(adminOwner, userRun.id()))
                .isInstanceOf(SanitizationRunNotFoundException.class);
    }
}

package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure domain tests for {@link SanitizationRun}: initial state, valid and
 * invalid transitions, terminal states, frozen policy fields, and the
 * metadata-only result/failure records. No Spring, no database.
 */
class SanitizationRunTest {

    private static SanitizationRun newRun() {
        Dataset dataset = new Dataset("customers.csv", "owner-1");
        PolicySnapshot snapshot = PolicySnapshot.fromPlan(
                "default", "v1", DefaultTransformationPolicy.plan());
        return new SanitizationRun(dataset, "owner-1", snapshot);
    }

    @Test
    void newRunStartsQueuedWithFrozenPolicyAndEmptyOutcome() {
        SanitizationRun run = newRun();

        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getOwnerSubject()).isEqualTo("owner-1");
        assertThat(run.getDataset().getName()).isEqualTo("customers.csv");
        assertThat(run.getPolicyName()).isEqualTo("default");
        assertThat(run.getPolicyVersion()).isEqualTo("v1");
        assertThat(run.getPolicySnapshot()).isEqualTo(
                PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan()).toJson());
        assertThat(run.getInputRowCount()).isNull();
        assertThat(run.getOutputRowCount()).isNull();
        assertThat(run.getBlankRowsSkipped()).isNull();
        assertThat(run.getColumnCount()).isNull();
        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getErrorCode()).isNull();
        assertThat(run.getErrorStage()).isNull();
        assertThat(run.getErrorMessage()).isNull();
    }

    @Test
    void markRunningMovesQueuedToRunning() {
        SanitizationRun run = newRun();

        run.markRunning();

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void markCompletedRecordsCounts() {
        SanitizationRun run = newRun();
        run.markRunning();

        run.markCompleted(new RunResult(100L, 98L, 2L, 5));

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getInputRowCount()).isEqualTo(100L);
        assertThat(run.getOutputRowCount()).isEqualTo(98L);
        assertThat(run.getBlankRowsSkipped()).isEqualTo(2L);
        assertThat(run.getColumnCount()).isEqualTo(5);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getErrorCode()).isNull();
    }

    @Test
    void markFailedRecordsSafeMetadata() {
        SanitizationRun run = newRun();
        run.markRunning();

        run.markFailed(new RunFailure("CSV_PARSE_ERROR", "TOKENIZE", "Row 42 has 3 columns, expected 5."));

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("CSV_PARSE_ERROR");
        assertThat(run.getErrorStage()).isEqualTo("TOKENIZE");
        assertThat(run.getErrorMessage()).isEqualTo("Row 42 has 3 columns, expected 5.");
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getInputRowCount()).isNull();
    }

    @Test
    void completingAQueuedRunIsRejected() {
        SanitizationRun run = newRun();

        assertThatThrownBy(() -> run.markCompleted(new RunResult(10L, 10L, 0L, 2)))
                .isInstanceOf(InvalidRunTransitionException.class)
                .hasMessageContaining("QUEUED")
                .hasMessageContaining("COMPLETED");
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void failingAQueuedRunIsRejected() {
        SanitizationRun run = newRun();

        assertThatThrownBy(() -> run.markFailed(new RunFailure("IO_ERROR", "WRITE", "Output unavailable.")))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void startingTwiceIsRejected() {
        SanitizationRun run = newRun();
        run.markRunning();

        assertThatThrownBy(run::markRunning).isInstanceOf(InvalidRunTransitionException.class);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void terminalStatesAcceptNoFurtherTransitions() {
        SanitizationRun completed = newRun();
        completed.markRunning();
        completed.markCompleted(new RunResult(1L, 1L, 0L, 1));

        assertThatThrownBy(completed::markRunning).isInstanceOf(InvalidRunTransitionException.class);
        assertThatThrownBy(() -> completed.markCompleted(new RunResult(1L, 1L, 0L, 1)))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThatThrownBy(() -> completed.markFailed(new RunFailure("X", "Y", "Z")))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);

        SanitizationRun failed = newRun();
        failed.markRunning();
        failed.markFailed(new RunFailure("X", "Y", "Z"));

        assertThatThrownBy(failed::markRunning).isInstanceOf(InvalidRunTransitionException.class);
        assertThatThrownBy(() -> failed.markCompleted(new RunResult(1L, 1L, 0L, 1)))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThatThrownBy(() -> failed.markFailed(new RunFailure("X", "Y", "Z")))
                .isInstanceOf(InvalidRunTransitionException.class);
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void nullResultAndFailureAreRejected() {
        SanitizationRun run = newRun();
        run.markRunning();

        assertThatThrownBy(() -> run.markCompleted(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> run.markFailed(null)).isInstanceOf(NullPointerException.class);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void invalidConstructionIsRejected() {
        Dataset dataset = new Dataset("customers.csv", "owner-1");
        PolicySnapshot snapshot =
                PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan());

        assertThatThrownBy(() -> new SanitizationRun(null, "owner-1", snapshot))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SanitizationRun(dataset, "   ", snapshot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SanitizationRun(dataset, "owner-1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void runResultRejectsNegativeCountsAndEmptyColumns() {
        assertThatThrownBy(() -> new RunResult(-1L, 0L, 0L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunResult(0L, -1L, 0L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunResult(0L, 0L, -1L, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunResult(0L, 0L, 0L, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runFailureRejectsBlankAndOverlongFields() {
        assertThatThrownBy(() -> new RunFailure("  ", "WRITE", "Output unavailable."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunFailure("IO_ERROR", null, "Output unavailable."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunFailure("IO_ERROR", "WRITE", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunFailure("C".repeat(65), "WRITE", "Output unavailable."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunFailure("IO_ERROR", "S".repeat(65), "Output unavailable."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunFailure("IO_ERROR", "WRITE", "M".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedResultCountsCannotLeakIntoCompletedRun() {
        SanitizationRun run = newRun();
        run.markRunning();
        run.markCompleted(new RunResult(0L, 0L, 0L, 1));

        assertThat(run.getErrorMessage()).isNull();
        assertThat(run.getCompletedAt()).isNotNull();
    }

    @Test
    void customPlanSnapshotIsFrozenAtConstruction() {
        TransformationPlan plan = TransformationPlan.of(List.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT)));
        Dataset dataset = new Dataset("a.csv", "owner-9");
        SanitizationRun run =
                new SanitizationRun(dataset, "owner-9", PolicySnapshot.fromPlan("custom", "v3", plan));

        assertThat(run.getPolicySnapshot()).contains("\"EMAIL\":\"REDACT\"");
        assertThat(run.getPolicySnapshot()).doesNotContain("PHONE");
    }
}

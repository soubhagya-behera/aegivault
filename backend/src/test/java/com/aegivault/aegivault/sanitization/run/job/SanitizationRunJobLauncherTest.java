package com.aegivault.aegivault.sanitization.run.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Modifier;

import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.dataset.DatasetInputSource;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationService;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.run.PolicySnapshot;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRun;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import com.aegivault.aegivault.sanitization.run.SanitizationRunTarget;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

/**
 * Pure unit tests for {@link SanitizationRunJobLauncher} (no Spring context, no
 * database, no Redis, no thread pool, no sleeping, no network).
 *
 * <p><strong>Determinism comes from the executor, not from timing.</strong> The
 * {@link RecordingTaskExecutor} below captures the submitted task and runs it
 * only when the test asks, so "was this asynchronous?" is answered by observing
 * what the launcher did <em>not</em> do, rather than by racing it with
 * {@code Thread.sleep}. A second executor refuses submissions on demand to
 * stand in for a full bounded pool.
 *
 * <p>They also pin the negative space: the launcher cannot reach CSV parsing,
 * PII detection, transformation, artifact storage, audit hashing, or policy
 * resolution even accidentally, because it holds no reference to any of them.
 */
class SanitizationRunJobLauncherTest {

    /** A valid frozen snapshot, built the one way the domain builds it. */
    private static final String SNAPSHOT =
            PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan()).toJson();

    private SanitizationRunExecutor runs;

    private RecordingTaskExecutor executor;

    private SanitizationRunJobLauncher launcher;

    @BeforeEach
    void setUp() {
        runs = mock(SanitizationRunExecutor.class);
        executor = new RecordingTaskExecutor();
        launcher = new SanitizationRunJobLauncher(runs, executor);
    }

    private static SanitizationRunTarget queued(UUID id, String owner) {
        return new SanitizationRunTarget(
                id, owner, UUID.randomUUID(), RunStatus.QUEUED, "default", "v1", SNAPSHOT);
    }

    private static SanitizationRunTarget withStatus(RunStatus status) {
        return new SanitizationRunTarget(
                UUID.randomUUID(), "actor-1", UUID.randomUUID(), status, "default", "v1", SNAPSHOT);
    }

    @Test
    void aQueuedRunIsSubmittedAndTheLauncherReturnsWithoutExecutingIt() {
        launcher.launch(queued(UUID.randomUUID(), "actor-1"));

        // Submitted, yes. Executed, no: the call returned having done nothing
        // but hand the work over.
        assertThat(executor.submitted()).isEqualTo(1);
        verify(runs, never()).executeQueuedRun(any());
    }

    @Test
    void theSubmittedTaskInvokesTheExistingExecutorExactlyOnce() {
        UUID id = UUID.randomUUID();

        launcher.launch(queued(id, "actor-1"));
        executor.runAll();

        verify(runs, times(1)).executeQueuedRun(id);
    }

    @Test
    void aDuplicateLaunchOfTheSameRunIsRefusedWhileTheFirstIsInFlight() {
        UUID id = UUID.randomUUID();
        launcher.launch(queued(id, "actor-1"));

        // The first task has not run yet, so the run is still claimed and a
        // second worker must not be allowed to execute it as well.
        assertThatThrownBy(() -> launcher.launch(queued(id, "actor-1")))
                .isInstanceOf(SanitizationRunNotLaunchableException.class)
                .hasMessage("Sanitization run is not launchable.");

        assertThat(executor.submitted()).as("no second task may be submitted").isEqualTo(1);
        executor.runAll();
        verify(runs, times(1)).executeQueuedRun(id);
    }

    @Test
    void aRunMayBeLaunchedAgainOnceItsExecutionHasFinished() {
        UUID id = UUID.randomUUID();
        launcher.launch(queued(id, "actor-1"));
        executor.runAll();

        // The hold is released in the worker's finally block, so the guard can
        // never strand a run in this process. The status check is what governs
        // afterwards: a still-QUEUED run may be launched again.
        assertThatCode(() -> launcher.launch(queued(id, "actor-1"))).doesNotThrowAnyException();
        assertThat(executor.submitted()).isEqualTo(2);
    }

    @Test
    void differentRunsAreSubmittedIndependently() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        launcher.launch(queued(first, "actor-1"));
        launcher.launch(queued(second, "actor-2"));

        assertThat(executor.submitted()).isEqualTo(2);
        executor.runAll();
        verify(runs, times(1)).executeQueuedRun(first);
        verify(runs, times(1)).executeQueuedRun(second);
    }

    @Test
    void aRefusedSubmissionIsReportedSafelyAndLeavesTheRunUnstarted() {
        executor.refuse = true;
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> launcher.launch(queued(id, "actor-1")))
                .isInstanceOf(SanitizationRunJobLaunchException.class)
                .hasMessage("Unable to launch sanitization run.");
        // No executor, thread, queue, or run detail in the safe message.
        assertThat(SanitizationRunJobLaunchException.MESSAGE)
                .doesNotContain("Rejected", "pool", "queue", "thread", "actor-1", id.toString());

        // No worker ran, so the run cannot have been marked RUNNING, and the
        // launcher started no work of its own.
        verify(runs, never()).executeQueuedRun(any());
    }

    @Test
    void aRefusedSubmissionReleasesTheHoldSoALaterLaunchCanStillSucceed() {
        executor.refuse = true;
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> launcher.launch(queued(id, "actor-1")))
                .isInstanceOf(SanitizationRunJobLaunchException.class);

        executor.refuse = false;
        launcher.launch(queued(id, "actor-1"));

        assertThat(executor.submitted()).as("only the second attempt is submitted").isEqualTo(1);
    }

    @Test
    void aNonQueuedRunIsNeverLaunched() {
        for (RunStatus status : List.of(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.FAILED)) {
            SanitizationRunJobLauncher fresh = new SanitizationRunJobLauncher(runs, executor);

            assertThatThrownBy(() -> fresh.launch(withStatus(status)))
                    .as("a %s run must not be launched", status)
                    .isInstanceOf(SanitizationRunNotLaunchableException.class);
        }

        assertThat(executor.submitted()).isZero();
        verify(runs, never()).executeQueuedRun(any());
    }

    @Test
    void theRunOwnsItsOwnExecutionAndNoOwnerCanBeSupplied() {
        // The launcher accepts the run and nothing else, so the worker can only
        // ever act with the owner recorded on the run row: there is no owner
        // argument for a caller (or a request) to override it with.
        assertThat(Arrays.stream(SanitizationRunJobLauncher.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("launch"))
                        .allMatch(method -> method.getParameterCount() == 1
                                && method.getParameterTypes()[0].equals(SanitizationRunTarget.class)))
                .as("launch must accept exactly one SanitizationRunTarget and no owner")
                .isTrue();

        // Two runs owned by two different actors are each executed as themselves.
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        launcher.launch(queued(mine, "actor-1"));
        launcher.launch(queued(theirs, "actor-2"));
        executor.runAll();

        // The launcher passes a run id and nothing else, so the owner used is
        // resolved by the executor from that run's own row — never supplied.
        verify(runs, times(1)).executeQueuedRun(mine);
        verify(runs, times(1)).executeQueuedRun(theirs);
    }

    @Test
    void theLauncherGeneratesNoLifecycleEventOfItsOwn() {
        launcher.launch(queued(UUID.randomUUID(), "actor-1"));
        executor.runAll();

        // No audit collaborator exists to append anything with, and no run
        // entity is reachable to mutate: RUN_CREATED / RUN_COMPLETED /
        // RUN_FAILED remain the executor's alone, so a launched run cannot
        // produce a duplicate pair of them.
        assertThat(Arrays.stream(SanitizationRunJobLauncher.class.getDeclaredFields())
                        .map(field -> field.getType().getName())
                        .toList())
                .doesNotContain(SanitizationRun.class.getName(), AuditLedgerService.class.getName());
    }

    @Test
    void theLauncherCannotReachAnySanitizationConcern() {
        // Negative space, pinned by reflection: no CSV engine, no input source,
        // no artifact store, no transformation registry, no PII detection, and
        // no policy resolution, so it cannot duplicate any of them.
        var dependencyTypes = Arrays.stream(SanitizationRunJobLauncher.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .toList();

        assertThat(dependencyTypes)
                .contains(SanitizationRunExecutor.class.getName())
                .doesNotContain(
                        CsvSanitizationService.class.getName(),
                        DatasetInputSource.class.getName(),
                        AuditLedgerService.class.getName(),
                        PiiDetectorRegistry.class.getName(),
                        TransformationPlan.class.getName(),
                        SanitizationArtifactStore.class.getName(),
                        TransformationRegistry.class.getName(),
                        SanitizationRun.class.getName());
    }

    @Test
    void theSafeMessagesNameNoRunOwnerOrInfrastructureDetail() {
        assertThat(SanitizationRunJobLaunchException.MESSAGE)
                .isEqualTo("Unable to launch sanitization run.")
                .doesNotContain("actor", "dataset", "run-", "thread", "pool", "queue", "Rejected");
        assertThat(SanitizationRunNotLaunchableException.MESSAGE)
                .isEqualTo("Sanitization run is not launchable.")
                .doesNotContain("actor", "dataset", "run-", "thread", "pool", "queue", "QUEUED");
    }

    /**
     * A task executor that records submissions and runs them only on demand.
     *
     * <p>This is the deterministic stand-in for the real bounded pool: a test
     * can assert that nothing executed at launch time, then run the captured
     * task itself — with no threads, no latches, and no sleeping.
     */
    private static final class RecordingTaskExecutor implements TaskExecutor {

        private final List<Runnable> tasks = new ArrayList<>();

        private boolean refuse;

        @Override
        public void execute(Runnable task) {
            if (refuse) {
                // What a full bounded pool does, expressed the way Spring does.
                throw new TaskRejectedException("queue is full", new RejectedExecutionException());
            }
            tasks.add(task);
        }

        private int submitted() {
            return tasks.size();
        }

        private void runAll() {
            List.copyOf(tasks).forEach(Runnable::run);
        }
    }
}

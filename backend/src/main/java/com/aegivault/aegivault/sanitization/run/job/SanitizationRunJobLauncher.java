package com.aegivault.aegivault.sanitization.run.job;

import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.run.SanitizationRunTarget;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Hands an already persisted {@code QUEUED} sanitization run to a background
 * worker and returns immediately. That is the whole job.
 *
 * <p><strong>It executes nothing itself.</strong> The launcher has no CSV
 * parser, no PII detector, no transformation registry, no artifact store, no
 * policy resolution, and no audit ledger — it cannot, because it holds no
 * reference to any of them. All execution stays in
 * {@link SanitizationRunExecutor}, which owns the lifecycle transitions and
 * every existing side effect. The launcher only decides <em>whether</em> to
 * submit, and submits.
 *
 * <p><strong>No lifecycle logic is duplicated here.</strong> This class performs
 * no {@code QUEUED -> RUNNING} transition, writes no run field, appends no
 * audit event, and stores no artifact. The persisted {@code SanitizationRun}
 * row is the only execution state, and the executor's {@code startRun} remains
 * the authoritative gate; the status check below is a cheap refusal that avoids
 * spending a submission on work that cannot legally start, and it consumes no
 * transition.
 *
 * <p><strong>Duplicate-launch protection is process-local.</strong> A set of
 * run ids currently in flight makes a second launch of the same run a refusal
 * rather than a second concurrent execution. This guard is deliberately
 * in-memory and per-JVM: it needs no Redis, no database lock, and no
 * distributed lock, and is therefore correct only for a single application
 * instance. A multi-instance deployment would need a shared guard, which is a
 * deliberate later decision — this milestone documents the limitation rather
 * than pretending it away.
 *
 * <p><strong>Submission failure leaves the run alone.</strong> The hold is
 * taken, the task is submitted, and if the bounded pool refuses the work the
 * hold is released and {@link SanitizationRunJobLaunchException} is raised. No
 * worker ran, so the run is not {@code RUNNING} and keeps its {@code QUEUED}
 * state for a later launch.
 *
 * <p><strong>Nothing is retried and nothing is recovered.</strong> A refused or
 * failed launch is reported to the caller and the run stays queued. There is no
 * retry queue, no backoff, no dead-letter path, and no scheduled reconciliation
 * of runs left {@code RUNNING} by a process crash.
 *
 * <p><strong>Ownership is not a parameter.</strong> {@link #launch} takes only
 * the run itself, so a job can never be pointed at another actor's data by
 * passing a different owner: the worker uses the owner recorded on the run row.
 */
@Service
public class SanitizationRunJobLauncher {

    private final SanitizationRunExecutor runs;

    private final TaskExecutor executor;

    /**
     * Run ids currently executing in this instance. Backed by a concurrent set
     * so the claim is atomic: two threads racing on the same run cannot both
     * win the add, which is the whole point of the guard.
     */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param runs the existing synchronous executor; the only thing that
     *        actually performs a run, never null
     * @param executor the bounded background pool from
     *        {@link SanitizationRunJobConfiguration}, injected by name so
     *        background work can never land on some other executor
     */
    public SanitizationRunJobLauncher(
            SanitizationRunExecutor runs,
            @Qualifier("sanitizationRunJobExecutor") TaskExecutor executor) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * Submits one persisted run for background execution and returns without
     * waiting for it.
     *
     * @param run the persisted run to execute, never null; the only identity
     *        accepted, so its recorded owner is the only owner used
     * @throws SanitizationRunNotLaunchableException when the run is not
     *         {@code QUEUED}, or is already in flight in this instance
     * @throws SanitizationRunJobLaunchException when the bounded pool refuses
     *         the submission, in which case no worker ran and the run is
     *         unchanged
     */
    public void launch(SanitizationRunTarget run) {
        Objects.requireNonNull(run, "run must not be null");
        if (run.status() != RunStatus.QUEUED) {
            // Nothing to start: a running or terminal run cannot be started
            // again, and the persisted state machine would refuse it anyway.
            throw new SanitizationRunNotLaunchableException();
        }
        if (!inFlight.add(run.id())) {
            // Already claimed in this instance, so a second claim is refused
            // rather than allowed to execute the same run twice.
            throw new SanitizationRunNotLaunchableException();
        }
        try {
            executor.execute(() -> execute(run.id()));
        } catch (RejectedExecutionException ex) {
            // The pool and its queue are both full. Nothing was executed, so
            // the run is still QUEUED and still launchable; give the hold back
            // and report the refusal without any executor or thread detail.
            inFlight.remove(run.id());
            throw new SanitizationRunJobLaunchException(ex);
        }
    }

    /**
     * The worker body: exactly one existing executor call, and nothing else.
     *
     * <p>A documented engine failure never arrives here — the executor maps it
     * to a {@code FAILED} run and returns. An unexpected failure propagates to
     * the pool, which contains it, and leaves the run {@code RUNNING} exactly
     * as it would have synchronously. Either way the in-flight hold is
     * released, so a run that failed unexpectedly does not stay permanently
     * un-launchable in this process.
     */
    private void execute(UUID runId) {
        try {
            runs.executeQueuedRun(runId);
        } finally {
            inFlight.remove(runId);
        }
    }
}

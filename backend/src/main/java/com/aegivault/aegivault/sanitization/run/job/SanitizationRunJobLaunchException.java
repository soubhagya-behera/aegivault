package com.aegivault.aegivault.sanitization.run.job;

/**
 * Signals that a sanitization run could not be handed to the background
 * worker pool, so no execution was started and the run is still exactly as it
 * was.
 *
 * <p><strong>Nothing ran, and the run keeps its state.</strong> The failure is
 * raised during submission, before any worker thread has touched the run, so
 * the run is not marked {@code RUNNING} and no lifecycle transition or audit
 * event occurred. In practice this is back-pressure: the bounded pool and its
 * finite queue were both full.
 *
 * <p><strong>The message is the whole safe body.</strong> It names no thread
 * name, pool size, queue capacity, executor class, Redis detail, dataset, run
 * id, actor, policy, CSV content, PII, or underlying exception text. The cause
 * is retained for server logs only. No HTTP status is chosen here: nothing
 * exposes this exception yet, because {@code POST /api/runs} still executes
 * synchronously and is unchanged.
 */
public class SanitizationRunJobLaunchException extends RuntimeException {

    /** The only safe launch-failure message, shared by every throw site. */
    public static final String MESSAGE = "Unable to launch sanitization run.";

    public SanitizationRunJobLaunchException(Throwable cause) {
        super(MESSAGE, cause);
    }
}

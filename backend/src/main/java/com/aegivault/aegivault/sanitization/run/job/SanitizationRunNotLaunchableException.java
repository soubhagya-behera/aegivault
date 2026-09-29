package com.aegivault.aegivault.sanitization.run.job;

/**
 * Signals that a run was not eligible to be launched for background execution,
 * so nothing was submitted and the run is unchanged.
 *
 * <p>There are exactly two reasons, and they are about <em>this</em>:
 *
 * <ul>
 *   <li>the run is not {@code QUEUED} — it is already {@code RUNNING} or has
 *       already reached {@code COMPLETED}/{@code FAILED}, so there is nothing
 *       left to start;</li>
 *   <li>the run is already in flight in this application instance, i.e. a
 *       previous launch has not finished yet, so a second worker could execute
 *       the same run twice.</li>
 * </ul>
 *
 * <p>Both are refusals rather than failures: no worker was asked to do anything
 * and no run state changed. The persisted state machine in
 * {@code SanitizationRun} remains the real authority — this check only avoids
 * wasting a submission on work that provably cannot start.
 *
 * <p>The message is fixed and safe: no run id, actor, dataset, status detail,
 * pool detail, or underlying text.
 */
public class SanitizationRunNotLaunchableException extends RuntimeException {

    /** The only safe refusal message, shared by every throw site. */
    public static final String MESSAGE = "Sanitization run is not launchable.";

    public SanitizationRunNotLaunchableException() {
        super(MESSAGE);
    }
}

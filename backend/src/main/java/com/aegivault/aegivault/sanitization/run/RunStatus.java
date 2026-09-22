package com.aegivault.aegivault.sanitization.run;

/**
 * Lifecycle states of one {@link SanitizationRun}.
 *
 * <p>The allowed flow is {@code QUEUED -> RUNNING -> COMPLETED} or
 * {@code QUEUED -> RUNNING -> FAILED}. {@code COMPLETED} and
 * {@code FAILED} are terminal. No other states exist: there is no
 * cancellation, pause, or retry in this milestone because no background
 * worker exists yet that could act on them.
 *
 * <p>The state machine lives here in the domain, not in a controller or
 * service: {@link #canTransitionTo(RunStatus)} is the single definition of
 * a legal transition, and the entity enforces it on every state change.
 */
public enum RunStatus {

    QUEUED,

    RUNNING,

    COMPLETED,

    FAILED;

    /**
     * @param target desired next state, may be null
     * @return true only for {@code QUEUED -> RUNNING},
     *         {@code RUNNING -> COMPLETED}, and {@code RUNNING -> FAILED}
     */
    public boolean canTransitionTo(RunStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case QUEUED -> target == RUNNING;
            case RUNNING -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }

    /**
     * @return true for {@code COMPLETED} and {@code FAILED}, which accept no
     *         further transitions
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}

package com.aegivault.aegivault.sanitization.run;

/**
 * Thrown when a state change violates the {@link RunStatus} lifecycle, for
 * example {@code QUEUED -> COMPLETED} or any transition out of a terminal
 * state. The message names statuses only, never data.
 */
public class InvalidRunTransitionException extends RuntimeException {

    public InvalidRunTransitionException(RunStatus from, RunStatus to) {
        super("Invalid run transition from " + from + " to " + to + ".");
    }
}

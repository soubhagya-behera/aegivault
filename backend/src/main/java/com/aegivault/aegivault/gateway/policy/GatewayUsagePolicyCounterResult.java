package com.aegivault.aegivault.gateway.policy;

import java.util.Objects;

/**
 * The outcome of one atomic multi-window request-admission attempt: either
 * every requested window had room and all of them were incremented, or at
 * least one was already spent and none was touched.
 *
 * <p>The two states are exhaustive, and there is no third "unknown" state
 * here on purpose. When the underlying store could not answer, the attempt
 * fails with {@link GatewayUsagePolicyCounterUnavailableException} instead of
 * producing one of these values, so a caller can never read an infrastructure
 * failure as a rejection or as an admission.
 *
 * <p>Unlike the single-window boolean, a rejection names <em>which</em>
 * window was exhausted. The named window is the first exhausted one in the
 * fixed evaluation order (day, then minute), so the same configuration always
 * reports the same window regardless of map ordering or arrival timing.
 *
 * <p>It carries no actor subject, counter value, Redis key, limit number, or
 * policy detail — only the decision and, when rejected, the window responsible.
 *
 * @param state the outcome, never null
 * @param rejectedWindow the exhausted window; non-null if and only if
 *        {@code state} is {@link State#REJECTED}
 * @throws IllegalArgumentException when {@code rejectedWindow} does not match
 *         {@code state}
 */
public record GatewayUsagePolicyCounterResult(
        State state, GatewayUsagePolicyCounterWindow rejectedWindow) {

    /** The only two outcomes an admission attempt can have. */
    public enum State {
        /** Every requested window had room and all were incremented. */
        ALLOWED,
        /** At least one requested window was already spent; none was touched. */
        REJECTED
    }

    public GatewayUsagePolicyCounterResult {
        Objects.requireNonNull(state, "state must not be null");
        if ((state == State.REJECTED) != (rejectedWindow != null)) {
            // A rejection without a window cannot be attributed, and a window
            // on an admission would imply a limit was exhausted when none was.
            throw new IllegalArgumentException(
                    "rejectedWindow must be present exactly when the state is REJECTED");
        }
    }

    /** Every requested window had room; all were incremented. */
    public static GatewayUsagePolicyCounterResult allowed() {
        return new GatewayUsagePolicyCounterResult(State.ALLOWED, null);
    }

    /**
     * At least one window was already spent; nothing was incremented.
     *
     * @param window the exhausted window, never null
     */
    public static GatewayUsagePolicyCounterResult rejected(GatewayUsagePolicyCounterWindow window) {
        return new GatewayUsagePolicyCounterResult(
                State.REJECTED, Objects.requireNonNull(window, "window must not be null"));
    }

    /** Whether the request may proceed. */
    public boolean isAllowed() {
        return state == State.ALLOWED;
    }
}

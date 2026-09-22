package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the run lifecycle state machine: the only legal moves are
 * {@code QUEUED -> RUNNING}, {@code RUNNING -> COMPLETED}, and
 * {@code RUNNING -> FAILED}.
 */
class RunStatusTest {

    @Test
    void queuedTransitionsOnlyToRunning() {
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.QUEUED)).isFalse();
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.COMPLETED)).isFalse();
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.FAILED)).isFalse();
        assertThat(RunStatus.QUEUED.canTransitionTo(null)).isFalse();
        assertThat(RunStatus.QUEUED.isTerminal()).isFalse();
    }

    @Test
    void runningTransitionsOnlyToTerminalStates() {
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.COMPLETED)).isTrue();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.QUEUED)).isFalse();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.RUNNING)).isFalse();
        assertThat(RunStatus.RUNNING.canTransitionTo(null)).isFalse();
        assertThat(RunStatus.RUNNING.isTerminal()).isFalse();
    }

    @Test
    void completedAcceptsNoTransitions() {
        assertThat(RunStatus.COMPLETED.isTerminal()).isTrue();
        for (RunStatus target : RunStatus.values()) {
            assertThat(RunStatus.COMPLETED.canTransitionTo(target))
                    .as("COMPLETED -> %s", target)
                    .isFalse();
        }
        assertThat(RunStatus.COMPLETED.canTransitionTo(null)).isFalse();
    }

    @Test
    void failedAcceptsNoTransitions() {
        assertThat(RunStatus.FAILED.isTerminal()).isTrue();
        for (RunStatus target : RunStatus.values()) {
            assertThat(RunStatus.FAILED.canTransitionTo(target))
                    .as("FAILED -> %s", target)
                    .isFalse();
        }
        assertThat(RunStatus.FAILED.canTransitionTo(null)).isFalse();
    }
}

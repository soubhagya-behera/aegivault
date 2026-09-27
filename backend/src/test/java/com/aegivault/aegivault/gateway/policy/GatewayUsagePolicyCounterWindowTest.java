package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyCounterWindow} (no Spring
 * context, no clock, no storage). They pin the UTC window boundaries, the
 * key format, and the TTL, and they deliberately run under a non-UTC default
 * time zone: if these rules ever leaked a JVM-default-zone dependency, the
 * boundaries would shift and the tests would fail.
 */
class GatewayUsagePolicyCounterWindowTest {

    private static final Instant NOON = Instant.parse("2026-03-15T12:30:45.123Z");

    @Test
    void minuteWindowsAreAlignedToTheUtcMinute() {
        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.windowStart(NOON))
                .isEqualTo(Instant.parse("2026-03-15T12:30:00Z"));
        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.windowEnd(Instant.parse("2026-03-15T12:30:00Z")))
                .isEqualTo(Instant.parse("2026-03-15T12:31:00Z"));
    }

    @Test
    void dayWindowsAreAlignedToTheUtcCalendarDay() {
        assertThat(GatewayUsagePolicyCounterWindow.DAY.windowStart(NOON))
                .isEqualTo(Instant.parse("2026-03-15T00:00:00Z"));
        assertThat(GatewayUsagePolicyCounterWindow.DAY.windowEnd(Instant.parse("2026-03-15T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-16T00:00:00Z"));
    }

    @Test
    void aMidnightInstantStartsTheNewUtcDay() {
        assertThat(GatewayUsagePolicyCounterWindow.DAY.windowStart(Instant.parse("2026-03-16T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-16T00:00:00Z"));
    }

    @Test
    void windowsAreUnaffectedByTheJvmDefaultTimeZone() {
        // Same instant, wildly different default zone: both windows must land
        // on the same UTC boundaries.
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertThat(GatewayUsagePolicyCounterWindow.MINUTE.windowStart(NOON))
                    .isEqualTo(Instant.parse("2026-03-15T12:30:00Z"));
            assertThat(GatewayUsagePolicyCounterWindow.DAY.windowStart(NOON))
                    .isEqualTo(Instant.parse("2026-03-15T00:00:00Z"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void theKeyFormatIsDocumentedAndDeterministic() {
        Instant windowStart = GatewayUsagePolicyCounterWindow.MINUTE.windowStart(NOON);

        String key = GatewayUsagePolicyCounterWindow.MINUTE.keyFor(windowStart, "actor-1");

        // aegivault:gateway:policy-counter:<window>:<windowStart>:<actorSubject>
        assertThat(key).isEqualTo("aegivault:gateway:policy-counter:minute:1773577800:actor-1");
        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.keyFor(windowStart, "actor-1")).isEqualTo(key);
    }

    @Test
    void windowLengthsAreOneMinuteAndOneDay() {
        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.length()).isEqualTo(Duration.ofMinutes(1));
        assertThat(GatewayUsagePolicyCounterWindow.DAY.length()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void ttlCoversTheRemainderOfTheWindowPlusASmallGrace() {
        // 12:30:45.123 into a minute that ends at 12:31:00 leaves 14.877s.
        long expected = 15_000L - 123L + GatewayUsagePolicyCounterWindow.EXPIRY_GRACE.toMillis();
        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.ttlMillis(NOON)).isEqualTo(expected);
    }

    @Test
    void ttlIsNeverZeroOrNegativeEvenAtAWindowBoundary() {
        // Exactly on the boundary the remaining time is a whole window, but
        // the guard matters for any instant that rounds to the end.
        Instant boundary = Instant.parse("2026-03-15T12:30:00Z");

        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.ttlMillis(boundary)).isPositive();
    }

    @Test
    void thereIsNoTokenWindow() {
        // tokensPerDay is intentionally unenforced: token usage is only known
        // after a provider response, while admission happens before provider
        // invocation. No estimated or converted token window may creep in.
        assertThat(GatewayUsagePolicyCounterWindow.values())
                .containsExactly(GatewayUsagePolicyCounterWindow.MINUTE, GatewayUsagePolicyCounterWindow.DAY)
                .noneMatch(window -> window.name().toLowerCase().contains("token"));
    }

    @Test
    void keyForIsAPureFormattingStepOverAnAlreadyValidatedActor() {
        // Key building formats; it does not validate. The counter rejects a
        // blank actor before it is ever used, so a blank subject can never
        // reach a key through the counter's own entry point.
        assertThat(GatewayUsagePolicyCounterWindow.MINUTE.keyFor(NOON, "actor-1"))
                .endsWith(":actor-1");
    }
}

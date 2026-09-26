package com.aegivault.aegivault.gateway.policy;

import com.aegivault.aegivault.gateway.usage.GatewayUsageDayWindow;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a {@link GatewayUsagePolicyUsageSnapshot} for one actor from
 * already-persisted gateway usage records, for a caller-supplied instant.
 *
 * <p>Read-only and side-effect free: it issues two windowed aggregate reads
 * and nothing else — no writes, no counters, no reservation, no check-and-
 * consume, and no decision of its own. It is the missing data source the
 * pure {@link GatewayUsagePolicyEvaluator} needs, and nothing else.
 *
 * <p><strong>What the request counts mean.</strong> They count persisted
 * {@code gateway_usage_records} rows — that is, provider invocations that
 * returned a response and were recorded. They are <em>not</em> a count of
 * every HTTP request the gateway ever received, and the difference is real:
 * a request blocked by request-side inspection writes no usage row, a
 * rate-limit rejection writes no usage row, and a provider or selector
 * failure writes no usage row. A limit expressed against these counts
 * therefore limits recorded provider usage, not inbound traffic.
 *
 * <p><strong>Token semantics.</strong> A daily token total is only reported
 * when <em>every</em> matching row for the day actually supplied one. If any
 * matching row has a null {@code totalTokens}, the partial sum is discarded
 * and the total is reported as unknown — an incomplete total is never passed
 * off as a complete one, because a policy that compared against a partial sum
 * could allow usage it should have blocked. A day with no records at all is a
 * different case: nothing was used, so the total is a known zero.
 *
 * <p>Nothing calls this provider yet. Gateway completion, the rate limiter,
 * and the usage write path are untouched, so no policy is enforced.
 */
@Service
@RequiredArgsConstructor
public class GatewayUsagePolicyUsageSnapshotProvider {

    private final GatewayUsageRepository usage;

    /**
     * Reads one actor's current-minute and current-day usage.
     *
     * <p>Windows are UTC and half-open: the minute is
     * {@code [minuteStart, minuteStart + 1 minute)} and the day is
     * {@code [dayStart, dayStart + 1 day)}, so a record exactly at a start
     * bound is included and one exactly at the end bound is excluded. Both
     * are derived from {@code now} in UTC, never from the JVM default zone.
     *
     * @param actorSubject authenticated actor, never blank; trimmed exactly
     *        like the existing gateway usage query and policy resolver do
     * @param now the instant to report from, never null
     * @return the snapshot, never null
     * @throws IllegalArgumentException when the actor is blank or either
     *         argument is null
     */
    @Transactional(readOnly = true)
    public GatewayUsagePolicyUsageSnapshot snapshotFor(String actorSubject, Instant now) {
        String actor = requireActor(actorSubject);
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }

        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant minuteEnd = minuteStart.plus(1L, ChronoUnit.MINUTES);
        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = dayStart.plus(1L, ChronoUnit.DAYS);

        long requestsInCurrentMinute = usage
                .countByActorSubjectAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        actor, minuteStart, minuteEnd);
        GatewayUsageDayWindow day = usage.summarizeDayWindow(actor, dayStart, dayEnd);
        long dayCount = zeroIfNull(day.getRecordCount());
        long knownTokenCount = zeroIfNull(day.getKnownTokenCount());

        if (dayCount == 0L) {
            // No usage recorded today, so the day's token total is a known
            // zero rather than an unknown.
            return GatewayUsagePolicyUsageSnapshot.withTokenUsage(requestsInCurrentMinute, 0L, 0L);
        }
        if (knownTokenCount < dayCount) {
            // At least one row reported no total: the partial sum is
            // discarded and the day is reported as unknown.
            return GatewayUsagePolicyUsageSnapshot.withoutTokenUsage(requestsInCurrentMinute, dayCount);
        }
        return GatewayUsagePolicyUsageSnapshot.withTokenUsage(
                requestsInCurrentMinute, dayCount, zeroIfNull(day.getTotalTokens()));
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}
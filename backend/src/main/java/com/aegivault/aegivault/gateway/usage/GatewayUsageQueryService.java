package com.aegivault.aegivault.gateway.usage;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Internal read-only query layer over persisted gateway usage: one
 * actor's history and one actor's aggregate. Strictly side-effect free —
 * no updates, deletes, backfills, or recalculation; the only dependency
 * is {@link GatewayUsageRepository}, never controllers, Redis, the rate
 * limiter, providers, PII detectors, or the audit ledger.
 *
 * <p>Every operation requires an {@code actorSubject} and reads only that
 * actor's rows: there is no global read path, no ADMIN bypass, and no way
 * for one actor's query to return another actor's records. History comes
 * back newest-first in a deterministic order ({@code createdAt}
 * descending, then {@code id} descending). Aggregates carry the exact
 * row count plus database-side token totals where null means unknown —
 * never zero, never money, cost, budgets, or quotas.
 *
 * <p>The self-service usage API reads through this service: bounded
 * newest-first history plus the unchanged database-side aggregate.
 */
@Service
@RequiredArgsConstructor
public class GatewayUsageQueryService {

    /**
     * Maximum history rows returned by {@link #recentHistoryFor(String)}:
     * at most 100 newest records, enforced in the repository/database
     * query — never by loading the full history and trimming in Java.
     * No pagination exists yet.
     */
    public static final int MAX_HISTORY = 100;

    private final GatewayUsageRepository repository;

    /**
     * Returns one actor's usage records, newest first
     * ({@code createdAt} descending, then {@code id} descending, so the
     * order is deterministic even when rows share a timestamp). Records
     * carry metadata only — never prompt or response content.
     *
     * @param actorSubject actor whose history is read, never blank
     * @return that actor's records in deterministic newest-first order,
     *         possibly empty, never null
     */
    public List<GatewayUsageRecord> historyFor(String actorSubject) {
        return repository.findByActorSubjectOrderByCreatedAtDescIdDesc(requireActor(actorSubject));
    }

    /**
     * Returns at most the 100 newest usage records for one actor,
     * newest first ({@code createdAt} descending, then {@code id}
     * descending). The bound is applied in the repository/database
     * query. Records carry metadata only — never prompt or response
     * content.
     *
     * @param actorSubject actor whose history is read, never blank
     * @return at most 100 of that actor's newest records, possibly
     *         empty, never null
     */
    public List<GatewayUsageRecord> recentHistoryFor(String actorSubject) {
        return repository.findTop100ByActorSubjectOrderByCreatedAtDescIdDesc(requireActor(actorSubject));
    }

    /**
     * Returns one actor's usage aggregate, computed database-side: the
     * exact matching-row count plus exact totals of known token values.
     * Each token total stays null when the actor has no known value for
     * that column — unknown is preserved, never reported as zero.
     *
     * @param actorSubject actor whose usage is aggregated, never blank
     * @return the actor's aggregate, never null
     */
    public GatewayUsageAggregate aggregateFor(String actorSubject) {
        return repository.aggregateByActorSubject(requireActor(actorSubject));
    }

    /**
     * Returns one actor's usage aggregate over an explicit UTC time window
     * [{@code from}, {@code to}), computed database-side: {@code from} is inclusive,
     * {@code to} is exclusive. Semantics match {@link #aggregateFor(String)},
     * preserving null for token fields where no known values exist and
     * reporting the exact matching record count.
     *
     * @param actorSubject actor whose usage is aggregated, never blank
     * @param from start of the window (inclusive, UTC), never null
     * @param to end of the window (exclusive, UTC), never null, strictly after {@code from}
     * @return the actor's aggregate for the window, never null
     * @throws IllegalArgumentException when {@code actorSubject} is blank,
     *         either bound is null, or {@code from} is not strictly before {@code to}
     */
    public GatewayUsageAggregate aggregateFor(String actorSubject, Instant from, Instant to) {
        String actor = requireActor(actorSubject);
        if (from == null) {
            throw new IllegalArgumentException("from must not be null");
        }
        if (to == null) {
            throw new IllegalArgumentException("to must not be null");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be strictly before to");
        }
        return repository.aggregateByActorSubjectAndCreatedAtBetween(actor, from, to);
    }

    private static String requireActor(String actorSubject) {
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new IllegalArgumentException("actorSubject must not be blank");
        }
        return actorSubject.trim();
    }
}

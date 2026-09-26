package com.aegivault.aegivault.gateway.usage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence access for {@link GatewayUsageRecord}. Standard CRUD comes
 * from {@link JpaRepository}; usage adds exactly four access paths
 * beyond the primary key: lookup by the server-generated gateway request
 * id (UNIQUE, so at most one row), actor-scoped history newest-first,
 * one database-side aggregate row per actor, and one database-side aggregate
 * row per actor over an explicit UTC time window [{@code from}, {@code to}).
 * The history ordering ({@code created_at} descending, {@code id} descending)
 * is deterministic even when rows share a timestamp, and the actor filter rides the
 * dedicated V9 {@code (actor_subject, created_at)} index. There is no
 * global (cross-actor) read path by design.
 */
public interface GatewayUsageRepository extends JpaRepository<GatewayUsageRecord, UUID> {

    Optional<GatewayUsageRecord> findByRequestId(UUID requestId);

    List<GatewayUsageRecord> findByActorSubjectOrderByCreatedAtDescIdDesc(String actorSubject);

    /**
     * Bounded actor-scoped history for the self-service usage API:
     * at most 100 newest rows for one actor in the same deterministic
     * order ({@code created_at} descending, {@code id} descending). The
     * bound is applied in the database query — the full actor history
     * is never loaded and trimmed in Java.
     */
    List<GatewayUsageRecord> findTop100ByActorSubjectOrderByCreatedAtDescIdDesc(String actorSubject);

    /**
     * One aggregate row for one actor, computed database-side: the exact
     * matching-row count plus the exact totals of known token values.
     * Each sum stays null when the actor has no known value for that
     * column — null means unknown, never zero — while the count still
     * reports the matching rows.
     */
    @Query(
            "SELECT NEW com.aegivault.aegivault.gateway.usage.GatewayUsageAggregate("
                    + "COUNT(r), SUM(r.promptTokens), SUM(r.completionTokens), SUM(r.totalTokens)) "
                    + "FROM GatewayUsageRecord r WHERE r.actorSubject = :actorSubject")
    GatewayUsageAggregate aggregateByActorSubject(@Param("actorSubject") String actorSubject);

    /**
     * One aggregate row for one actor over an explicit UTC time window
     * [{@code from}, {@code to}), computed database-side: {@code from} is inclusive,
     * {@code to} is exclusive. The matching-row count and token sums follow the
     * same null-means-unknown semantics as {@link #aggregateByActorSubject(String)}.
     */
    @Query(
            "SELECT NEW com.aegivault.aegivault.gateway.usage.GatewayUsageAggregate("
                    + "COUNT(r), SUM(r.promptTokens), SUM(r.completionTokens), SUM(r.totalTokens)) "
                    + "FROM GatewayUsageRecord r "
                    + "WHERE r.actorSubject = :actorSubject "
                    + "AND r.createdAt >= :from "
                    + "AND r.createdAt < :to")
    GatewayUsageAggregate aggregateByActorSubjectAndCreatedAtBetween(
            @Param("actorSubject") String actorSubject,
            @Param("from") Instant from,
            @Param("to") Instant to);
}

package com.aegivault.aegivault.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Proves the real persistence path for gateway usage: the Flyway V9
 * migration applies against PostgreSQL, the {@link GatewayUsageRecord}
 * mapping validates, exact provider-reported counts round-trip (null
 * stays null), request ids are unique, and the schema CHECKs hold. No
 * embedded database is used.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class GatewayUsageRepositoryTest {

    @Autowired
    private GatewayUsageRepository records;

    @PersistenceContext
    private EntityManager entities;

    private static GatewayUsageRecord record(UUID requestId, GatewayUsageOutcome outcome) {
        return new GatewayUsageRecord(
                requestId, "analyst", "test-model", 12L, 34L, 46L, outcome);
    }

    @Test
    void migratesMapsAndRoundTripsADeliveredRecord() {
        UUID requestId = UUID.randomUUID();
        UUID id = records.saveAndFlush(record(requestId, GatewayUsageOutcome.DELIVERED)).getId();
        entities.clear();

        GatewayUsageRecord found = records.findById(id).orElseThrow();

        assertThat(found.getRequestId()).isEqualTo(requestId);
        assertThat(found.getActorSubject()).isEqualTo("analyst");
        assertThat(found.getModel()).isEqualTo("test-model");
        assertThat(found.getPromptTokens()).isEqualTo(12L);
        assertThat(found.getCompletionTokens()).isEqualTo(34L);
        assertThat(found.getTotalTokens()).isEqualTo(46L);
        assertThat(found.getOutcome()).isEqualTo(GatewayUsageOutcome.DELIVERED);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void unknownUsageRemainsNull() {
        UUID id = records
                .saveAndFlush(new GatewayUsageRecord(
                        UUID.randomUUID(),
                        "analyst",
                        "test-model",
                        null,
                        null,
                        null,
                        GatewayUsageOutcome.DELIVERED))
                .getId();
        entities.clear();

        GatewayUsageRecord found = records.findById(id).orElseThrow();

        assertThat(found.getPromptTokens()).isNull();
        assertThat(found.getCompletionTokens()).isNull();
        assertThat(found.getTotalTokens()).isNull();
    }

    @Test
    void blockedOutcomeRoundTrips() {
        UUID requestId = UUID.randomUUID();
        records.saveAndFlush(record(requestId, GatewayUsageOutcome.SECURITY_BLOCKED));
        entities.clear();

        GatewayUsageRecord found = records.findByRequestId(requestId).orElseThrow();

        assertThat(found.getOutcome()).isEqualTo(GatewayUsageOutcome.SECURITY_BLOCKED);
        assertThat(found.getPromptTokens()).isEqualTo(12L);
    }

    @Test
    void missingRequestIdFindsNothing() {
        assertThat(records.findByRequestId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void repeatedRequestIdIsRejected() {
        UUID requestId = UUID.randomUUID();
        records.saveAndFlush(record(requestId, GatewayUsageOutcome.DELIVERED));

        assertThatThrownBy(() -> records.saveAndFlush(record(requestId, GatewayUsageOutcome.DELIVERED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negativeTokenCountsAreRejected() {
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "analyst", "test-model", -1L, null, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "analyst", "test-model", null, -1L, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "analyst", "test-model", null, null, -1L, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankActorModelAndNullIdsAreRejected() {
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> new GatewayUsageRecord(
                        null, "analyst", "test-model", null, null, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, null, "test-model", null, null, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "   ", "test-model", null, null, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "analyst", null, null, null, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "analyst", "   ", null, null, null, GatewayUsageOutcome.DELIVERED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsageRecord(
                        requestId, "analyst", "test-model", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void databaseRejectsUnknownOutcomeValues() {
        assertThatThrownBy(
                        () ->
                                entities
                                        .createNativeQuery(
                                                "INSERT INTO gateway_usage_records"
                                                        + " (request_id, actor_subject, model, outcome)"
                                                        + " VALUES (:requestId, 'analyst', 'test-model', 'QUOTA_EXCEEDED')")
                                        .setParameter("requestId", UUID.randomUUID())
                                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsNegativeTokenCounts() {
        assertThatThrownBy(
                        () ->
                                entities
                                        .createNativeQuery(
                                                "INSERT INTO gateway_usage_records"
                                                        + " (request_id, actor_subject, model, prompt_tokens, outcome)"
                                                        + " VALUES (:requestId, 'analyst', 'test-model', -5, 'DELIVERED')")
                                        .setParameter("requestId", UUID.randomUUID())
                                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsNullActorSubject() {
        assertThatThrownBy(
                        () ->
                                entities
                                        .createNativeQuery(
                                                "INSERT INTO gateway_usage_records"
                                                        + " (request_id, actor_subject, model, outcome)"
                                                        + " VALUES (:requestId, NULL, 'test-model', 'DELIVERED')")
                                        .setParameter("requestId", UUID.randomUUID())
                                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsNullModel() {
        assertThatThrownBy(
                        () ->
                                entities
                                        .createNativeQuery(
                                                "INSERT INTO gateway_usage_records"
                                                        + " (request_id, actor_subject, model, outcome)"
                                                        + " VALUES (:requestId, 'analyst', NULL, 'DELIVERED')")
                                        .setParameter("requestId", UUID.randomUUID())
                                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    private GatewayUsageRecord stored(
            String actor, Long prompt, Long completion, Long total, GatewayUsageOutcome outcome) {
        GatewayUsageRecord saved = records.saveAndFlush(
                new GatewayUsageRecord(UUID.randomUUID(), actor, "test-model", prompt, completion, total, outcome));
        pause();
        return saved;
    }

    private static void pause() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private GatewayUsageRecord storedAt(
            String actor,
            Long prompt,
            Long completion,
            Long total,
            GatewayUsageOutcome outcome,
            Instant createdAt) {
        GatewayUsageRecord saved = stored(actor, prompt, completion, total, outcome);
        entities.createNativeQuery(
                        "UPDATE gateway_usage_records SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        entities.flush();
        entities.clear();
        return saved;
    }

    private static Comparator<GatewayUsageRecord> newestFirst() {
        return Comparator.comparing(GatewayUsageRecord::getCreatedAt)
                .reversed()
                .thenComparing(Comparator.comparing(GatewayUsageRecord::getId).reversed());
    }

    @Test
    void emptyActorHasNoHistoryAndUnknownAggregate() {
        assertThat(records.findByActorSubjectOrderByCreatedAtDescIdDesc("nobody")).isEmpty();
        assertThat(records.aggregateByActorSubject("nobody"))
                .isEqualTo(new GatewayUsageAggregate(0L, null, null, null));
    }

    @Test
    void singleDeliveredRecordHistoryAndAggregate() {
        GatewayUsageRecord saved =
                stored("analyst", 12L, 34L, 46L, GatewayUsageOutcome.DELIVERED);
        entities.clear();

        assertThat(records.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst"))
                .extracting(GatewayUsageRecord::getId)
                .containsExactly(saved.getId());
        assertThat(records.aggregateByActorSubject("analyst"))
                .isEqualTo(new GatewayUsageAggregate(1L, 12L, 34L, 46L));
    }

    @Test
    void multipleRecordsAreOrderedNewestFirst() {
        GatewayUsageRecord first =
                stored("analyst", 1L, 1L, 2L, GatewayUsageOutcome.DELIVERED);
        GatewayUsageRecord second =
                stored("analyst", 3L, 4L, 7L, GatewayUsageOutcome.DELIVERED);
        GatewayUsageRecord third =
                stored("analyst", 5L, 6L, 11L, GatewayUsageOutcome.SECURITY_BLOCKED);
        entities.clear();

        List<GatewayUsageRecord> history =
                records.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");

        assertThat(history)
                .extracting(GatewayUsageRecord::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    void multipleActorsRemainIsolated() {
        stored("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED);
        stored("someone-else", 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED);
        stored("analyst", 5L, 5L, 10L, GatewayUsageOutcome.SECURITY_BLOCKED);
        entities.clear();

        List<GatewayUsageRecord> history =
                records.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");

        assertThat(history).hasSize(2);
        assertThat(history).allMatch(record -> record.getActorSubject().equals("analyst"));
        assertThat(records.aggregateByActorSubject("analyst"))
                .isEqualTo(new GatewayUsageAggregate(2L, 15L, 25L, 40L));
        assertThat(records.aggregateByActorSubject("someone-else"))
                .isEqualTo(new GatewayUsageAggregate(1L, 100L, 200L, 300L));
    }

    @Test
    void blockedRecordsAreIncludedInHistoryAndAggregate() {
        stored("analyst", 10L, 20L, 30L, GatewayUsageOutcome.SECURITY_BLOCKED);
        entities.clear();

        List<GatewayUsageRecord> history =
                records.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");

        assertThat(history)
                .extracting(GatewayUsageRecord::getOutcome)
                .containsExactly(GatewayUsageOutcome.SECURITY_BLOCKED);
        assertThat(records.aggregateByActorSubject("analyst"))
                .isEqualTo(new GatewayUsageAggregate(1L, 10L, 20L, 30L));
    }

    @Test
    void nullTokenValuesRemainUnknownInAggregates() {
        stored("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED);
        stored("analyst", null, null, null, GatewayUsageOutcome.DELIVERED);
        stored("analyst", 5L, null, null, GatewayUsageOutcome.SECURITY_BLOCKED);
        entities.clear();

        assertThat(records.aggregateByActorSubject("analyst"))
                .isEqualTo(new GatewayUsageAggregate(3L, 15L, 20L, 30L));
    }

    @Test
    void allUnknownTokensAggregateToUnknownWithExactCount() {
        stored("analyst", null, null, null, GatewayUsageOutcome.DELIVERED);
        stored("analyst", null, null, null, GatewayUsageOutcome.SECURITY_BLOCKED);
        entities.clear();

        assertThat(records.aggregateByActorSubject("analyst"))
                .isEqualTo(new GatewayUsageAggregate(2L, null, null, null));
    }

    @Test
    void orderingIsDeterministicWhenTimestampsAreEqual() {
        stored("analyst", 1L, 1L, 2L, GatewayUsageOutcome.DELIVERED);
        stored("analyst", 2L, 2L, 4L, GatewayUsageOutcome.DELIVERED);
        stored("analyst", 3L, 3L, 6L, GatewayUsageOutcome.DELIVERED);
        entities.clear();

        List<GatewayUsageRecord> firstRead =
                records.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");
        List<GatewayUsageRecord> secondRead =
                records.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");

        assertThat(firstRead).hasSize(3);
        assertThat(firstRead).isSortedAccordingTo(newestFirst());
        assertThat(secondRead)
                .extracting(GatewayUsageRecord::getId)
                .containsExactlyElementsOf(
                        firstRead.stream().map(GatewayUsageRecord::getId).toList());
    }

    @Test
    void queryResultCarriesMetadataFieldsOnly() {
        var fields = Arrays.stream(GatewayUsageRecord.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "id",
                "requestId",
                "actorSubject",
                "model",
                "promptTokens",
                "completionTokens",
                "totalTokens",
                "outcome",
                "createdAt");
    }

    @Test
    void windowAggregateReturnsEmptyAggregateForEmptyWindow() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-03-31T23:59:59Z");

        storedAt("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-02-15T00:00:00Z"));
        storedAt("analyst", 15L, 25L, 40L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-04-15T00:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(0L, null, null, null));
    }

    @Test
    void windowAggregateIncludesSingleMatchingRow() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 12L, 34L, 46L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-15T12:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(1L, 12L, 34L, 46L));
    }

    @Test
    void windowAggregateExcludesRowsBeforeTheWindow() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-02-28T23:59:59Z"));
        storedAt("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(1L, 10L, 20L, 30L));
    }

    @Test
    void windowAggregateIncludesRowExactlyAtFromBound() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 11L, 22L, 33L, GatewayUsageOutcome.DELIVERED, from);

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(1L, 11L, 22L, 33L));
    }

    @Test
    void windowAggregateExcludesRowExactlyAtToBound() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-15T00:00:00Z"));
        storedAt("analyst", 50L, 50L, 100L, GatewayUsageOutcome.DELIVERED, to);

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(1L, 10L, 20L, 30L));
    }

    @Test
    void windowAggregateAggregatesMultipleMatchingRowsCorrectly() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-05T00:00:00Z"));
        storedAt("analyst", 15L, 25L, 40L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-15T00:00:00Z"));
        storedAt("analyst", 5L, 10L, 15L, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-25T00:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(3L, 30L, 55L, 85L));
    }

    @Test
    void windowAggregateIsolatesActorsWithinTheSameWindow() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        storedAt("other-actor", 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        GatewayUsageAggregate analystAggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);
        GatewayUsageAggregate otherAggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("other-actor", from, to);

        assertThat(analystAggregate).isEqualTo(new GatewayUsageAggregate(1L, 10L, 20L, 30L));
        assertThat(otherAggregate).isEqualTo(new GatewayUsageAggregate(1L, 100L, 200L, 300L));
    }

    @Test
    void windowAggregateIncludesSecurityBlockedRecords() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 12L, 34L, 46L, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-12T00:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(1L, 12L, 34L, 46L));
    }

    @Test
    void windowAggregatePreservesNullWhenAllMatchingTokensAreUnknown() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", null, null, null, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        storedAt("analyst", null, null, null, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-20T00:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(2L, null, null, null));
    }

    @Test
    void windowAggregateSumsMixedKnownAndUnknownTokenValuesCorrectly() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        storedAt("analyst", 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-05T00:00:00Z"));
        storedAt("analyst", null, null, null, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        storedAt("analyst", 5L, null, null, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-15T00:00:00Z"));

        GatewayUsageAggregate aggregate =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(aggregate).isEqualTo(new GatewayUsageAggregate(3L, 15L, 20L, 30L));
    }

    @Test
    void windowAggregateRecordCountIsExactAndDeterministic() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        for (int i = 0; i < 5; i++) {
            storedAt("analyst", (long) i, (long) i, (long) (2 * i), GatewayUsageOutcome.DELIVERED,
                    Instant.parse(String.format("2026-03-%02dT10:00:00Z", i + 1)));
        }

        GatewayUsageAggregate firstCall =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);
        GatewayUsageAggregate secondCall =
                records.aggregateByActorSubjectAndCreatedAtBetween("analyst", from, to);

        assertThat(firstCall.recordCount()).isEqualTo(5L);
        assertThat(firstCall).isEqualTo(secondCall);
        assertThat(firstCall).isEqualTo(new GatewayUsageAggregate(5L, 10L, 10L, 20L));
    }
}

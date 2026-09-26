package com.aegivault.aegivault.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
}

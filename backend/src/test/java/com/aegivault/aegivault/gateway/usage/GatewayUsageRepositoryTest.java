package com.aegivault.aegivault.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
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
}

package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/**
 * Proves the real persistence path for gateway usage policies: the Flyway
 * V10 migration applies against PostgreSQL, the {@link GatewayUsagePolicy}
 * mapping validates, limits and labels round-trip (null stays null),
 * updates and deletes persist, owner scoping holds, and the listing order
 * is deterministic. No embedded database is used.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class GatewayUsagePolicyRepositoryTest {

    @Autowired
    private GatewayUsagePolicyRepository policies;

    @PersistenceContext
    private EntityManager entities;

    private static GatewayUsagePolicy policy(String owner, String name) {
        return new GatewayUsagePolicy(owner, name, null, 60L, null, null, true);
    }

    private GatewayUsagePolicy stored(String owner, String name) {
        GatewayUsagePolicy saved = policies.saveAndFlush(policy(owner, name));
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

    @Test
    void migratesMapsAndRoundTripsAPolicy() {
        UUID id = policies.saveAndFlush(new GatewayUsagePolicy(
                        "owner-1", "team-default", "monthly cap", 60L, 10000L, 500000L, true))
                .getId();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(id).orElseThrow();

        assertThat(found.getOwnerSubject()).isEqualTo("owner-1");
        assertThat(found.getName()).isEqualTo("team-default");
        assertThat(found.getDescription()).isEqualTo("monthly cap");
        assertThat(found.getRequestsPerMinute()).isEqualTo(60L);
        assertThat(found.getRequestsPerDay()).isEqualTo(10000L);
        assertThat(found.getTokensPerDay()).isEqualTo(500000L);
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void unsetLimitsStayNull() {
        UUID id = policies.saveAndFlush(policy("owner-1", "minute-only")).getId();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(id).orElseThrow();

        assertThat(found.getDescription()).isNull();
        assertThat(found.getRequestsPerDay()).isNull();
        assertThat(found.getTokensPerDay()).isNull();
    }

    @Test
    void disabledPolicyPersists() {
        UUID id = policies
                .saveAndFlush(new GatewayUsagePolicy("owner-1", "paused", null, null, 500L, null, false))
                .getId();
        entities.clear();

        assertThat(policies.findById(id).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void blankOwnerIsRejected() {
        assertThatThrownBy(() -> policy("   ", "no-owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> policy("owner-1", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not be blank");
    }

    @Test
    void overlongLabelsAreRejected() {
        assertThatThrownBy(() -> policy("owner-1", "n".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "ok", "d".repeat(1025), 1L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveLimitsAreRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "zero", null, 0L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestsPerMinute must be positive when present");
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "negative", null, null, -1L, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestsPerDay must be positive when present");
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "zero-tokens", null, null, null, 0L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tokensPerDay must be positive when present");
    }

    @Test
    void policyWithNoLimitIsRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "empty", null, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("policy must define at least one limit");
    }

    @Test
    void databaseRejectsNonPositiveLimits() {
        assertThatThrownBy(() -> entities
                        .createNativeQuery(
                                "INSERT INTO gateway_usage_policies"
                                        + " (owner_subject, name, requests_per_minute)"
                                        + " VALUES ('owner-1', 'raw-zero', 0)")
                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> entities
                        .createNativeQuery(
                                "INSERT INTO gateway_usage_policies"
                                        + " (owner_subject, name, tokens_per_day)"
                                        + " VALUES ('owner-1', 'raw-negative', -5)")
                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsBlankOwnerAndName() {
        assertThatThrownBy(() -> entities
                        .createNativeQuery(
                                "INSERT INTO gateway_usage_policies"
                                        + " (owner_subject, name, requests_per_minute)"
                                        + " VALUES ('  ', 'raw', 1)")
                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> entities
                        .createNativeQuery(
                                "INSERT INTO gateway_usage_policies"
                                        + " (owner_subject, name, requests_per_minute)"
                                        + " VALUES ('owner-1', '', 1)")
                        .executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void updatePersistsInPlace() {
        GatewayUsagePolicy stored = stored("owner-1", "before");
        stored.update("after", "changed", null, 500L, null, false);
        UUID id = policies.saveAndFlush(stored).getId();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(id).orElseThrow();

        assertThat(found.getName()).isEqualTo("after");
        assertThat(found.getDescription()).isEqualTo("changed");
        assertThat(found.getRequestsPerMinute()).isNull();
        assertThat(found.getRequestsPerDay()).isEqualTo(500L);
        assertThat(found.isEnabled()).isFalse();
        // Same row, same owner, unchanged creation instant. Counting is
        // owner-scoped: the non-transactional API tests commit rows to the
        // same real database, so a global count would be meaningless here.
        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1")).hasSize(1);
        assertThat(found.getOwnerSubject()).isEqualTo("owner-1");
        assertThat(found.getCreatedAt()).isEqualTo(stored.getCreatedAt());
    }

    @Test
    void rejectedUpdateLeavesThePolicyUntouched() {
        GatewayUsagePolicy stored = stored("owner-1", "keep-me");
        policies.flush();
        entities.clear();

        GatewayUsagePolicy reloaded = policies.findById(stored.getId()).orElseThrow();
        assertThatThrownBy(() -> reloaded.update("nope", null, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        entities.clear();

        GatewayUsagePolicy found = policies.findById(stored.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("keep-me");
        assertThat(found.getRequestsPerMinute()).isEqualTo(60L);
    }

    @Test
    void deleteRemovesThePolicy() {
        GatewayUsagePolicy stored = stored("owner-1", "doomed");
        policies.delete(stored);
        policies.flush();
        entities.clear();

        assertThat(policies.findById(stored.getId())).isEmpty();
        // Owner-scoped, for the same reason as above: other suites commit
        // rows to the same real database.
        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1")).isEmpty();
        assertThat(policies.findByIdAndOwnerSubject(stored.getId(), "owner-1")).isEmpty();
    }

    @Test
    void listingIsNewestFirstAndDeterministic() {
        stored("owner-1", "first");
        stored("owner-1", "second");
        stored("owner-1", "third");
        entities.clear();

        List<GatewayUsagePolicy> ordered = policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1");

        assertThat(ordered).hasSize(3);
        assertThat(ordered).isSortedAccordingTo(Comparator.comparing(GatewayUsagePolicy::getName).reversed());
        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1"))
                .extracting(GatewayUsagePolicy::getId)
                .containsExactlyElementsOf(ordered.stream().map(GatewayUsagePolicy::getId).toList());
    }

    @Test
    void ownersRemainIsolated() {
        stored("owner-1", "mine");
        stored("owner-2", "theirs");
        entities.clear();

        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1"))
                .extracting(GatewayUsagePolicy::getName)
                .containsExactly("mine");
        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-2"))
                .extracting(GatewayUsagePolicy::getName)
                .containsExactly("theirs");

        UUID mine = policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1").get(0).getId();
        assertThat(policies.findByIdAndOwnerSubject(mine, "owner-1")).isPresent();
        assertThat(policies.findByIdAndOwnerSubject(mine, "owner-2")).isEmpty();
    }

    @Test
    void unknownOwnerSeesNoPolicies() {
        stored("owner-1", "mine");
        entities.clear();

        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("nobody")).isEmpty();
        assertThat(policies.findByIdAndOwnerSubject(UUID.randomUUID(), "nobody")).isEmpty();
    }
}

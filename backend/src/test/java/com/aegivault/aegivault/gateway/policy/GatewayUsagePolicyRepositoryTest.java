package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.temporal.ChronoUnit;
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
        return new GatewayUsagePolicy(owner, name, null, 60L, null, null, null, true);
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
        UUID id = policies.saveAndFlush(new GatewayUsagePolicy("owner-1", "team-default", "monthly cap", 60L, 10000L, 500000L, 1L, true))
                .getId();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(id).orElseThrow();

        assertThat(found.getOwnerSubject()).isEqualTo("owner-1");
        assertThat(found.getName()).isEqualTo("team-default");
        assertThat(found.getDescription()).isEqualTo("monthly cap");
        assertThat(found.getRequestsPerMinute()).isEqualTo(60L);
        assertThat(found.getRequestsPerDay()).isEqualTo(10000L);
        assertThat(found.getTokensPerDay()).isEqualTo(500000L);
        assertThat(found.getReservationTokensPerRequest()).isEqualTo(1L);
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void aPositiveReservationAmountRoundTrips() {
        UUID id = policies
                .saveAndFlush(new GatewayUsagePolicy(
                        "owner-1", "capped", null, null, null, 100000L, 4000L, true))
                .getId();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(id).orElseThrow();

        assertThat(found.getTokensPerDay()).isEqualTo(100000L);
        // The amount the owner configured comes back exactly, never a default
        // and never a value derived from the limit.
        assertThat(found.getReservationTokensPerRequest()).isEqualTo(4000L);
    }

    @Test
    void anAbsentReservationStaysNullForANonTokenPolicy() {
        UUID id = policies.saveAndFlush(policy("owner-1", "minute-only")).getId();
        entities.clear();

        // A policy that declares no daily token limit has nothing to reserve,
        // so the column is genuinely NULL rather than defaulted to zero.
        assertThat(policies.findById(id).orElseThrow().getReservationTokensPerRequest())
                .isNull();
    }

    @Test
    void aReservationSurvivesAnInPlaceUpdate() {
        GatewayUsagePolicy stored = stored("owner-1", "capped");
        stored.update("after", null, null, null, 100000L, 250L, true);
        policies.saveAndFlush(stored);
        pause();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(stored.getId()).orElseThrow();

        assertThat(found.getTokensPerDay()).isEqualTo(100000L);
        assertThat(found.getReservationTokensPerRequest()).isEqualTo(250L);
    }

    @Test
    void updatingToNoTokenLimitClearsTheReservation() {
        GatewayUsagePolicy stored = policies.saveAndFlush(new GatewayUsagePolicy(
                "owner-1", "capped", null, null, null, 100000L, 250L, true));
        pause();
        stored.update("request-only", null, 60L, null, null, null, true);
        policies.saveAndFlush(stored);
        pause();
        entities.clear();

        GatewayUsagePolicy found = policies.findById(stored.getId()).orElseThrow();

        assertThat(found.getTokensPerDay()).isNull();
        assertThat(found.getReservationTokensPerRequest()).isNull();
    }

    @Test
    void aTokenLimitWithoutAReservationIsRejected() {
        // The whole point of the field: a daily token limit with no
        // pre-request amount would leave future enforcement unable to reserve
        // anything, so such a policy is never stored.
        assertThatThrownBy(() -> new GatewayUsagePolicy(
                        "owner-1", "tokens-only", null, null, null, 1000L, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationTokensPerRequest is required when tokensPerDay is present");
    }

    @Test
    void aReservationGreaterThanTheDayLimitIsRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicy(
                        "owner-1", "over", null, null, null, 1000L, 1001L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationTokensPerRequest must not exceed tokensPerDay");
    }

    @Test
    void aReservationEqualToTheDayLimitIsAccepted() {
        // The boundary is inclusive: holding the whole day for one request is
        // unsatisfiable in practice but not a contradiction, so it is not
        // rejected and is never silently lowered.
        assertThat(new GatewayUsagePolicy(
                                "owner-1", "whole-day", null, null, null, 1000L, 1000L, true)
                        .getReservationTokensPerRequest())
                .isEqualTo(1000L);
    }

    @Test
    void aNonPositiveReservationIsRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicy(
                        "owner-1", "zero", null, null, null, 1000L, 0L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationTokensPerRequest must be positive when present");
        assertThatThrownBy(() -> new GatewayUsagePolicy(
                        "owner-1", "negative", null, 60L, null, null, -1L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationTokensPerRequest must be positive when present");
    }

    @Test
    void aReservationWithoutATokenLimitIsAccepted() {
        // A policy that constrains tokens not at all may still declare a
        // reservation; the value is kept rather than silently discarded.
        assertThat(new GatewayUsagePolicy(
                                "owner-1", "requests", null, 60L, null, null, 500L, true)
                        .getReservationTokensPerRequest())
                .isEqualTo(500L);
    }

    @Test
    void updateAppliesTheSameCrossFieldRules() {
        GatewayUsagePolicy stored = stored("owner-1", "capped");

        assertThatThrownBy(() -> stored.update("x", null, null, null, 1000L, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationTokensPerRequest is required when tokensPerDay is present");
        assertThatThrownBy(() -> stored.update("x", null, null, null, 1000L, 2000L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationTokensPerRequest must not exceed tokensPerDay");

        // A rejected update mutates nothing, exactly as before.
        assertThat(stored.getTokensPerDay()).isNull();
        assertThat(stored.getReservationTokensPerRequest()).isNull();
        assertThat(stored.getName()).isEqualTo("capped");
    }

    @Test
    void theDatabaseRejectsAReservationGreaterThanTheDayLimit() {
        // The cross-field rule is enforced by PostgreSQL as well as the
        // aggregate, so a row written by anything other than the entity still
        // cannot hold an unsatisfiable pair.
        assertThatThrownBy(() -> entities
                        .createNativeQuery("""
                                INSERT INTO gateway_usage_policies (
                                    owner_subject, name, requests_per_minute, tokens_per_day,
                                    reservation_tokens_per_request, enabled)
                                VALUES ('owner-1', 'raw', 60, 1000, 1001, TRUE)
                                """)
                        .executeUpdate())
                .hasMessageContaining("chk_usage_policy_reservation_within_tokens_per_day");
    }

    @Test
    void theDatabaseRejectsANonPositiveReservation() {
        assertThatThrownBy(() -> entities
                        .createNativeQuery("""
                                INSERT INTO gateway_usage_policies (
                                    owner_subject, name, tokens_per_day,
                                    reservation_tokens_per_request, enabled)
                                VALUES ('owner-1', 'raw-zero', 1000, 0, TRUE)
                                """)
                        .executeUpdate())
                .hasMessageContaining("chk_usage_policy_reservation_tokens_per_request_positive");
    }

    @Test
    void theDatabaseRejectsATokenLimitWithoutAReservation() {
        assertThatThrownBy(() -> entities
                        .createNativeQuery("""
                                INSERT INTO gateway_usage_policies (
                                    owner_subject, name, tokens_per_day, enabled)
                                VALUES ('owner-1', 'raw-unreserved', 1000, TRUE)
                                """)
                        .executeUpdate())
                .hasMessageContaining("chk_usage_policy_reservation_requires_tokens_per_day");
    }

    @Test
    void theDatabaseAcceptsANonTokenPolicyWithNoReservation() {
        // Existing policies, whose tokens_per_day is NULL, remain valid rows:
        // the migration adds a nullable column and backfills nothing.
        entities.createNativeQuery("""
                INSERT INTO gateway_usage_policies (owner_subject, name, requests_per_minute, enabled)
                VALUES ('owner-1', 'legacy-request-only', 60, TRUE)
                """).executeUpdate();
        pause();
        entities.clear();

        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1"))
                .anySatisfy(found -> {
                    assertThat(found.getName()).isEqualTo("legacy-request-only");
                    assertThat(found.getTokensPerDay()).isNull();
                    assertThat(found.getReservationTokensPerRequest()).isNull();
                });
    }

    @Test
    void ownerIsolationIsUnchangedByTheNewColumn() {
        String owner = "owner-" + UUID.randomUUID();
        policies.saveAndFlush(new GatewayUsagePolicy(
                owner, "mine", null, null, null, 100000L, 4000L, true));
        pause();
        UUID id = policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner).get(0).getId();
        entities.clear();

        // A foreign owner still sees an empty lookup, so the new column cannot
        // become a cross-owner side channel.
        assertThat(policies.findByIdAndOwnerSubject(id, "someone-else")).isEmpty();
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
                .saveAndFlush(new GatewayUsagePolicy("owner-1", "paused", null, null, 500L, null, null, false))
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
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "ok", "d".repeat(1025), 1L, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveLimitsAreRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "zero", null, 0L, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestsPerMinute must be positive when present");
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "negative", null, null, -1L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestsPerDay must be positive when present");
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "zero-tokens", null, null, null, 0L, 1L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tokensPerDay must be positive when present");
    }

    @Test
    void policyWithNoLimitIsRejected() {
        assertThatThrownBy(() -> new GatewayUsagePolicy("owner-1", "empty", null, null, null, null, null, true))
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
        stored.update("after", "changed", null, 500L, null, null, false);
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
        // Compared at millisecond precision because PostgreSQL truncates
        // sub-microsecond nanos on the round trip.
        assertThat(found.getCreatedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(stored.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void rejectedUpdateLeavesThePolicyUntouched() {
        GatewayUsagePolicy stored = stored("owner-1", "keep-me");
        policies.flush();
        entities.clear();

        GatewayUsagePolicy reloaded = policies.findById(stored.getId()).orElseThrow();
        assertThatThrownBy(() -> reloaded.update("nope", null, null, null, null, null, true))
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

    @Test
    void enabledPoliciesAreFoundForTheirOwner() {
        String owner = isolatedOwner();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "on", null, 60L, null, null, null, true));
        pause();
        entities.clear();

        List<GatewayUsagePolicy> found =
                policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(owner);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getName()).isEqualTo("on");
        assertThat(found.get(0).getOwnerSubject()).isEqualTo(owner);
    }

    @Test
    void disabledPoliciesAreExcludedFromTheEnabledLookup() {
        String owner = isolatedOwner();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "off", null, 60L, null, null, null, false));
        pause();
        entities.clear();

        assertThat(policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(owner)).isEmpty();
        // It is still a stored policy, just not an enabled candidate.
        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner)).hasSize(1);
    }

    @Test
    void onlyEnabledPoliciesAreReturnedWhenBothKindsExist() {
        String owner = isolatedOwner();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "off", null, 60L, null, null, null, false));
        pause();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "on", null, 30L, null, null, null, true));
        pause();
        entities.clear();

        assertThat(policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(owner))
                .extracting(GatewayUsagePolicy::getName)
                .containsExactly("on");
    }

    @Test
    void multipleEnabledPoliciesAreReturnedNewestFirstDeterministically() {
        String owner = isolatedOwner();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "first", null, 1L, null, null, null, true));
        pause();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "second", null, 2L, null, null, null, true));
        pause();
        entities.clear();

        List<GatewayUsagePolicy> firstRead =
                policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(owner);
        List<GatewayUsagePolicy> secondRead =
                policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(owner);

        // Deterministic newest-first, and stable across reads. The order is a
        // read convenience only: the resolver treats more than one candidate
        // as ambiguous and never breaks the tie with this ordering.
        assertThat(firstRead)
                .extracting(GatewayUsagePolicy::getName)
                .containsExactly("second", "first");
        assertThat(secondRead)
                .extracting(GatewayUsagePolicy::getId)
                .containsExactlyElementsOf(
                        firstRead.stream().map(GatewayUsagePolicy::getId).toList());
    }

    @Test
    void enabledLookupIsOwnerScoped() {
        String owner = isolatedOwner();
        policies.saveAndFlush(new GatewayUsagePolicy(owner, "mine-on", null, 60L, null, null, null, true));
        pause();
        policies.saveAndFlush(new GatewayUsagePolicy("other-owner", "theirs-on", null, 60L, null, null, null, true));
        pause();
        entities.clear();

        assertThat(policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(owner))
                .extracting(GatewayUsagePolicy::getName)
                .containsExactly("mine-on");
        assertThat(policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("other-owner"))
                .extracting(GatewayUsagePolicy::getName)
                .containsExactly("theirs-on");
    }

    @Test
    void enabledLookupForAnUnknownOwnerIsEmpty() {
        assertThat(policies.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc(
                        "owner-does-not-exist-" + UUID.randomUUID()))
                .isEmpty();
    }

    /** A per-test owner so this suite never collides with committed rows. */
    private static String isolatedOwner() {
        return "repo-test-owner-" + UUID.randomUUID();
    }
}

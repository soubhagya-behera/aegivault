package com.aegivault.aegivault.sanitization.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
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
 * Proves the real persistence path for policies: the Flyway V6 migration
 * applies against PostgreSQL, the {@link SanitizationPolicy} and
 * {@link PolicyRule} mappings validate (composite id plus derived
 * {@code policy_id}), owner scoping holds, and the listing order is
 * deterministic. Schema CHECKs are proved separately by
 * {@link SanitizationPolicySchemaTest}. No embedded database is used.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SanitizationPolicyRepositoryTest {

    @Autowired
    private SanitizationPolicyRepository policies;

    @PersistenceContext
    private EntityManager entities;

    private static List<TransformationRule> emailRule() {
        return List.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
    }

    private SanitizationPolicy policy(String owner, String name) {
        return policies.saveAndFlush(new SanitizationPolicy(owner, name, "v1", null, emailRule()));
    }

    private static void pause() {
        try {
            Thread.sleep(15L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void migratesMapsAndRoundTripsAPolicyWithItsRules() {
        UUID id = policies
                .saveAndFlush(new SanitizationPolicy(
                        "owner-1",
                        "pii-default",
                        "v1",
                        "Covers emails and phones",
                        List.of(
                                new TransformationRule(
                                        PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL),
                                new TransformationRule(PiiType.PHONE, TransformationStrategy.REDACT))))
                .getId();
        entities.clear();

        SanitizationPolicy found = policies.findById(id).orElseThrow();

        assertThat(found.getOwnerSubject()).isEqualTo("owner-1");
        assertThat(found.getName()).isEqualTo("pii-default");
        assertThat(found.getVersion()).isEqualTo("v1");
        assertThat(found.getDescription()).isEqualTo("Covers emails and phones");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getRules())
                .extracting(PolicyRule::getPiiType)
                .containsExactly(PiiType.EMAIL, PiiType.PHONE);
        assertThat(found.getRules())
                .extracting(PolicyRule::getTransformationStrategy)
                .containsExactly(TransformationStrategy.SYNTHETIC_EMAIL, TransformationStrategy.REDACT);
        assertThat(found.getRules()).allSatisfy(rule -> assertThat(rule.getPolicyId()).isEqualTo(id));
        assertThat(found.getRules())
                .allSatisfy(rule -> assertThat(rule.getPolicy().getId()).isEqualTo(id));
    }

    @Test
    void ownerScopedLookupFindsOwnPolicyOnly() {
        SanitizationPolicy saved = policy("owner-1", "mine");

        assertThat(policies.findByIdAndOwnerSubject(saved.getId(), "owner-1")).isPresent();
        assertThat(policies.findByIdAndOwnerSubject(saved.getId(), "owner-2")).isEmpty();
        assertThat(policies.findByIdAndOwnerSubject(UUID.randomUUID(), "owner-1")).isEmpty();
    }

    @Test
    void ownerListingContainsOnlyOwnPolicies() {
        SanitizationPolicy mine = policy("owner-1", "mine");
        policy("owner-2", "theirs");

        List<SanitizationPolicy> owned =
                policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1");

        assertThat(owned).extracting(SanitizationPolicy::getId).containsExactly(mine.getId());
    }

    @Test
    void listingIsNewestFirstWithIdAsDeterministicTiebreak() {
        SanitizationPolicy first = policy("owner-1", "first");
        pause();
        SanitizationPolicy second = policy("owner-1", "second");
        pause();
        SanitizationPolicy third = policy("owner-1", "third");

        List<SanitizationPolicy> ordered =
                policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1");

        assertThat(ordered).extracting(SanitizationPolicy::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(ordered).extracting(SanitizationPolicy::getName)
                .containsExactly("third", "second", "first");

        entities.clear();
        entities.createNativeQuery(
                        "UPDATE sanitization_policies SET created_at = TIMESTAMPTZ '2020-01-01 00:00:00Z'"
                                + " WHERE owner_subject = 'owner-1'")
                .executeUpdate();
        entities.clear();

        List<UUID> tied = policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1").stream()
                .map(SanitizationPolicy::getId)
                .toList();

        assertThat(tied).hasSize(3);
        assertThat(tied).isSortedAccordingTo(Comparator.comparing(UUID::toString).reversed());
    }
}

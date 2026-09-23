package com.aegivault.aegivault.sanitization.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-level policy behavior against real PostgreSQL: creation persists
 * one owner's aggregate with exactly the rules it was given (in the
 * deterministic order), the aggregate invariants hold for any caller, and
 * reads are owner-scoped with identical missing-vs-foreign responses. Each
 * test rolls back.
 */
@SpringBootTest
@Transactional
class SanitizationPolicyServiceTest {

    @Autowired
    private SanitizationPolicyService service;

    @Autowired
    private SanitizationPolicyRepository policies;

    @PersistenceContext
    private EntityManager entities;

    private static String owner() {
        return "owner-" + UUID.randomUUID();
    }

    private static TransformationRule rule(PiiType type, TransformationStrategy strategy) {
        return new TransformationRule(type, strategy);
    }

    private static List<TransformationRule> emailRule() {
        return List.of(rule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
    }

    private PolicyResponse create(String owner) {
        return service.create(owner, "pii-default", "v1", "Covers emails", emailRule());
    }

    @Test
    void createPersistsLabelsRulesAndOwner() {
        String owner = owner();
        PolicyResponse created = service.create(
                owner,
                "pii-default",
                "v1",
                "Covers phones and emails",
                List.of(
                        rule(PiiType.PHONE, TransformationStrategy.REDACT),
                        rule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL)));
        entities.clear();

        SanitizationPolicy stored = policies.findById(created.id()).orElseThrow();

        assertThat(stored.getOwnerSubject()).isEqualTo(owner);
        assertThat(stored.getName()).isEqualTo("pii-default");
        assertThat(stored.getVersion()).isEqualTo("v1");
        assertThat(stored.getDescription()).isEqualTo("Covers phones and emails");
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.createdAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(stored.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
        assertThat(created.rules())
                .extracting(TransformationRule::piiType)
                .containsExactly(PiiType.EMAIL, PiiType.PHONE);
        assertThat(stored.getRules())
                .extracting(PolicyRule::getPiiType)
                .containsExactly(PiiType.EMAIL, PiiType.PHONE);
        assertThat(created.rules()).doesNotContain(rule(PiiType.PHONE, TransformationStrategy.MASK));
    }

    @Test
    void blankDescriptionBecomesNull() {
        PolicyResponse created = service.create(owner(), "pii-default", "v1", "   ", emailRule());

        assertThat(created.description()).isNull();
    }

    @Test
    void duplicatePiiTypeIsRejectedBeforeAnythingPersists() {
        String owner = owner();

        assertThatThrownBy(() -> service.create(
                        owner,
                        "duplicates",
                        "v1",
                        null,
                        List.of(
                                rule(PiiType.EMAIL, TransformationStrategy.REDACT),
                                rule(PiiType.EMAIL, TransformationStrategy.MASK))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner)).isEmpty();
    }

    @Test
    void emptyRulesAreRejectedBeforeAnythingPersists() {
        String owner = owner();

        assertThatThrownBy(() -> service.create(owner, "empty", "v1", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("policy must cover at least one PII type");

        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner)).isEmpty();
    }

    @Test
    void blankAndOverlongLabelsAreRejected() {
        String owner = owner();

        assertThatThrownBy(() -> service.create(owner, "   ", "v1", null, emailRule()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(owner, "n", " ".repeat(1), null, emailRule()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(owner, "n".repeat(256), "v1", null, emailRule()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(owner, "n", "v".repeat(256), null, emailRule()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(owner, "n", "v1", "d".repeat(1025), emailRule()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(policies.findByOwnerSubjectOrderByCreatedAtDescIdDesc(owner)).isEmpty();
    }

    @Test
    void blankOwnerIsRejected() {
        assertThatThrownBy(() -> service.create("   ", "n", "v1", null, emailRule()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
    }

    @Test
    void listContainsOnlyOwnPolicies() {
        String mine = owner();
        create(mine);
        create(owner());

        List<PolicyResponse> listed = service.list(mine);

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).name()).isEqualTo("pii-default");
        assertThat(service.list(owner())).isEmpty();
    }

    @Test
    void foreignAndMissingPoliciesProduceIdenticalFailures() {
        String owner = owner();
        String stranger = owner();
        UUID mine = create(owner).id();

        String foreignMessage = null;
        try {
            service.get(stranger, mine);
        } catch (PolicyNotFoundException ex) {
            foreignMessage = ex.getMessage();
        }
        String missingMessage = null;
        try {
            service.get(stranger, UUID.randomUUID());
        } catch (PolicyNotFoundException ex) {
            missingMessage = ex.getMessage();
        }

        assertThat(foreignMessage).isNotNull();
        assertThat(foreignMessage).isEqualTo(missingMessage);
        assertThat(foreignMessage).isEqualTo("Policy not found.");
        assertThat(service.get(owner, mine).id()).isEqualTo(mine);
    }
}

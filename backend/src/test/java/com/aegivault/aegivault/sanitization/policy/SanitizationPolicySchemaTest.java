package com.aegivault.aegivault.sanitization.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.metamodel.Attribute;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

/**
 * Proves the V6 schema independently of the application code: the enum
 * vocabulary CHECKs, the label-length CHECKs, and the composite
 * {@code (policy_id, pii_type)} primary key reject what the aggregate also
 * rejects, rule rows disappear with their policy, and neither table nor
 * view has anywhere to keep raw data. No embedded database is used.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SanitizationPolicySchemaTest {

    @Autowired
    private SanitizationPolicyRepository policies;

    @PersistenceContext
    private EntityManager entities;

    private UUID policyId(String owner) {
        return policies
                .saveAndFlush(new SanitizationPolicy(
                        owner, "schema-check", "v1", null, List.of(rule(PiiType.EMAIL))))
                .getId();
    }

    private static TransformationRule rule(PiiType type) {
        return new TransformationRule(type, TransformationStrategy.REDACT);
    }

    private void insertRule(UUID policyId, String piiType, String strategy) {
        entities.createNativeQuery(
                        "INSERT INTO sanitization_policy_rules (policy_id, pii_type, transformation_strategy)"
                                + " VALUES ('" + policyId + "', '" + piiType + "', '" + strategy + "')")
                .executeUpdate();
    }

    @Test
    void duplicatePiiTypeWithinOnePolicyIsRejectedByThePrimaryKey() {
        UUID policyId = policyId("owner-1");

        insertRule(policyId, "PHONE", "REDACT");

        assertThatThrownBy(() -> insertRule(policyId, "PHONE", "MASK"))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void unsupportedPiiTypeValueIsRejectedBySchema() {
        UUID policyId = policyId("owner-1");

        assertThatThrownBy(() -> insertRule(policyId, "NOT_A_TYPE", "REDACT"))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void unsupportedStrategyValueIsRejectedBySchema() {
        UUID policyId = policyId("owner-1");

        assertThatThrownBy(() -> insertRule(policyId, "PHONE", "SHRED"))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void blankPolicyNameIsRejectedBySchema() {
        assertThatThrownBy(() -> entities.createNativeQuery(
                        "INSERT INTO sanitization_policies (owner_subject, name, version)"
                                + " VALUES ('owner-1', '   ', 'v1')")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void overlongPolicyNameIsRejectedBySchema() {
        assertThatThrownBy(() -> entities.createNativeQuery(
                        "INSERT INTO sanitization_policies (owner_subject, name, version)"
                                + " VALUES ('owner-1', '" + "n".repeat(256) + "', 'v1')")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void overlongPolicyVersionIsRejectedBySchema() {
        assertThatThrownBy(() -> entities.createNativeQuery(
                        "INSERT INTO sanitization_policies (owner_subject, name, version)"
                                + " VALUES ('owner-1', 'name', '" + "v".repeat(256) + "')")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void overlongDescriptionIsRejectedBySchema() {
        assertThatThrownBy(() -> entities.createNativeQuery(
                        "INSERT INTO sanitization_policies (owner_subject, name, version, description)"
                                + " VALUES ('owner-1', 'name', 'v1', '" + "d".repeat(1025) + "')")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingAPolicyRemovesItsRules() {
        UUID policyId = policyId("owner-1");
        insertRule(policyId, "PHONE", "MASK");

        entities.clear();
        entities.createNativeQuery("DELETE FROM sanitization_policies WHERE id = '" + policyId + "'")
                .executeUpdate();
        entities.clear();

        Number remaining = (Number) entities
                .createNativeQuery(
                        "SELECT count(*) FROM sanitization_policy_rules WHERE policy_id = '" + policyId + "'")
                .getSingleResult();

        assertThat(remaining.intValue()).isZero();
    }

    @Test
    void policyTablesAndViewsHaveNowhereToStoreRawData() {
        policyId("owner-1");

        assertThat(entities.getMetamodel().entity(SanitizationPolicy.class).getAttributes().stream()
                        .map(Attribute::getName)
                        .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "id", "ownerSubject", "name", "version", "description", "rules", "createdAt",
                        "updatedAt");
        assertThat(entities.getMetamodel().entity(PolicyRule.class).getAttributes().stream()
                        .map(Attribute::getName)
                        .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("policyId", "piiType", "transformationStrategy", "policy");
        assertThat(java.util.Arrays.stream(PolicyResponse.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactlyInAnyOrder(
                        "id", "name", "version", "description", "rules", "createdAt", "updatedAt");
    }
}

package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the policy snapshot is a deterministic, immutable, metadata-only
 * freeze of one plan: identical plans always render identical JSON with
 * alphabetically ordered keys, and the snapshot never follows later policy
 * changes.
 */
class PolicySnapshotTest {

    private static TransformationPlan twoRulePlan() {
        return TransformationPlan.of(List.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL),
                new TransformationRule(PiiType.ADDRESS, TransformationStrategy.REDACT)));
    }

    @Test
    void snapshotRendersCanonicalJsonWithAlphabeticalKeyOrder() {
        PolicySnapshot snapshot = PolicySnapshot.fromPlan("default", "v1", twoRulePlan());

        assertThat(snapshot.toJson()).isEqualTo(
                "{\"policyName\":\"default\",\"policyVersion\":\"v1\","
                        + "\"rules\":{\"ADDRESS\":\"REDACT\",\"EMAIL\":\"SYNTHETIC_EMAIL\"}}");
    }

    @Test
    void sameMappingAlwaysRendersIdenticalJson() {
        PolicySnapshot first = PolicySnapshot.fromPlan("default", "v1", twoRulePlan());
        PolicySnapshot second = PolicySnapshot.fromPlan("default", "v1", twoRulePlan());

        assertThat(first.toJson()).isEqualTo(second.toJson());
    }

    @Test
    void keyOrderDoesNotDependOnPlanConstructionOrder() {
        TransformationPlan reversed = TransformationPlan.of(List.of(
                new TransformationRule(PiiType.ADDRESS, TransformationStrategy.REDACT),
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL)));

        assertThat(PolicySnapshot.fromPlan("default", "v1", reversed).toJson())
                .isEqualTo(PolicySnapshot.fromPlan("default", "v1", twoRulePlan()).toJson());
    }

    @Test
    void defaultPolicySnapshotCoversAllElevenTypesDeterministically() {
        PolicySnapshot snapshot =
                PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan());

        assertThat(snapshot.toJson()).isEqualTo(
                "{\"policyName\":\"default\",\"policyVersion\":\"v1\",\"rules\":{"
                        + "\"ADDRESS\":\"REDACT\","
                        + "\"API_KEY\":\"REDACT\","
                        + "\"CREDIT_CARD\":\"MASK\","
                        + "\"CUSTOM_IDENTIFIER\":\"HASH_SHA256\","
                        + "\"EMAIL\":\"SYNTHETIC_EMAIL\","
                        + "\"IP_ADDRESS\":\"HASH_SHA256\","
                        + "\"JWT\":\"REDACT\","
                        + "\"PASSWORD\":\"HASH_SHA256\","
                        + "\"PERSON_NAME\":\"REDACT\","
                        + "\"PHONE\":\"SYNTHETIC_PHONE\","
                        + "\"UUID\":\"HASH_SHA256\"}}");
        assertThat(snapshot.toJson())
                .isEqualTo(PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan()).toJson());
    }

    @Test
    void snapshotIsUnaffectedByLaterPolicyChanges() {
        PolicySnapshot frozen = PolicySnapshot.fromPlan("default", "v1", twoRulePlan());
        String frozenJson = frozen.toJson();

        TransformationPlan changed = TransformationPlan.of(List.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK),
                new TransformationRule(PiiType.ADDRESS, TransformationStrategy.MASK),
                new TransformationRule(PiiType.PHONE, TransformationStrategy.REDACT)));
        PolicySnapshot later = PolicySnapshot.fromPlan("default", "v2", changed);

        assertThat(frozen.toJson()).isEqualTo(frozenJson);
        assertThat(later.toJson()).isNotEqualTo(frozenJson);
    }

    @Test
    void snapshotCarriesNoDataValues() {
        PolicySnapshot snapshot =
                PolicySnapshot.fromPlan("default", "v1", DefaultTransformationPolicy.plan());

        assertThat(snapshot.toJson()).doesNotContain("@", ".csv", "sk-", "example.com");
        assertThat(snapshot.rules()).hasSize(11);
    }

    @Test
    void labelsAreTrimmed() {
        PolicySnapshot snapshot = PolicySnapshot.fromPlan("  default  ", "  v1  ", twoRulePlan());

        assertThat(snapshot.policyName()).isEqualTo("default");
        assertThat(snapshot.policyVersion()).isEqualTo("v1");
    }

    @Test
    void specialCharactersInLabelsAreEscaped() {
        PolicySnapshot snapshot =
                PolicySnapshot.fromPlan("my \"quoted\" policy", "v\\1", twoRulePlan());

        assertThat(snapshot.toJson()).contains("\"policyName\":\"my \\\"quoted\\\" policy\"");
        assertThat(snapshot.toJson()).contains("\"policyVersion\":\"v\\\\1\"");
    }

    @Test
    void rulesAreUnmodifiable() {
        PolicySnapshot snapshot = PolicySnapshot.fromPlan("default", "v1", twoRulePlan());

        assertThatThrownBy(() -> snapshot.rules().put(PiiType.PHONE, TransformationStrategy.KEEP))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidSnapshotsAreRejected() {
        assertThatThrownBy(() -> PolicySnapshot.fromPlan(null, "v1", twoRulePlan()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySnapshot.fromPlan("   ", "v1", twoRulePlan()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySnapshot.fromPlan("default", null, twoRulePlan()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySnapshot.fromPlan("default", "  ", twoRulePlan()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PolicySnapshot.fromPlan("default", "v1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PolicySnapshot.fromPlan(
                        "default", "v1", TransformationPlan.of(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

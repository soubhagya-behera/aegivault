package com.aegivault.aegivault.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link DefaultTransformationPolicy} (no Spring, no I/O).
 *
 * <p>Every one of the eleven {@link PiiType} values must have an intentional
 * mapping; the policy is pinned exactly so an accidental change is a test
 * failure instead of a silent drift.
 */
class DefaultTransformationPolicyTest {

    @Test
    void coversEveryPiiType() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        for (PiiType piiType : PiiType.values()) {
            assertThat(plan.strategyFor(piiType))
                    .as("default strategy for %s", piiType)
                    .isPresent();
        }
    }

    @Test
    void mapsEveryTypeToTheDocumentedStrategy() {
        Map<PiiType, TransformationStrategy> expected = new EnumMap<>(PiiType.class);
        expected.put(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL);
        expected.put(PiiType.PHONE, TransformationStrategy.SYNTHETIC_PHONE);
        expected.put(PiiType.PERSON_NAME, TransformationStrategy.REDACT);
        expected.put(PiiType.ADDRESS, TransformationStrategy.REDACT);
        expected.put(PiiType.CREDIT_CARD, TransformationStrategy.MASK);
        expected.put(PiiType.IP_ADDRESS, TransformationStrategy.HASH_SHA256);
        expected.put(PiiType.UUID, TransformationStrategy.HASH_SHA256);
        expected.put(PiiType.API_KEY, TransformationStrategy.REDACT);
        expected.put(PiiType.PASSWORD, TransformationStrategy.HASH_SHA256);
        expected.put(PiiType.JWT, TransformationStrategy.REDACT);
        expected.put(PiiType.CUSTOM_IDENTIFIER, TransformationStrategy.HASH_SHA256);
        assertThat(DefaultTransformationPolicy.plan().strategies()).isEqualTo(expected);
    }

    @Test
    void neverKeepsADetectedTypeByDefault() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        for (PiiType piiType : PiiType.values()) {
            assertThat(plan.strategyFor(piiType)).hasValueSatisfying(
                    strategy -> assertThat(strategy).isNotEqualTo(TransformationStrategy.KEEP));
        }
    }

    @Test
    void producedPlansAreImmutableAndDeterministic() {
        assertThat(DefaultTransformationPolicy.plan()).isEqualTo(DefaultTransformationPolicy.plan());
        assertThatThrownBy(() -> DefaultTransformationPolicy.plan().strategies().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

package com.aegivault.aegivault.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link TransformationPlan} (no Spring, no I/O). */
class TransformationPlanTest {

    @Test
    void buildsFromExplicitRules() {
        TransformationPlan plan = TransformationPlan.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        assertThat(plan.strategyFor(PiiType.EMAIL)).contains(TransformationStrategy.SYNTHETIC_EMAIL);
        assertThat(plan.strategies())
                .containsEntry(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL)
                .hasSize(1);
    }

    @Test
    void rejectsDuplicatePiiTypes() {
        assertThatThrownBy(() -> TransformationPlan.of(
                        new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT),
                        new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate transformation rule for PII type EMAIL");
    }

    @Test
    void rejectsNullRules() {
        assertThatThrownBy(() -> TransformationPlan.of((List<TransformationRule>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransformationPlan.of(new TransformationRule[] {null}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransformationPlan.of(Arrays.asList((TransformationRule) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rule must not be null");
    }

    @Test
    void rejectsNullPiiTypeAndNullStrategyInRules() {
        assertThatThrownBy(() -> new TransformationRule(null, TransformationStrategy.REDACT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("piiType must not be null");
        assertThatThrownBy(() -> new TransformationRule(PiiType.EMAIL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("strategy must not be null");
    }

    @Test
    void allowsAnEmptyPlan() {
        TransformationPlan plan = TransformationPlan.of();
        assertThat(plan.strategies()).isEmpty();
        assertThat(plan.strategyFor(PiiType.EMAIL)).isEmpty();
    }

    @Test
    void strategyForIsEmptyForUncoveredTypes() {
        TransformationPlan plan = TransformationPlan.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        assertThat(plan.strategyFor(PiiType.PHONE)).isEmpty();
    }

    @Test
    void strategyForRejectsNullTypes() {
        TransformationPlan plan = TransformationPlan.of();
        assertThatThrownBy(() -> plan.strategyFor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void storedMappingsAreImmutable() {
        TransformationPlan plan = TransformationPlan.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        assertThatThrownBy(() -> plan.strategies().put(PiiType.PHONE, TransformationStrategy.MASK))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullStrategiesInDirectConstruction() {
        EnumMap<PiiType, TransformationStrategy> invalid = new EnumMap<>(PiiType.class);
        invalid.put(PiiType.EMAIL, null);
        assertThatThrownBy(() -> new TransformationPlan(invalid)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransformationPlan(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void plansWithIdenticalRulesAreEqual() {
        TransformationRule rule = new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT);
        assertThat(TransformationPlan.of(rule)).isEqualTo(TransformationPlan.of(Collections.singletonList(rule)));
        assertThat(TransformationPlan.of(rule).toString()).doesNotContain("@");
    }
}

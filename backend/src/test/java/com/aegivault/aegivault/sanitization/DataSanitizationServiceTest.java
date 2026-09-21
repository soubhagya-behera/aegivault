package com.aegivault.aegivault.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.strategy.KeepTransformation;
import com.aegivault.aegivault.sanitization.strategy.MaskTransformation;
import com.aegivault.aegivault.sanitization.strategy.RedactTransformation;
import com.aegivault.aegivault.sanitization.strategy.Sha256HashTransformation;
import com.aegivault.aegivault.sanitization.strategy.SyntheticEmailTransformation;
import com.aegivault.aegivault.sanitization.strategy.SyntheticPhoneTransformation;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import com.aegivault.aegivault.sanitization.strategy.ValueTransformation;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link DataSanitizationService} (no Spring, no I/O). */
class DataSanitizationServiceTest {

    private DataSanitizationService service;

    private static TransformationRegistry registry() {
        List<ValueTransformation> transformations = List.of(
                new KeepTransformation(),
                new RedactTransformation(),
                new MaskTransformation(),
                new SyntheticEmailTransformation(),
                new SyntheticPhoneTransformation(),
                new Sha256HashTransformation());
        return new TransformationRegistry(transformations);
    }

    @BeforeEach
    void setUp() {
        service = new DataSanitizationService(registry());
    }

    @Test
    void resolvesTheStrategyConfiguredByThePlan() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        assertThat(service.resolveStrategy(PiiType.EMAIL, plan))
                .isEqualTo(TransformationStrategy.SYNTHETIC_EMAIL);
    }

    @Test
    void appliesTheResolvedStrategyToTheValue() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        String first = service.sanitize("alice@example.com", PiiType.EMAIL, plan);
        assertThat(first).endsWith("@example.invalid");
        assertThat(first).doesNotContain("alice");
    }

    @Test
    void honoursExplicitStrategyOverridesPerPlan() {
        TransformationPlan redactPlan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        TransformationPlan syntheticPlan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        assertThat(service.sanitize("alice@example.com", PiiType.EMAIL, redactPlan))
                .isEqualTo("[REDACTED]");
        assertThat(service.sanitize("alice@example.com", PiiType.EMAIL, syntheticPlan))
                .endsWith("@example.invalid");
    }

    @Test
    void failsClosedWhenThePlanDoesNotCoverTheType() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT));
        assertThatThrownBy(() -> service.sanitize("alice@example.com", PiiType.PHONE, plan))
                .isInstanceOf(MissingTransformationException.class)
                .hasMessageContaining("PHONE")
                .hasMessageNotContaining("alice@example.com");
    }

    @Test
    void missingStrategyExceptionNamesOnlyPolicyMetadata() {
        TransformationPlan plan = TransformationPlan.of();
        String sensitiveValue = "alice-secret-value-xyz";
        assertThatThrownBy(() -> service.sanitize(sensitiveValue, PiiType.API_KEY, plan))
                .isInstanceOf(MissingTransformationException.class)
                .hasMessageContaining("API_KEY")
                .hasMessageNotContaining(sensitiveValue);
    }

    @Test
    void returnsNullForNullAndLeavesBlankValuesUnchanged() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        assertThat(service.sanitize(null, PiiType.EMAIL, plan)).isNull();
        assertThat(service.sanitize("", PiiType.EMAIL, plan)).isEmpty();
        assertThat(service.sanitize("   ", PiiType.EMAIL, plan)).isEqualTo("   ");
    }

    @Test
    void rejectsNullTypePlanAndStrategy() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        assertThatThrownBy(() -> service.sanitize("value", null, plan))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.sanitize("value", PiiType.EMAIL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.sanitize("value", (TransformationStrategy) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DataSanitizationService(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sanitizesDirectlyWithAnAlreadyResolvedStrategy() {
        assertThat(service.sanitize("alice@example.com", TransformationStrategy.KEEP))
                .isEqualTo("alice@example.com");
        assertThat(service.sanitize("alice@example.com", TransformationStrategy.REDACT))
                .isEqualTo("[REDACTED]");
        assertThat(service.sanitize(null, TransformationStrategy.REDACT)).isNull();
    }

    @Test
    void sameInputAlwaysProducesTheSameOutput() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        for (PiiType piiType : PiiType.values()) {
            String first = service.sanitize("alice@example.com", piiType, plan);
            String second = service.sanitize("alice@example.com", piiType, plan);
            assertThat(second).as("deterministic output for %s", piiType).isEqualTo(first);
        }
    }

    @Test
    void repeatedTransformationIsDeterministicAcrossServiceInstances() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        DataSanitizationService other = new DataSanitizationService(registry());
        assertThat(other.sanitize("alice@example.com", PiiType.EMAIL, plan))
                .isEqualTo(service.sanitize("alice@example.com", PiiType.EMAIL, plan));
        assertThat(other.sanitize("9876543210", PiiType.PHONE, plan))
                .isEqualTo(service.sanitize("9876543210", PiiType.PHONE, plan));
        assertThat(other.sanitize("user-1", PiiType.CUSTOM_IDENTIFIER, plan))
                .isEqualTo(service.sanitize("user-1", PiiType.CUSTOM_IDENTIFIER, plan));
    }

    @Test
    void distinctInputsProduceDistinctRelationshipPreservingOutputs() {
        TransformationPlan plan = DefaultTransformationPolicy.plan();
        assertThat(service.sanitize("user1@example.com", PiiType.EMAIL, plan))
                .isNotEqualTo(service.sanitize("user2@example.com", PiiType.EMAIL, plan));
        assertThat(service.sanitize("user-1", PiiType.CUSTOM_IDENTIFIER, plan))
                .isNotEqualTo(service.sanitize("user-2", PiiType.CUSTOM_IDENTIFIER, plan));
    }

    @Test
    void completeReplacementStrategiesRetainNoPartOfTheOriginalValue() {
        TransformationPlan plan = TransformationPlan.of(
                new TransformationRule(PiiType.API_KEY, TransformationStrategy.REDACT),
                new TransformationRule(PiiType.PASSWORD, TransformationStrategy.HASH_SHA256),
                new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL));
        String apiKey = "sk-proj-abcdef123456";
        assertThat(service.sanitize(apiKey, PiiType.API_KEY, plan)).doesNotContain(apiKey);
        String password = "sup3r-s3cret-pw";
        assertThat(service.sanitize(password, PiiType.PASSWORD, plan)).doesNotContain(password);
        assertThat(service.sanitize("alice@example.com", PiiType.EMAIL, plan))
                .doesNotContain("alice");
    }

    @Test
    void hasNoDetectorCoupling() {
        assertThat(Arrays.stream(DataSanitizationService.class.getDeclaredFields())
                        .noneMatch(field -> field.getType().getSimpleName().contains("Detector")))
                .isTrue();
        assertThat(Arrays.stream(DataSanitizationService.class.getDeclaredMethods())
                        .noneMatch(method -> Arrays.stream(method.getParameterTypes())
                                .anyMatch(type -> type.getSimpleName().contains("Detector"))))
                .isTrue();
    }
}

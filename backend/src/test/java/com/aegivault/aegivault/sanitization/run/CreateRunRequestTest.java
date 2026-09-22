package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.TransformationRule;
import com.aegivault.aegivault.sanitization.TransformationStrategy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Pure unit tests for the future run-creation payload: boundary validation
 * plus exact, fail-closed mapping to {@link TransformationPlan}. No Spring,
 * no database, no HTTP.
 */
class CreateRunRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CreateRunRequest valid() {
        return new CreateRunRequest(
                UUID.randomUUID(),
                "default",
                "v1",
                List.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.SYNTHETIC_EMAIL)));
    }

    @Test
    void validRequestPassesValidationAndMapsExactly() {
        CreateRunRequest request = valid();

        assertThat(VALIDATOR.validate(request)).isEmpty();

        TransformationPlan plan = request.toTransformationPlan();
        assertThat(plan.strategies()).hasSize(1);
        assertThat(plan.strategyFor(PiiType.EMAIL)).contains(TransformationStrategy.SYNTHETIC_EMAIL);
    }

    @Test
    void explicitRulesArePreservedExactly() {
        CreateRunRequest request = new CreateRunRequest(
                UUID.randomUUID(),
                "custom",
                "v3",
                List.of(
                        new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT),
                        new TransformationRule(PiiType.PHONE, TransformationStrategy.MASK)));

        TransformationPlan plan = request.toTransformationPlan();

        assertThat(plan.strategies()).hasSize(2);
        assertThat(plan.strategyFor(PiiType.EMAIL)).contains(TransformationStrategy.REDACT);
        assertThat(plan.strategyFor(PiiType.PHONE)).contains(TransformationStrategy.MASK);
    }

    @Test
    void missingTypesAreNotFilledWithDefaults() {
        TransformationPlan plan = valid().toTransformationPlan();

        assertThat(plan.strategyFor(PiiType.PHONE)).isEmpty();
        assertThat(plan.strategyFor(PiiType.ADDRESS)).isEmpty();
        assertThat(plan.strategies()).hasSize(1);
    }

    @Test
    void missingDatasetIdIsRejected() {
        CreateRunRequest request = new CreateRunRequest(
                null,
                "default",
                "v1",
                List.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT)));

        assertThat(VALIDATOR.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("datasetId");
    }

    @Test
    void blankPolicyFieldsAreRejected() {
        CreateRunRequest blankName = new CreateRunRequest(
                UUID.randomUUID(),
                "   ",
                "v1",
                List.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT)));
        CreateRunRequest blankVersion = new CreateRunRequest(
                UUID.randomUUID(),
                "default",
                "",
                List.of(new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT)));

        assertThat(VALIDATOR.validate(blankName)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("policyName");
        assertThat(VALIDATOR.validate(blankVersion)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("policyVersion");
    }

    @Test
    void emptyRulesAreRejected() {
        CreateRunRequest request =
                new CreateRunRequest(UUID.randomUUID(), "default", "v1", List.of());

        assertThat(VALIDATOR.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("rules");
    }

    @Test
    void nullRulesAreRejected() {
        assertThatThrownBy(() -> new CreateRunRequest(UUID.randomUUID(), "default", "v1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullRuleEntryIsRejected() {
        List<TransformationRule> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> new CreateRunRequest(UUID.randomUUID(), "default", "v1", withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void duplicatePiiTypeIsRejected() {
        CreateRunRequest request = new CreateRunRequest(
                UUID.randomUUID(),
                "default",
                "v1",
                List.of(
                        new TransformationRule(PiiType.EMAIL, TransformationStrategy.REDACT),
                        new TransformationRule(PiiType.EMAIL, TransformationStrategy.MASK)));

        assertThatThrownBy(request::toTransformationPlan).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidPiiTypeIsRejected() {
        String json = "{\"datasetId\":\"" + UUID.randomUUID()
                + "\",\"policyName\":\"default\",\"policyVersion\":\"v1\","
                + "\"rules\":[{\"piiType\":\"NOT_A_TYPE\",\"strategy\":\"REDACT\"}]}";

        assertThatThrownBy(() -> MAPPER.readValue(json, CreateRunRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void invalidStrategyIsRejected() {
        String json = "{\"datasetId\":\"" + UUID.randomUUID()
                + "\",\"policyName\":\"default\",\"policyVersion\":\"v1\","
                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"NOT_A_STRATEGY\"}]}";

        assertThatThrownBy(() -> MAPPER.readValue(json, CreateRunRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void validJsonBindsToRequest() throws Exception {
        UUID datasetId = UUID.randomUUID();
        String json = "{\"datasetId\":\"" + datasetId
                + "\",\"policyName\":\"default\",\"policyVersion\":\"v1\","
                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"SYNTHETIC_EMAIL\"}]}";

        CreateRunRequest request = MAPPER.readValue(json, CreateRunRequest.class);

        assertThat(request.datasetId()).isEqualTo(datasetId);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.toTransformationPlan().strategyFor(PiiType.EMAIL))
                .contains(TransformationStrategy.SYNTHETIC_EMAIL);
    }

    @Test
    void requestAndPlanRetainNoRawData() {
        CreateRunRequest request = valid();

        PolicySnapshot snapshot = PolicySnapshot.fromPlan("default", "v1", request.toTransformationPlan());
        assertThat(snapshot.toJson()).doesNotContain("@", "sk-", "password");
        assertThat(request.toString()).doesNotContain("@");
    }
}

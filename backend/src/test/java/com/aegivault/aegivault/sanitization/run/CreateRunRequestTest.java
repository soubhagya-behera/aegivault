package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure unit tests for the run-creation payload: both ids are required, and
 * nothing else is bound. No Spring, no database, no HTTP.
 */
class CreateRunRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CreateRunRequest valid() {
        return new CreateRunRequest(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void validRequestPassesValidation() {
        CreateRunRequest request = valid();

        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.datasetId()).isNotNull();
        assertThat(request.policyId()).isNotNull();
    }

    @Test
    void missingIdsAreRejected() {
        assertThat(VALIDATOR.validate(new CreateRunRequest(null, UUID.randomUUID())))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("datasetId");
        assertThat(VALIDATOR.validate(new CreateRunRequest(UUID.randomUUID(), null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("policyId");
    }

    @Test
    void validJsonBindsToRequest() throws Exception {
        UUID datasetId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String json = "{\"datasetId\":\"" + datasetId + "\",\"policyId\":\"" + policyId + "\"}";

        CreateRunRequest request = MAPPER.readValue(json, CreateRunRequest.class);

        assertThat(request.datasetId()).isEqualTo(datasetId);
        assertThat(request.policyId()).isEqualTo(policyId);
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void oldInlinePayloadIsNotAccepted() {
        String json = "{\"datasetId\":\"" + UUID.randomUUID()
                + "\",\"policyName\":\"default\",\"policyVersion\":\"v1\","
                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"SYNTHETIC_EMAIL\"}]}";

        // One clear contract: the old inline shape binds to no usable policy
        // reference. Either deserialization rejects the unknown properties, or
        // binding succeeds with no policyId and bean validation rejects it.
        try {
            CreateRunRequest request = MAPPER.readValue(json, CreateRunRequest.class);
            assertThat(VALIDATOR.validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("policyId");
        } catch (JacksonException expected) {
            assertThat(expected.getMessage()).isNotEmpty();
        }
    }

    @Test
    void malformedUuidsAreRejected() {
        String json = "{\"datasetId\":\"not-a-uuid\",\"policyId\":\"" + UUID.randomUUID() + "\"}";

        assertThatThrownBy(() -> MAPPER.readValue(json, CreateRunRequest.class))
                .isInstanceOf(JacksonException.class);
    }
}

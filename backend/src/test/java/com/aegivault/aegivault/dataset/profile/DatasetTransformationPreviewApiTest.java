package com.aegivault.aegivault.dataset.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.dataset.DatabaseDatasetInputSource;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.policy.SanitizationPolicyRepository;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only transformation preview
 * ({@code GET /api/datasets/{id}/profile/transformation-preview}) against
 * real PostgreSQL. The endpoint maps the persisted profile through the
 * existing default policy — it never profiles, sanitizes, persists, or
 * appends anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetTransformationPreviewApiTest {

    private static final String STORED =
            "name,email,phone\nbob,bob@example.com,9876543210\ncarol,carol@example.com,9876543211\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private DatabaseDatasetInputSource inputs;

    @Autowired
    private SanitizationPolicyRepository policies;

    @Autowired
    private SanitizationRunRepository runs;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    @PersistenceContext
    private EntityManager entities;

    private static String email() {
        return "preview-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "preview-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String createDataset(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void upload(String token, String datasetId, byte[] csv) throws Exception {
        mvc.perform(post("/api/datasets/" + datasetId + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isOk());
    }

    private void profile(String token, String datasetId) throws Exception {
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String previewBody(String token, String datasetId) throws Exception {
        return mvc.perform(get("/api/datasets/" + datasetId + "/profile/transformation-preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String storedBytes(String token, String datasetId) throws Exception {
        String subject = jwtDecoder.decode(token).getSubject();
        try (InputStream open = inputs.openInput(subject, UUID.fromString(datasetId))) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long profileColumnRows(String datasetId) {
        return ((Number) entities
                        .createNativeQuery(
                                "SELECT count(*) FROM dataset_profile_columns WHERE dataset_id = '" + datasetId + "'")
                        .getSingleResult())
                .longValue();
    }

    @Test
    void ownerReceivesCorrectPreviewAndCsvIsUnchanged() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);

        MvcResult result = mvc.perform(
                        get("/api/datasets/" + datasetId + "/profile/transformation-preview")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andExpect(jsonPath("$.columns[0].columnName").value("email"))
                .andExpect(jsonPath("$.columns[0].detectedTypes[0].piiType").value("EMAIL"))
                .andExpect(jsonPath("$.columns[0].detectedTypes[0].detectionCount").value(2))
                .andExpect(jsonPath("$.columns[0].detectedTypes[0].detectionRate").value(1.0))
                .andExpect(jsonPath("$.columns[0].detectedTypes[0].suggestedStrategy").value("SYNTHETIC_EMAIL"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bob@example.com", "carol@example.com", "ownerSubject");
        assertThat(storedBytes(token, datasetId)).isEqualTo(STORED);
    }

    @Test
    void everyDetectedTypeMapsToExistingDefaultStrategy() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);

        TransformationPlan defaults = DefaultTransformationPolicy.plan();
        for (PiiType type : PiiType.values()) {
            assertThat(defaults.strategyFor(type))
                    .as("default policy covers %s", type)
                    .isPresent();
        }

        JsonNode preview = objectMapper.readTree(previewBody(token, datasetId));
        int detections = 0;
        for (JsonNode column : preview.get("columns")) {
            assertThat(column.get("columnName").asText()).isNotBlank();
            for (JsonNode detection : column.get("detectedTypes")) {
                PiiType type = PiiType.valueOf(detection.get("piiType").asText());
                assertThat(detection.get("suggestedStrategy").asText())
                        .isEqualTo(defaults.strategyFor(type).orElseThrow().name());
                detections++;
            }
        }
        assertThat(detections).isGreaterThan(0);
    }

    @Test
    void countsAndRatesComeFromPersistedProfile() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);

        JsonNode stored = objectMapper.readTree(mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        JsonNode preview = objectMapper.readTree(previewBody(token, datasetId));

        assertThat(preview.get("datasetId").asText()).isEqualTo(stored.get("datasetId").asText());
        assertThat(preview.get("columns").size()).isEqualTo(stored.get("columns").size());
        for (int index = 0; index < stored.get("columns").size(); index++) {
            JsonNode storedColumn = stored.get("columns").get(index);
            JsonNode previewColumn = preview.get("columns").get(index);
            assertThat(previewColumn.get("columnName").asText())
                    .isEqualTo(storedColumn.get("columnName").asText());
            assertThat(previewColumn.get("detectedTypes").size())
                    .isEqualTo(storedColumn.get("detectedTypes").size());
            for (JsonNode detection : previewColumn.get("detectedTypes")) {
                String type = detection.get("piiType").asText();
                assertThat(detection.get("detectionCount").asInt())
                        .isEqualTo(storedColumn.get("detectionCounts").get(type).asInt());
                assertThat(detection.get("detectionRate").asDouble())
                        .isCloseTo(
                                storedColumn.get("detectionRates").get(type).asDouble(),
                                org.assertj.core.data.Offset.offset(1e-12));
            }
        }
    }

    @Test
    void previewDoesNotTriggerReprofilingOrPersist() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "unprofiled.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));

        mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        String body = mvc.perform(
                        get("/api/datasets/" + datasetId + "/profile/transformation-preview")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Dataset not found.");

        mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(profileColumnRows(datasetId)).isZero();
    }

    @Test
    void foreignAndMissingDatasetsReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String foreignId = createDataset(tokenA, "not-yours.csv");
        upload(tokenA, foreignId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(tokenA, foreignId);
        String missingId = UUID.randomUUID().toString();

        String foreignBody = mvc.perform(
                        get("/api/datasets/" + foreignId + "/profile/transformation-preview")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(
                        get("/api/datasets/" + missingId + "/profile/transformation-preview")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo("Dataset not found.");
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "guarded.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);

        mvc.perform(get("/api/datasets/" + datasetId + "/profile/transformation-preview"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/datasets/" + datasetId + "/profile/transformation-preview")
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(get("/api/datasets/not-a-uuid/profile/transformation-preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Invalid dataset id.");
    }

    @Test
    void previewCreatesNoPolicy() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);
        long before = policies.count();

        previewBody(token, datasetId);

        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void previewCreatesNoRun() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);
        long before = runs.count();

        previewBody(token, datasetId);

        assertThat(runs.count()).isEqualTo(before);
    }

    @Test
    void previewCreatesNoAuditEvent() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);
        long before = ledger.count();

        previewBody(token, datasetId);

        assertThat(ledger.count()).isEqualTo(before);
    }

    @Test
    void responseContainsNoRawValuesPiiOrOwner() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        profile(token, datasetId);

        String body = previewBody(token, datasetId);

        assertThat(body).contains("suggestedStrategy");
        assertThat(body).doesNotContain(
                "bob", "carol", "bob@example.com", "carol@example.com", "9876543210", "9876543211");
        assertThat(body).doesNotContain("ownerSubject", "example.invalid", "policySnapshot", "content");
    }
}

package com.aegivault.aegivault.dataset.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.dataset.DatabaseDatasetInputSource;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit profile trigger ({@code POST /api/datasets/{id}/profile})
 * against real PostgreSQL. The endpoint reads the already-uploaded input
 * server-side, runs the existing CSV profiler, and persists through the
 * existing profile service — upload itself never profiles.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetProfilePostApiTest {

    private static final String STORED = "name,email\nalice,alice@example.com\nbob,bob@example.com\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private DatabaseDatasetInputSource inputs;

    @PersistenceContext
    private EntityManager entities;

    private static String email() {
        return "trigger-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "trigger-pass-1", null));
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

    private String storedBytes(String token, String datasetId) throws Exception {
        String subject = jwtDecoder.decode(token).getSubject();
        try (InputStream open = inputs.openInput(subject, UUID.fromString(datasetId))) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long rowCount(String table, String datasetId) {
        return ((Number) entities
                        .createNativeQuery("SELECT count(*) FROM " + table + " WHERE dataset_id = '" + datasetId + "'")
                        .getSingleResult())
                .longValue();
    }

    @Test
    void ownerProfilesUploadedCsv() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andExpect(jsonPath("$.totalColumns").value(2))
                .andExpect(jsonPath("$.columns[0].columnName").value("email"))
                .andExpect(jsonPath("$.columns[1].columnName").value("name"))
                .andExpect(jsonPath("$.columns[0].detectionCounts.EMAIL").value(2))
                .andExpect(jsonPath("$.columns[0].detectedTypes[0]").value("EMAIL"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("alice@example.com", "bob@example.com");
    }

    @Test
    void requestBodyIsIgnoredAndStoredInputIsProfiled() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));

        // A caller-supplied body must not become the profiling input: the
        // endpoint takes no body and always profiles the stored bytes.
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content("other\n1\n2\n3\n".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andExpect(jsonPath("$.totalColumns").value(2))
                .andExpect(jsonPath("$.columns[0].columnName").value("email"));
    }

    @Test
    void persistedProfileIsImmediatelyRetrievableViaGet() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));

        String posted = mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String fetched = mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(fetched)).isEqualTo(objectMapper.readTree(posted));
    }

    @Test
    void reprofilingReplacesPreviousProfileWithoutStaleRows() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalColumns").value(2));

        upload(token, datasetId, "solo\nhello\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalColumns").value(1))
                .andExpect(jsonPath("$.columns[0].columnName").value("solo"));

        String fetched = mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(fetched).doesNotContain("\"columnName\":\"a\"", "\"columnName\":\"b\"");
        assertThat(rowCount("dataset_profile_columns", datasetId)).isEqualTo(1);
        assertThat(rowCount("dataset_profile_detections", datasetId)).isZero();
    }

    @Test
    void foreignAndMissingDatasetsReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String foreignId = createDataset(tokenA, "not-yours.csv");
        upload(tokenA, foreignId, STORED.getBytes(StandardCharsets.UTF_8));
        String missingId = UUID.randomUUID().toString();

        String foreignBody = mvc.perform(post("/api/datasets/" + foreignId + "/profile")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(post("/api/datasets/" + missingId + "/profile")
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
    void datasetWithoutInputReturnsSafe404AndPersistsNothing() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "no-input.csv");

        String body = mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Dataset not found.");
        mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "guarded.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));

        mvc.perform(post("/api/datasets/" + datasetId + "/profile")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(post("/api/datasets/not-a-uuid/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Invalid dataset id.");
    }

    @Test
    void storedCsvAndDatasetMetadataRemainUnchangedAfterProfiling() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        String metadataBefore = mvc.perform(get("/api/datasets/" + datasetId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(storedBytes(token, datasetId)).isEqualTo(STORED);
        String metadataAfter = mvc.perform(get("/api/datasets/" + datasetId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(metadataAfter)).isEqualTo(objectMapper.readTree(metadataBefore));
    }

    @Test
    void profileResponseCarriesCountsAndRatesButNoRawValues() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].suppliedValueCount").value(2))
                .andExpect(jsonPath("$.columns[0].analyzedValueCount").value(2))
                .andExpect(jsonPath("$.columns[0].analyzableValueCount").value(2))
                .andExpect(jsonPath("$.columns[0].detectionRates.EMAIL").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("detectionCounts", "detectionRates", "EMAIL");
        assertThat(body).doesNotContain(
                "alice", "bob", "alice@example.com", "bob@example.com", "ownerSubject", "content", "sample");
    }

    @Test
    void malformedStoredCsvReturnsSafeErrorAndPersistsNothing() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "ragged.csv");
        upload(token, datasetId, "a,b\n1,2,3\n".getBytes(StandardCharsets.UTF_8));

        String body = mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).contains("CSV row 2");
        assertThat(body).doesNotContain("1,2,3");
        mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(rowCount("dataset_profile_columns", datasetId)).isZero();
    }

    @Test
    void tenMibUploadLimitRemainsEnforcedAndOriginalStillProfiles() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, STORED.getBytes(StandardCharsets.UTF_8));
        byte[] huge = new byte[10 * 1024 * 1024 + 1];

        String rejected = mvc.perform(post("/api/datasets/" + datasetId + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(rejected).get("message").asText()).contains("10485760");
        assertThat(storedBytes(token, datasetId)).isEqualTo(STORED);
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalColumns").value(2))
                .andExpect(jsonPath("$.columns[0].columnName").value("email"));
    }
}

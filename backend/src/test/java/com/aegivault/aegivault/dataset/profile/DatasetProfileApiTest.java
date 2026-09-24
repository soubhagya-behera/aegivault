package com.aegivault.aegivault.dataset.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.pii.EmailDetector;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.profile.ColumnInput;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfiler;
import com.aegivault.aegivault.pii.profile.PiiColumnProfiler;
import java.util.List;
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
 * Owner-scoped profile read API against real PostgreSQL. The profile is
 * persisted through {@link DatasetProfileService} (there is no POST
 * profile endpoint); the GET returns the stored result verbatim and never
 * re-runs profiling.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetProfileApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private DatasetProfileService profiles;

    private static String email() {
        return "profile-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, "profile-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    private String createDataset(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    private void saveProfile(String token, String datasetId) {
        String owner = jwtDecoder.decode(token).getSubject();
        DatasetProfiler profiler = new DatasetProfiler(
                new PiiColumnProfiler(new PiiDetectorRegistry(List.of(new EmailDetector()))));
        DatasetProfile profile = profiler.profile(UUID.fromString(datasetId), List.of(
                new ColumnInput("notes", List.of("hello", "world")),
                new ColumnInput("email",
                        List.of("alice@example.com", "bob@example.com", "not an email"))));
        profiles.saveProfile(owner, UUID.fromString(datasetId), profile);
    }

    @Test
    void ownerCanRetrieveOwnProfileWithCountsAndRates() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "profiled.csv");
        saveProfile(token, datasetId);

        MvcResult result = mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andExpect(jsonPath("$.totalColumns").value(2))
                .andExpect(jsonPath("$.maxSampleSizePerColumn").value(100))
                .andExpect(jsonPath("$.columns[0].columnName").value("email"))
                .andExpect(jsonPath("$.columns[1].columnName").value("notes"))
                .andExpect(jsonPath("$.columns[0].suppliedValueCount").value(3))
                .andExpect(jsonPath("$.columns[0].analyzedValueCount").value(3))
                .andExpect(jsonPath("$.columns[0].analyzableValueCount").value(3))
                .andExpect(jsonPath("$.columns[0].detectionCounts.EMAIL").value(2))
                .andExpect(jsonPath("$.columns[0].detectedTypes[0]").value("EMAIL"))
                .andExpect(jsonPath("$.columns[1].suppliedValueCount").value(2))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("alice@example.com", "bob@example.com", "not an email");
        JsonNode parsed = objectMapper.readTree(body);
        assertThat(parsed.get("columns").get(0).get("detectionRates").get("EMAIL").asDouble())
                .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void foreignDatasetAndMissingDatasetReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String othersId = createDataset(tokenA, "not-yours.csv");
        saveProfile(tokenA, othersId);
        String missingId = UUID.randomUUID().toString();

        String otherBody = mvc.perform(get("/api/datasets/" + othersId + "/profile")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missingBody = mvc.perform(get("/api/datasets/" + missingId + "/profile")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(otherBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(otherBody).get("message").asText())
                .isEqualTo("Dataset not found.");
    }

    @Test
    void datasetWithoutPersistedProfileReturns404() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "unprofiled.csv");

        String body = mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("Dataset not found.");
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "guarded.csv");
        saveProfile(token, datasetId);

        mvc.perform(get("/api/datasets/" + datasetId + "/profile"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(get("/api/datasets/not-a-uuid/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("Invalid dataset id.");
    }
}

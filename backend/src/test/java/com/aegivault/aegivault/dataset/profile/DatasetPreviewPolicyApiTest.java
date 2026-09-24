package com.aegivault.aegivault.dataset.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.dataset.DatabaseDatasetInputSource;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import com.aegivault.aegivault.sanitization.policy.SanitizationPolicyRepository;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
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
 * Policy creation from the transformation preview
 * ({@code POST /api/datasets/{id}/profile/transformation-preview/policy})
 * against real PostgreSQL. Rules come from the persisted profile plus the
 * existing default plan only; persistence reuses the existing policy
 * service, and nothing else — no run, no sanitization, no CSV change — is
 * touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetPreviewPolicyApiTest {

    private static final String STORED =
            "name,email,phone\nbob,bob@example.com,9876543210\ncarol,carol@example.com,9876543211\n";

    private static final String CLEAN = "a,b\n1,2\n3,4\n";

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

    private static String email() {
        return "preview-policy-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "preview-policy-pass-1", null));
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

    private void upload(String token, String datasetId, String csv) throws Exception {
        mvc.perform(post("/api/datasets/" + datasetId + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(csv.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
    }

    private void profile(String token, String datasetId) throws Exception {
        mvc.perform(post("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String profiledDataset(String token, String csv) throws Exception {
        String datasetId = createDataset(token, "customers.csv");
        upload(token, datasetId, csv);
        profile(token, datasetId);
        return datasetId;
    }

    private MvcResult createPolicy(String token, String datasetId, String body) throws Exception {
        return mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String policyRequest(String name, String version, String description) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"description\":\"" + description + "\"}";
    }

    private String storedBytes(String token, String datasetId) throws Exception {
        String subject = jwtDecoder.decode(token).getSubject();
        try (InputStream open = inputs.openInput(subject, UUID.fromString(datasetId))) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String storedProfile(String token, String datasetId) throws Exception {
        return mvc.perform(get("/api/datasets/" + datasetId + "/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Distinct detected types across all preview columns, in first-appearance order. */
    private static TreeSet<String> detectedTypes(JsonNode preview) {
        TreeSet<String> types = new TreeSet<>();
        for (JsonNode column : preview.get("columns")) {
            for (JsonNode detection : column.get("detectedTypes")) {
                types.add(detection.get("piiType").asText());
            }
        }
        return types;
    }

    private String previewBody(String token, String datasetId) throws Exception {
        return mvc.perform(get("/api/datasets/" + datasetId + "/profile/transformation-preview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void ownerCreatesPolicyFromPreviewWithDetectedTypesOnly() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);
        JsonNode preview = objectMapper.readTree(previewBody(token, datasetId));
        TreeSet<String> detected = detectedTypes(preview);
        assertThat(detected).contains("EMAIL");

        MvcResult result = createPolicy(
                token, datasetId, policyRequest("Production Data Safe Copy", "1.0", "Default policy from profile"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        String policyId = created.get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/policies/" + policyId);
        assertThat(created.get("name").asText()).isEqualTo("Production Data Safe Copy");
        assertThat(created.get("version").asText()).isEqualTo("1.0");
        assertThat(created.get("description").asText()).isEqualTo("Default policy from profile");
        assertThat(body).doesNotContain("ownerSubject");

        Map<String, String> rules = new LinkedHashMap<>();
        for (JsonNode rule : created.get("rules")) {
            rules.put(rule.get("piiType").asText(), rule.get("strategy").asText());
        }
        TransformationPlan defaults = DefaultTransformationPolicy.plan();
        assertThat(rules.keySet()).containsExactlyElementsOf(detected);
        for (String type : detected) {
            assertThat(rules.get(type))
                    .isEqualTo(defaults.strategyFor(PiiType.valueOf(type)).orElseThrow().name());
        }
        assertThat(rules.get("EMAIL")).isEqualTo("SYNTHETIC_EMAIL");
        assertThat(body).doesNotContain("bob@example.com", "carol@example.com", "9876543210");
    }

    @Test
    void undetectedTypesAreNeverAdded() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);

        MvcResult result = createPolicy(token, datasetId, policyRequest("scoped", "v1", "scoped policy"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString());
        TreeSet<String> ruleTypes = new TreeSet<>();
        for (JsonNode rule : created.get("rules")) {
            ruleTypes.add(rule.get("piiType").asText());
        }
        assertThat(ruleTypes).doesNotContain("CREDIT_CARD", "JWT", "API_KEY", "PASSWORD", "UUID");
        assertThat(created.get("rules").size()).isLessThanOrEqualTo(PiiType.values().length);
    }

    @Test
    void createdPolicyIsPersistedAndRetrievableViaPoliciesApi() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);

        MvcResult created = createPolicy(token, datasetId, policyRequest("reusable", "v3", "reusable policy"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String policyId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        String fetched = mvc.perform(get("/api/policies/" + policyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(policyId))
                .andExpect(jsonPath("$.name").value("reusable"))
                .andExpect(jsonPath("$.version").value("v3"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(fetched).get("rules"))
                .isEqualTo(objectMapper.readTree(created.getResponse().getContentAsString()).get("rules"));
        assertThat(policies.count()).isGreaterThan(0);
    }

    @Test
    void foreignAndMissingDatasetsReturnSame404AndPersistNothing() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String foreignId = profiledDataset(tokenA, STORED);
        String missingId = UUID.randomUUID().toString();
        long before = policies.count();

        String foreignBody = mvc.perform(post("/api/datasets/" + foreignId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(post("/api/datasets/" + missingId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText()).isEqualTo("Dataset not found.");
        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void unprofiledDatasetReturns404AndPersistsNothing() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "unprofiled.csv");
        upload(token, datasetId, STORED);
        long before = policies.count();

        String body = mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Dataset not found.");
        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void datasetWithoutInputReturns404AndPersistsNothing() throws Exception {
        String token = register(email());
        String datasetId = createDataset(token, "no-input.csv");
        long before = policies.count();

        mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isNotFound());

        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void unauthenticatedRequestReturns401AndPersistsNothing() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);
        long before = policies.count();

        mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isUnauthorized());

        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void malformedUuidReturns400AndPersistsNothing() throws Exception {
        String token = register(email());
        long before = policies.count();

        String body = mvc.perform(post("/api/datasets/not-a-uuid/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("x", "v1", "x")))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Invalid dataset id.");
        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void emptyProfileReturns400AndPersistsNothing() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, CLEAN);
        JsonNode stored = objectMapper.readTree(storedProfile(token, datasetId));
        for (JsonNode column : stored.get("columns")) {
            assertThat(column.get("detectedTypes").size()).isZero();
        }
        long before = policies.count();

        String body = mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("empty", "v1", "must not exist")))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("No detected PII types to build a policy from.");
        assertThat(policies.count()).isEqualTo(before);
        String listing = mvc.perform(get("/api/policies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(listing).size()).isZero();
    }

    @Test
    void invalidLabelsReturn400AndPersistNothing() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);
        long before = policies.count();

        mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("", "v1", "blank name")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest("n".repeat(256), "v1", "overlong name")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/datasets/" + datasetId + "/profile/transformation-preview/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());

        assertThat(policies.count()).isEqualTo(before);
    }

    @Test
    void policyCreationFromPreviewCreatesNoRun() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);
        long before = runs.count();

        MvcResult result =
                createPolicy(token, datasetId, policyRequest("no-run", "v1", "creates no run"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(runs.count()).isEqualTo(before);
    }

    @Test
    void datasetProfileAndCsvAreUnchanged() throws Exception {
        String token = register(email());
        String datasetId = profiledDataset(token, STORED);
        String profileBefore = storedProfile(token, datasetId);

        MvcResult result =
                createPolicy(token, datasetId, policyRequest("unchanged", "v1", "leaves everything else alone"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(storedBytes(token, datasetId)).isEqualTo(STORED);
        assertThat(objectMapper.readTree(storedProfile(token, datasetId)))
                .isEqualTo(objectMapper.readTree(profileBefore));
    }
}

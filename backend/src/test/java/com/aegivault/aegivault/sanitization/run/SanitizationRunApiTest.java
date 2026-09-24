package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.artifact.DatabaseArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * Owner-scoped run read endpoint against real PostgreSQL. Ownership comes
 * from the verified JWT subject; USER and ADMIN behave identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SanitizationRunApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private SanitizationRunExecutor executor;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private DatabaseArtifactStore artifacts;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    private static String email() {
        return "run-" + UUID.randomUUID() + "@example.com";
    }

    private String registerPolicy(String token, String name, String version) throws Exception {
        MvcResult result = mvc.perform(post("/api/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyRequest(name, version)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static String policyRequest(String name, String version) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\","
                + "\"rules\":["
                + "{\"piiType\":\"EMAIL\",\"strategy\":\"SYNTHETIC_EMAIL\"},"
                + "{\"piiType\":\"PHONE\",\"strategy\":\"SYNTHETIC_PHONE\"},"
                + "{\"piiType\":\"PERSON_NAME\",\"strategy\":\"REDACT\"},"
                + "{\"piiType\":\"ADDRESS\",\"strategy\":\"REDACT\"},"
                + "{\"piiType\":\"CREDIT_CARD\",\"strategy\":\"MASK\"},"
                + "{\"piiType\":\"IP_ADDRESS\",\"strategy\":\"HASH_SHA256\"},"
                + "{\"piiType\":\"UUID\",\"strategy\":\"HASH_SHA256\"},"
                + "{\"piiType\":\"API_KEY\",\"strategy\":\"REDACT\"},"
                + "{\"piiType\":\"PASSWORD\",\"strategy\":\"HASH_SHA256\"},"
                + "{\"piiType\":\"JWT\",\"strategy\":\"REDACT\"},"
                + "{\"piiType\":\"CUSTOM_IDENTIFIER\",\"strategy\":\"HASH_SHA256\"}"
                + "]}";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "run-api-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID completedRun(String token) {
        String subject = jwtDecoder.decode(token).getSubject();
        Dataset dataset = datasets.save(new Dataset("customers.csv", subject));
        SanitizationRunView view = executor.executeCsv(
                subject,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream("name,email\nbob,bob@example.com\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        return view.id();
    }

    private UUID failedRun(String token) {
        String subject = jwtDecoder.decode(token).getSubject();
        Dataset dataset = datasets.save(new Dataset("ragged.csv", subject));
        SanitizationRunView view = executor.executeCsv(
                subject,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream("a,b\n1,2,3\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        return view.id();
    }

    @Test
    void ownerRetrievesCompletedRun() throws Exception {
        String token = register(email());
        UUID id = completedRun(token);

        MvcResult result = mvc.perform(get("/api/runs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.inputRowCount").value(1))
                .andExpect(jsonPath("$.outputRowCount").value(1))
                .andExpect(jsonPath("$.columnCount").value(2))
                .andExpect(jsonPath("$.policyName").value("default"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bob@example.com", "password", "Exception", "at com.aegivault");
    }

    @Test
    void ownerRetrievesFailedRun() throws Exception {
        String token = register(email());
        UUID id = failedRun(token);

        mvc.perform(get("/api/runs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("CSV_PARSE_ERROR"))
                .andExpect(jsonPath("$.errorStage").value("TOKENIZE"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }

    @Test
    void foreignAndMissingRunsReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        UUID foreignId = completedRun(tokenA);
        String missingId = UUID.randomUUID().toString();

        String foreignBody = mvc.perform(
                        get("/api/runs/" + foreignId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(
                        get("/api/runs/" + missingId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo("Sanitization run not found.");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        String token = register(email());
        UUID id = completedRun(token);

        mvc.perform(get("/api/runs/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/runs/" + id).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(get("/api/runs/not-a-uuid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Invalid run id.");
    }

    @Test
    void ownerListsOnlyTheirOwnRuns() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        UUID first = completedRun(tokenA);
        UUID second = failedRun(tokenA);
        UUID foreign = completedRun(tokenB);

        String bodyA = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String bodyB = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(bodyA).size()).isEqualTo(2);
        assertThat(bodyA).contains(first.toString(), second.toString());
        assertThat(bodyA).doesNotContain(foreign.toString());
        assertThat(bodyB).doesNotContain(first.toString(), second.toString());
        assertThat(bodyB).contains(foreign.toString());
        assertThat(bodyA).doesNotContain("ownerSubject", "bob@example.com");
    }

    @Test
    void runListingIsNewestFirst() throws Exception {
        String token = register(email());
        UUID first = completedRun(token);
        UUID second = failedRun(token);
        UUID third = completedRun(token);

        MvcResult result = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        var ids = new ArrayList<String>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(node -> ids.add(node.get("id").asText()));
        assertThat(ids).containsExactly(third.toString(), second.toString(), first.toString());
    }

    @Test
    void emptyOwnerReceivesEmptyArray() throws Exception {
        String token = register(email());

        MvcResult result = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void unauthenticatedListingIsRejected() throws Exception {
        mvc.perform(get("/api/runs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/runs").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    private String createDatasetViaApi(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void uploadInput(String token, String datasetId, String csv) throws Exception {
        mvc.perform(post("/api/datasets/" + datasetId + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(csv.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
    }

    private String runRequest(String datasetId, String policyId) {
        return "{\"datasetId\":\"" + datasetId + "\",\"policyId\":\"" + policyId + "\"}";
    }

    private MvcResult postRun(String token, String body) throws Exception {
        return mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String readArtifact(DatabaseArtifactStore artifacts, String token, JwtDecoder decoder, String runId)
            throws Exception {
        String subject = decoder.decode(token).getSubject();
        try (java.io.InputStream open = artifacts.openArtifact(subject, UUID.fromString(runId))) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ownerCreatesSuccessfulRun() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "name,email\nbob,bob@example.com\ncarol,carol@example.com\n");
        String policyId = registerPolicy(token, "default", "v1");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        String runId = objectMapper.readTree(body).get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/runs/" + runId);
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(objectMapper.readTree(body).get("inputRowCount").asLong()).isEqualTo(2L);
        assertThat(objectMapper.readTree(body).get("outputRowCount").asLong()).isEqualTo(2L);
        assertThat(objectMapper.readTree(body).get("columnCount").asInt()).isEqualTo(2);
        assertThat(body).doesNotContain("ownerSubject", "bob@example.com", "carol@example.com");
    }

    @Test
    void runSnapshotFreezesPersistedPolicyNameVersionAndRules() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "name,email\nbob,bob@example.com\n");
        String policyId = registerPolicy(token, "snapshot-check", "v7");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("policyName").asText()).isEqualTo("snapshot-check");
        assertThat(objectMapper.readTree(body).get("policyVersion").asText()).isEqualTo("v7");
        String snapshot = objectMapper.readTree(body).get("policySnapshot").asText();
        assertThat(snapshot).contains("\"policyName\":\"snapshot-check\"");
        assertThat(snapshot).contains("\"policyVersion\":\"v7\"");
        assertThat(snapshot).contains("\"EMAIL\":\"SYNTHETIC_EMAIL\"");
        assertThat(snapshot).contains("\"PHONE\":\"SYNTHETIC_PHONE\"");
        assertThat(snapshot).contains("\"PERSON_NAME\":\"REDACT\"");
        assertThat(body).doesNotContain("ownerSubject", "bob@example.com");
    }

    @Test
    void existingRunSnapshotRemainsUnchangedAfterPolicyUpdate() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "name,email\nbob,bob@example.com\n");
        String policyId = registerPolicy(token, "original", "v1");

        MvcResult first = postRun(token, runRequest(datasetId, policyId));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        String runId = firstBody.get("id").asText();
        String originalSnapshot = firstBody.get("policySnapshot").asText();

        // Replace every label and rule of the policy.
        mvc.perform(put("/api/policies/" + policyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"changed\",\"version\":\"v2\","
                                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}"))
                .andExpect(status().isOk());

        // The earlier run still carries exactly what it froze at creation.
        String body = mvc.perform(get("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(body).get("policyName").asText()).isEqualTo("original");
        assertThat(objectMapper.readTree(body).get("policyVersion").asText()).isEqualTo("v1");
        assertThat(objectMapper.readTree(body).get("policySnapshot").asText())
                .isEqualTo(originalSnapshot);

        // A run created after the update freezes the new policy instead.
        MvcResult second = postRun(token, runRequest(datasetId, policyId));
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("policyVersion").asText()).isEqualTo("v2");
        assertThat(secondBody.get("policySnapshot").asText()).contains("\"EMAIL\":\"REDACT\"");
        assertThat(secondBody.get("policySnapshot").asText()).isNotEqualTo(originalSnapshot);
    }

    @Test
    void existingRunAndArtifactSurvivePolicyDeletion() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "name,email\nbob,bob@example.com\n");
        String policyId = registerPolicy(token, "doomed", "v1");

        MvcResult created = postRun(token, runRequest(datasetId, policyId));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode runBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String runId = runBody.get("id").asText();
        String snapshot = runBody.get("policySnapshot").asText();

        byte[] before = mvc.perform(get("/api/runs/" + runId + "/artifact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        mvc.perform(delete("/api/policies/" + policyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The run is unchanged: same name, version, and snapshot.
        String body = mvc.perform(get("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(body).get("policyName").asText()).isEqualTo("doomed");
        assertThat(objectMapper.readTree(body).get("policyVersion").asText()).isEqualTo("v1");
        assertThat(objectMapper.readTree(body).get("policySnapshot").asText()).isEqualTo(snapshot);

        // The artifact still downloads with identical bytes.
        MvcResult downloaded = mvc.perform(get("/api/runs/" + runId + "/artifact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(downloaded.getResponse().getContentAsByteArray()).isEqualTo(before);

        // The deleted policy can no longer back a new run.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, policyId)))
                .andExpect(status().isNotFound());
    }

    private List<AuditLedgerEntry> ledgerEntriesForRun(UUID runId) {
        return ledger.findAllByOrderBySequenceNumberAsc().stream()
                .filter(entry -> runId.equals(entry.getResourceId()))
                .toList();
    }

    @Test
    void successfulRunRecordsCreatedAndCompletedAuditEvents() throws Exception {
        String token = register(email());
        String subject = jwtDecoder.decode(token).getSubject();
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "name,email\nbob,bob@example.com\n");
        String policyId = registerPolicy(token, "default", "v1");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String runId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("status")
                        .asText())
                .isEqualTo("COMPLETED");

        List<AuditLedgerEntry> entries = ledgerEntriesForRun(UUID.fromString(runId));
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(AuditLedgerEntry::getEventType))
                .containsExactly("SANITIZATION_RUN_CREATED", "SANITIZATION_RUN_COMPLETED");
        for (AuditLedgerEntry entry : entries) {
            assertThat(entry.getActorSubject()).isEqualTo(subject);
            assertThat(entry.getResourceType()).isEqualTo("SANITIZATION_RUN");
            assertThat(entry.getResourceId()).isEqualTo(UUID.fromString(runId));
        }
        assertThat(entries.get(1).getSequenceNumber())
                .isEqualTo(entries.get(0).getSequenceNumber() + 1L);
        assertThat(entries.get(1).getPreviousHash()).isEqualTo(entries.get(0).getEntryHash());

        String created = entries.get(0).getEventData();
        assertThat(created).contains("\"datasetId\":\"" + datasetId + "\"");
        assertThat(created).contains("\"policyName\":\"default\"");
        assertThat(created).contains("\"policyVersion\":\"v1\"");
        String completed = entries.get(1).getEventData();
        assertThat(completed).contains("\"inputRows\":1");
        assertThat(completed).contains("\"outputRows\":1");
        for (AuditLedgerEntry entry : entries) {
            assertThat(entry.getEventData())
                    .doesNotContain("bob@example.com", "password", "sk-", "Bearer", "Exception");
        }
    }

    @Test
    void failedRunRecordsCreatedAndFailedAuditEvents() throws Exception {
        String token = register(email());
        String subject = jwtDecoder.decode(token).getSubject();
        String datasetId = createDatasetViaApi(token, "ragged.csv");
        uploadInput(token, datasetId, "a,b\n1,2,3\n");
        String policyId = registerPolicy(token, "default", "v1");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("FAILED");
        String runId = objectMapper.readTree(body).get("id").asText();

        List<AuditLedgerEntry> entries = ledgerEntriesForRun(UUID.fromString(runId));
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(AuditLedgerEntry::getEventType))
                .containsExactly("SANITIZATION_RUN_CREATED", "SANITIZATION_RUN_FAILED");
        for (AuditLedgerEntry entry : entries) {
            assertThat(entry.getActorSubject()).isEqualTo(subject);
            assertThat(entry.getResourceType()).isEqualTo("SANITIZATION_RUN");
            assertThat(entry.getResourceId()).isEqualTo(UUID.fromString(runId));
        }
        String failed = entries.get(1).getEventData();
        assertThat(failed).contains("\"errorCode\":\"CSV_PARSE_ERROR\"");
        assertThat(failed).contains("\"errorStage\":\"TOKENIZE\"");
        assertThat(failed).doesNotContain("1,2,3", "at com.aegivault", "Exception");
        assertThat(entries.get(1).getPreviousHash()).isEqualTo(entries.get(0).getEntryHash());
    }

    @Test
    void foreignAndMissingPoliciesReturnSame404AndCreateNoRun() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String foreignPolicyId = registerPolicy(tokenA, "not-yours", "v1");
        String datasetId = createDatasetViaApi(tokenB, "mine.csv");
        uploadInput(tokenB, datasetId, "a,b\n1,2\n");

        String foreignBody = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, foreignPolicyId)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo("Dataset not found.");
        MvcResult listing = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listing.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void successfulRunStoresArtifactFromUploadedInput() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "name,email\nbob,bob@example.com\n");
        String policyId = registerPolicy(token, "default", "v1");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        String body = result.getResponse().getContentAsString();
        String runId = objectMapper.readTree(body).get("id").asText();
        String artifact = readArtifact(artifacts, token, jwtDecoder, runId);
        assertThat(artifact).startsWith("name,email\n");
        assertThat(artifact).doesNotContain("bob@example.com");
        assertThat(artifact).contains("example.invalid");
    }

    @Test
    void foreignAndMissingDatasetsReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String foreignId = createDatasetViaApi(tokenA, "not-yours.csv");
        uploadInput(tokenA, foreignId, "a,b\n1,2\n");
        String policyId = registerPolicy(tokenB, "default", "v1");

        String foreignBody = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(foreignId, policyId)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(UUID.randomUUID().toString(), policyId)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo("Dataset not found.");
    }

    @Test
    void datasetWithoutInputReturns404AndCreatesNoRun() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "no-input.csv");
        String policyId = registerPolicy(token, "default", "v1");

        String body = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, policyId)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Dataset not found.");
        MvcResult listing = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listing.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void invalidRequestsAreRejected400() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "customers.csv");
        uploadInput(token, datasetId, "a,b\n1,2\n");
        String policyId = registerPolicy(token, "default", "v1");

        // Missing policyId.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\":\"" + datasetId + "\"}"))
                .andExpect(status().isBadRequest());
        // Missing datasetId.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + policyId + "\"}"))
                .andExpect(status().isBadRequest());
        // Malformed dataset UUID.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest("not-a-uuid", policyId)))
                .andExpect(status().isBadRequest());
        // Malformed policy UUID.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, "not-a-uuid")))
                .andExpect(status().isBadRequest());
        // Old inline-rules shape is no longer a valid contract.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\":\"" + datasetId
                                + "\",\"policyName\":\"default\",\"policyVersion\":\"v1\","
                                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedCreateIsRejected() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "guarded.csv");
        uploadInput(token, datasetId, "a,b\n1,2\n");
        String policyId = registerPolicy(token, "default", "v1");

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, policyId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, policyId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void raggedCsvReturnsPersistedFailedRun() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "ragged.csv");
        uploadInput(token, datasetId, "a,b\n1,2,3\n");
        String policyId = registerPolicy(token, "default", "v1");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("FAILED");
        assertThat(objectMapper.readTree(body).get("errorCode").asText()).isEqualTo("CSV_PARSE_ERROR");
        assertThat(body).doesNotContain("1,2,3");
    }

    @Test
    void policyGapReturnsPersistedFailedRun() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "phones.csv");
        uploadInput(token, datasetId, "phone\n9876543210\n");
        String policyBody = mvc.perform(post("/api/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"email-only\",\"version\":\"v1\","
                                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String minimalPolicyId =
                objectMapper.readTree(policyBody).get("id").asText();

        MvcResult result = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, minimalPolicyId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("FAILED");
        assertThat(objectMapper.readTree(body).get("errorCode").asText()).isEqualTo("POLICY_GAP");
    }

    @Test
    void overflowingOutputReturnsFailedRunWithoutArtifact() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "ips.csv");
        int rows = (int) (DatabaseArtifactStore.MAX_ARTIFACT_BYTES / 65) + 100;
        StringBuilder csv = new StringBuilder("ip\n");
        for (int index = 0; index < rows; index++) {
            csv.append("1.1.1.1\n");
        }
        mvc.perform(post("/api/datasets/" + datasetId + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(csv.toString().getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        String policyId = registerPolicy(token, "default", "v1");

        MvcResult result = postRun(token, runRequest(datasetId, policyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        String runId = objectMapper.readTree(body).get("id").asText();
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("FAILED");
        assertThat(objectMapper.readTree(body).get("errorCode").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        String subject = jwtDecoder.decode(token).getSubject();
        assertThatThrownBy(() -> artifacts.openArtifact(subject, UUID.fromString(runId)))
                .isInstanceOf(SanitizationRunNotFoundException.class);
    }

    @Test
    void crossOwnerCombinationsCreateNoRunAndNoAuditEvent() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String datasetA = createDatasetViaApi(tokenA, "a.csv");
        uploadInput(tokenA, datasetA, "a,b\n1,2\n");
        String policyA = registerPolicy(tokenA, "policy-a", "v1");
        String datasetB = createDatasetViaApi(tokenB, "b.csv");
        uploadInput(tokenB, datasetB, "a,b\n1,2\n");
        String policyB = registerPolicy(tokenB, "policy-b", "v1");
        long auditBefore = ledger.count();

        // Own dataset + foreign policy, both directions.
        MvcResult ownDatasetForeignPolicyA = postRun(tokenA, runRequest(datasetA, policyB));
        MvcResult ownDatasetForeignPolicyB = postRun(tokenB, runRequest(datasetB, policyA));
        // Foreign dataset + own policy, both directions.
        MvcResult foreignDatasetOwnPolicyA = postRun(tokenA, runRequest(datasetB, policyA));
        MvcResult foreignDatasetOwnPolicyB = postRun(tokenB, runRequest(datasetA, policyB));

        String[] bodies = new String[] {
            ownDatasetForeignPolicyA.getResponse().getContentAsString(),
            ownDatasetForeignPolicyB.getResponse().getContentAsString(),
            foreignDatasetOwnPolicyA.getResponse().getContentAsString(),
            foreignDatasetOwnPolicyB.getResponse().getContentAsString()
        };
        for (MvcResult rejected : new MvcResult[] {
            ownDatasetForeignPolicyA,
            ownDatasetForeignPolicyB,
            foreignDatasetOwnPolicyA,
            foreignDatasetOwnPolicyB
        }) {
            assertThat(rejected.getResponse().getStatus()).isEqualTo(404);
        }
        for (String body : bodies) {
            assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Dataset not found.");
        }
        assertThat(bodies[0]).isEqualTo(bodies[1]).isEqualTo(bodies[2]).isEqualTo(bodies[3]);

        // Authorization fails before any run row exists, so neither owner
        // gains a run and the shared ledger gains no entry.
        for (String token : new String[] {tokenA, tokenB}) {
            MvcResult listing = mvc.perform(get("/api/runs").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(listing.getResponse().getContentAsString()).isEqualTo("[]");
        }
        assertThat(ledger.count()).isEqualTo(auditBefore);
    }

    @Test
    void executedOutputFollowsPersistedPolicyNotDefaultPolicy() throws Exception {
        String token = register(email());
        String datasetId = createDatasetViaApi(token, "emails.csv");
        uploadInput(token, datasetId, "email\nalice@example.com\n");
        String policyBody = mvc.perform(post("/api/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"redact-emails\",\"version\":\"v9\","
                                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String minimalPolicyId = objectMapper.readTree(policyBody).get("id").asText();

        MvcResult result = postRun(token, runRequest(datasetId, minimalPolicyId));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(objectMapper.readTree(body).get("policyName").asText()).isEqualTo("redact-emails");
        assertThat(objectMapper.readTree(body).get("policyVersion").asText()).isEqualTo("v9");
        assertThat(objectMapper.readTree(body).get("policySnapshot").asText()).contains("\"EMAIL\":\"REDACT\"");

        // The default policy would have produced a synthetic address; the
        // persisted REDACT rule must win instead.
        String runId = objectMapper.readTree(body).get("id").asText();
        String artifact = mvc.perform(get("/api/runs/" + runId + "/artifact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(artifact).contains("[REDACTED]");
        assertThat(artifact).doesNotContain("alice@example.com", "example.invalid");
    }
}

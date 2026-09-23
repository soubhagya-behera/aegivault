package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.artifact.DatabaseArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Owner-scoped sanitized-artifact download against real PostgreSQL:
 * {@code GET /api/runs/{runId}/artifact} returns the exact stored bytes of a
 * completed run, with a fixed media type and a run-id-only attachment
 * filename, and answers every non-downloadable case (foreign run, missing
 * run, unfinished run, missing artifact) with the same safe 404. Ownership
 * comes from the verified JWT subject; USER and ADMIN behave identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SanitizationArtifactDownloadApiTest {

    private static final String CSV = "name,email\nbob,bob@example.com\ncarol,carol@example.com\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private SanitizationRunService runService;

    @Autowired
    private SanitizationRunExecutor executor;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private DatabaseArtifactStore artifacts;

    private static String email() {
        return "artifact-download-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, "artifact-download-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String subject(String token) {
        return jwtDecoder.decode(token).getSubject();
    }

    private String createDataset(String token, String name, String originalFilename) throws Exception {
        String body = originalFilename == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"originalFilename\":\"" + originalFilename + "\"}";
        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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

    private String runRequest(String datasetId, String policyName, String policyVersion) {
        return "{\"datasetId\":\"" + datasetId + "\",\"policyName\":\"" + policyName
                + "\",\"policyVersion\":\"" + policyVersion + "\","
                + "\"rules\":[{\"piiType\":\"PERSON_NAME\",\"strategy\":\"REDACT\"},"
                + "{\"piiType\":\"EMAIL\",\"strategy\":\"SYNTHETIC_EMAIL\"}]}";
    }

    /** Uploads input, runs it through {@code POST /api/runs}, returns the completed run id. */
    private String completedRunId(String token, String csv, String datasetName) throws Exception {
        String datasetId = createDataset(token, datasetName, null);
        uploadInput(token, datasetId, csv);
        MvcResult result = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, "default", "v1")))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("status").asText()).isEqualTo("COMPLETED");
        return objectMapper.readTree(body).get("id").asText();
    }

    private MvcResult download(String token, String runId) throws Exception {
        return mvc.perform(get("/api/runs/" + runId + "/artifact")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private byte[] storedArtifact(String token, String runId) throws Exception {
        try (InputStream open = artifacts.openArtifact(subject(token), UUID.fromString(runId))) {
            return open.readAllBytes();
        }
    }

    @Test
    void ownerDownloadsExactStoredArtifactWithSafeHeaders() throws Exception {
        String token = register(email());
        String runId = completedRunId(token, CSV, "customers.csv");

        MvcResult result = download(token, runId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"sanitized-" + runId + ".csv\"");

        byte[] downloaded = result.getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(storedArtifact(token, runId));

        String text = new String(downloaded, StandardCharsets.UTF_8);
        assertThat(text).startsWith("name,email\n");
        assertThat(text).doesNotContain("bob@example.com", "carol@example.com");
    }

    @Test
    void downloadStreamsEveryByteOfALargerArtifact() throws Exception {
        String token = register(email());
        StringBuilder csv = new StringBuilder("name,email\n");
        for (int index = 0; index < 500; index++) {
            csv.append("person-").append(index).append(",user-").append(index).append("@example.com\n");
        }
        String runId = completedRunId(token, csv.toString(), "large.csv");

        MvcResult result = download(token, runId);

        byte[] expected = storedArtifact(token, runId);
        assertThat(expected.length).isGreaterThan(16 * 1024);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(expected);
    }

    @Test
    void repeatedDownloadsReturnTheSameBytes() throws Exception {
        String token = register(email());
        String runId = completedRunId(token, CSV, "customers.csv");

        assertThat(download(token, runId).getResponse().getContentAsByteArray())
                .isEqualTo(download(token, runId).getResponse().getContentAsByteArray());
    }

    @Test
    void foreignRunReturnsTheSame404AsAMissingRun() throws Exception {
        String owner = register(email());
        String stranger = register(email());
        String runId = completedRunId(owner, CSV, "customers.csv");

        MvcResult foreign = download(stranger, runId);
        MvcResult missing = download(stranger, UUID.randomUUID().toString());

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(foreign.getResponse().getContentAsString())
                .isEqualTo("{\"message\":\"Sanitization run not found.\"}");
        assertThat(foreign.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
        assertThat(foreign.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void completedRunWithoutArtifactReturnsSafe404() throws Exception {
        String token = register(email());
        String owner = subject(token);
        Dataset dataset = datasets.save(new Dataset("customers.csv", owner));
        SanitizationRunView view = executor.executeCsv(
                owner,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());

        assertThat(view.status()).isEqualTo(RunStatus.COMPLETED);
        MvcResult result = download(token, view.id().toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"message\":\"Sanitization run not found.\"}");
    }

    @Test
    void unfinishedRunWithAStoredArtifactReturnsSafe404UntilCompleted() throws Exception {
        String token = register(email());
        String owner = subject(token);
        String datasetId = createDataset(token, "customers.csv", null);
        uploadInput(token, datasetId, CSV);
        SanitizationRunView created = runService.createRun(
                owner,
                UUID.fromString(datasetId),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1");
        SanitizationRunView running = runService.startRun(owner, created.id());
        byte[] planted = "name,email\nredacted,synthetic@example.invalid\n".getBytes(StandardCharsets.UTF_8);
        artifacts.storeArtifact(owner, created.id(), new ByteArrayInputStream(planted));

        MvcResult refused = download(token, created.id().toString());

        assertThat(running.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(refused.getResponse().getContentAsString())
                .isEqualTo("{\"message\":\"Sanitization run not found.\"}");

        runService.completeRun(owner, created.id(), new RunResult(1L, 1L, 0L, 2));

        MvcResult served = download(token, created.id().toString());

        assertThat(served.getResponse().getStatus()).isEqualTo(200);
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(planted);
    }

    @Test
    void unauthenticatedDownloadIsRejected() throws Exception {
        String owner = register(email());
        String runId = completedRunId(owner, CSV, "customers.csv");

        mvc.perform(get("/api/runs/" + runId + "/artifact"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/runs/" + runId + "/artifact").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedRunUuidReturns400() throws Exception {
        String owner = register(email());

        MvcResult result = mvc.perform(get("/api/runs/not-a-uuid/artifact")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"message\":\"Invalid run id.\"}");
    }

    @Test
    void downloadExposesNoOwnerSubjectOrInternalMetadata() throws Exception {
        String owner = register(email());
        String runId = completedRunId(owner, CSV, "customers.csv");

        MvcResult result = download(owner, runId);
        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(result.getResponse().getContentAsString()).doesNotContain("ownerSubject", subject(owner));
        assertThat(result.getResponse().getHeaderNames())
                .doesNotContain("ownerSubject", "X-Run-Status", "X-Policy-Name");
        assertThat(disposition).doesNotContain(subject(owner), owner);
    }

    @Test
    void filenameIgnoresPolicyDatasetAndOriginalFileNames() throws Exception {
        String owner = register(email());
        String datasetId = createDataset(owner, "leaky-dataset-name.csv", "leaky-original-name.csv");
        uploadInput(owner, datasetId, CSV);
        MvcResult created = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest(datasetId, "leaky-policy-name", "leaky-policy-version")))
                .andExpect(status().isCreated())
                .andReturn();
        String runId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText();

        String disposition = download(owner, runId).getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(disposition).isEqualTo("attachment; filename=\"sanitized-" + runId + ".csv\"");
        assertThat(disposition)
                .doesNotContain("leaky-dataset-name", "leaky-original-name", "leaky-policy-name",
                        "leaky-policy-version");
    }
}

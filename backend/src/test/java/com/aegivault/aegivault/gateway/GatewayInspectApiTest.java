package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.profile.StoredDatasetProfileRepository;
import com.aegivault.aegivault.sanitization.policy.SanitizationPolicyRepository;
import com.aegivault.aegivault.sanitization.run.SanitizationRunRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway inspection HTTP endpoint against real PostgreSQL. The endpoint is
 * a thin authenticated shell over {@link SecurityInspectionService}: the
 * actor comes from the JWT only, the policy is always strict, a BLOCK is
 * data (200), and inspection logs no request content. Each successfully
 * inspected request appends exactly one metadata-only entry to the audit
 * ledger (see {@code GatewayInspectAuditTest}); nothing else is persisted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayInspectApiTest {

    // Synthetic fixtures only: fragments are joined at runtime so no
    // complete credential-like literal ever appears in this source file.
    private static final String EMAIL = "alice@example.com";

    private static final String SYNTHETIC_KEY = "s" + "k-" + "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SanitizationPolicyRepository policies;

    @Autowired
    private SanitizationRunRepository runs;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private StoredDatasetProfileRepository profiles;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    private static String email() {
        return "gateway-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MvcResult inspect(String token, String body) throws Exception {
        var request = post("/api/gateway/inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn();
    }

    private static String inspectBody(String model, String content) {
        return "{\"model\":\"" + model + "\",\"content\":\"" + content + "\"}";
    }

    @Test
    void cleanRequestReturnsAllow() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"))
                .andExpect(jsonPath("$.reasons").isEmpty())
                .andExpect(jsonPath("$.detectedPiiTypes").isEmpty())
                .andExpect(jsonPath("$.actorSubject").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void piiRequestReturnsBlockAsData() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", "contact " + EMAIL + " for access.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.reasons[0]").value("PII_DETECTED"))
                .andExpect(jsonPath("$.detectedPiiTypes[0]").value("EMAIL"));
    }

    @Test
    void secretRequestReturnsBlockAsData() throws Exception {
        String token = register(email());

        // The key token also matches the PII registry's API-key shape, so a
        // secret finding honestly carries both reason codes.
        MvcResult result = mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", "use key " + SYNTHETIC_KEY + " for deploy.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andReturn();

        // GatewayInspectResponse-equivalent sets have no ordering promise in
        // JSON; assert membership rather than indices.
        Set<String> secretReasons = new HashSet<>();
        objectMapper.readTree(result.getResponse().getContentAsString()).get("reasons").forEach(node -> secretReasons.add(node.asText()));
        assertThat(secretReasons).containsExactlyInAnyOrder("PII_DETECTED", "SECRET_DETECTED");

        assertThat(result.getResponse().getContentAsString()).doesNotContain(SYNTHETIC_KEY);
    }

    @Test
    void combinedFindingsReturnDeterministicReasons() throws Exception {
        String token = register(email());
        String body = inspectBody("local-test-model", "contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".");

        String first = inspect(token, body).getResponse().getContentAsString();
        String second = inspect(token, body).getResponse().getContentAsString();

        assertThat(objectMapper.readTree(first)).isEqualTo(objectMapper.readTree(second));
        Set<String> bothReasons = new HashSet<>();
        objectMapper.readTree(first).get("reasons").forEach(node -> bothReasons.add(node.asText()));
        assertThat(bothReasons).containsExactlyInAnyOrder("PII_DETECTED", "SECRET_DETECTED");
        Set<String> bothTypes = new HashSet<>();
        objectMapper.readTree(first).get("detectedPiiTypes").forEach(node -> bothTypes.add(node.asText()));
        assertThat(bothTypes).containsExactlyInAnyOrder("API_KEY", "EMAIL");
    }

    @Test
    void actorSubjectAndRequestIdCannotBeSupplied() throws Exception {
        String token = register(email());

        MvcResult result = mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"" + CLEAN + "\","
                                + "\"actorSubject\":\"hacker\",\"requestId\":\"" + UUID.randomUUID() + "\","
                                + "\"blockOnPii\":false,\"blockOnSecrets\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("hacker", "actorSubject", "requestId", CLEAN);
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(post("/api/gateway/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", CLEAN)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", CLEAN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        String token = register(email());

        // Missing model.
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isBadRequest());
        // Blank model.
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("  ", CLEAN)))
                .andExpect(status().isBadRequest());
        // Overlong model.
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("m".repeat(256), CLEAN)))
                .andExpect(status().isBadRequest());
        // Missing content.
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\"}"))
                .andExpect(status().isBadRequest());
        // Blank content is defined ALLOW, consistent with the domain request.
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", "   ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"));
    }

    @Test
    void responseContainsNoRawContentSecretsOrActor() throws Exception {
        String token = register(email());
        String content = "contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".";

        String response = inspect(token, inspectBody("local-test-model", content))
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("BLOCK", "PII_DETECTED", "SECRET_DETECTED", "EMAIL", "API_KEY");
        assertThat(response)
                .doesNotContain(content, EMAIL, SYNTHETIC_KEY, "actorSubject", "requestId", "local-test-model");
    }

    @Test
    void repeatedIdenticalRequestGivesEquivalentDecision() throws Exception {
        String token = register(email());
        String body = inspectBody("local-test-model", "contact " + EMAIL + " for access.");

        String first = inspect(token, body).getResponse().getContentAsString();
        String second = inspect(token, body).getResponse().getContentAsString();

        assertThat(objectMapper.readTree(first)).isEqualTo(objectMapper.readTree(second));
    }

    @Test
    void inspectionPersistsNothingExceptAuditEvents() throws Exception {
        String token = register(email());
        long policiesBefore = policies.count();
        long runsBefore = runs.count();
        long datasetsBefore = datasets.count();
        long profilesBefore = profiles.count();
        long ledgerBefore = ledger.count();

        inspect(token, inspectBody("local-test-model", CLEAN));
        inspect(token, inspectBody("local-test-model", "Contact " + EMAIL + " for access."));
        inspect(token, inspectBody("local-test-model", "Use key " + SYNTHETIC_KEY + " for deploy."));

        assertThat(policies.count()).isEqualTo(policiesBefore);
        assertThat(runs.count()).isEqualTo(runsBefore);
        assertThat(datasets.count()).isEqualTo(datasetsBefore);
        assertThat(profiles.count()).isEqualTo(profilesBefore);
        // Exactly one metadata-only audit entry per inspected request.
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 3);
    }

    @Test
    void contentExactlyAtMaximumIsAccepted() throws Exception {
        String token = register(email());
        String content = "a".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH);

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"));
    }

    @Test
    void contentOneAboveMaximumIsRejected() throws Exception {
        String token = register(email());
        String content = "a".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH + 1);

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedPiiContentIsRejectedBeforeInspection() throws Exception {
        String token = register(email());
        // Would be BLOCK if inspected; 400 proves Bean Validation
        // rejected it before SecurityInspectionService ran.
        String content = "contact " + EMAIL + " " + "x".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH);

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedContentDoesNotLeakSubmittedContent() throws Exception {
        String token = register(email());
        String content = "contact " + EMAIL + " " + "x".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH);

        MvcResult result = mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isBadRequest())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain(EMAIL, content.substring(0, 1000));
    }

    @Test
    void oversizedContentCreatesNoRowsAnywhere() throws Exception {
        String token = register(email());
        long policiesBefore = policies.count();
        long runsBefore = runs.count();
        long datasetsBefore = datasets.count();
        long profilesBefore = profiles.count();
        long ledgerBefore = ledger.count();

        String content = "b".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH + 1);
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isBadRequest());

        assertThat(policies.count()).isEqualTo(policiesBefore);
        assertThat(runs.count()).isEqualTo(runsBefore);
        assertThat(datasets.count()).isEqualTo(datasetsBefore);
        assertThat(profiles.count()).isEqualTo(profilesBefore);
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }
}

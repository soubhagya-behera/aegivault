package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.identity.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Gateway completion HTTP endpoint against real PostgreSQL, with a
 * recording fake standing in for the provider seam: ALLOW forwards
 * exactly once, BLOCK never reaches the provider, and failures stay
 * generic. Actor and audit assertions reuse the inspect-endpoint
 * conventions.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayCompleteApiTest {

    // Synthetic fixtures only: fragments are joined at runtime so no
    // complete credential-like literal ever appears in this source file.
    private static final String EMAIL = "alice@example.com";

    private static final String SYNTHETIC_KEY = "s" + "k-" + "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    /** Hand-rolled test double for the LlmProvider seam (no Mockito bean support needed). */
    static class RecordingFake implements LlmProvider {

        private final List<LlmRequest> calls = Collections.synchronizedList(new ArrayList<>());

        private volatile RuntimeException failure;

        private volatile String nextContent;

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls.add(request);
            if (failure != null) {
                throw failure;
            }
            if (nextContent != null) {
                return new LlmResponse(request.model(), nextContent);
            }
            return new LlmResponse(request.model(), "fake-completion for " + request.model());
        }

        void fail(RuntimeException failure) {
            this.failure = failure;
        }

        void respondNext(String content) {
            this.nextContent = content;
        }

        List<LlmRequest> calls() {
            return List.copyOf(calls);
        }

        void reset() {
            calls.clear();
            failure = null;
            nextContent = null;
        }
    }

    @TestConfiguration
    static class FakeProviderConfig {

        @Bean
        @Primary
        RecordingFake fakeLlmProvider() {
            return new RecordingFake();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingFake providers;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    @Autowired
    private UserRepository users;

    @BeforeEach
    void resetFake() {
        providers.reset();
    }

    private static String email() {
        return "gateway-complete-" + UUID.randomUUID() + "@example.com";
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

    private static String completeBody(String model, String content) {
        return "{\"model\":\"" + model + "\",\"content\":\"" + content + "\"}";
    }

    private String actorFor(String email) {
        return users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElseThrow().getId().toString();
    }

    @Test
    void cleanRequestForwardsOnceAndReturnsProviderPayload() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        long ledgerBefore = ledger.count();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"))
                .andExpect(jsonPath("$.provider.model").value("local-test-model"))
                .andExpect(jsonPath("$.provider.content").value("fake-completion for local-test-model"))
                .andExpect(jsonPath("$.reasons").isEmpty())
                .andExpect(jsonPath("$.detectedPiiTypes").isEmpty())
                .andExpect(jsonPath("$.actorSubject").doesNotExist())
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andReturn();

        assertThat(providers.calls()).containsExactly(new LlmRequest("local-test-model", CLEAN));
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("entryHash", "previousHash", "sequenceNumber", "eventData");
        assertThat(actorFor(userEmail)).isNotBlank();
    }

    @Test
    void piiRequestBlocksWithoutCallingProvider() throws Exception {
        String token = register(email());
        long ledgerBefore = ledger.count();

        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", "contact " + EMAIL + " for access.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.reasons[0]").value("PII_DETECTED"))
                .andExpect(jsonPath("$.detectedPiiTypes[0]").value("EMAIL"))
                .andExpect(jsonPath("$.provider").doesNotExist());

        assertThat(providers.calls()).isEmpty();
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
    }

    @Test
    void secretRequestBlocksWithoutCallingProvider() throws Exception {
        String token = register(email());
        String content = "use key " + SYNTHETIC_KEY + " for deploy.";
        long ledgerBefore = ledger.count();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andReturn();

        assertThat(providers.calls()).isEmpty();
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SYNTHETIC_KEY, EMAIL);
    }

    @Test
    void combinedFindingsBlockWithoutCallingProvider() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", "contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.reasons[0]").value("PII_DETECTED"))
                .andExpect(jsonPath("$.reasons[1]").value("SECRET_DETECTED"))
                .andExpect(jsonPath("$.provider").doesNotExist());

        assertThat(providers.calls()).isEmpty();
    }

    @Test
    void unauthenticatedRequestIsRejectedWithoutCallingProvider() throws Exception {
        mvc.perform(post("/api/gateway/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isUnauthorized());

        assertThat(providers.calls()).isEmpty();
    }

    @Test
    void oversizedContentIsRejectedWithoutCallingProvider() throws Exception {
        String token = register(email());
        long ledgerBefore = ledger.count();

        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody(
                                "local-test-model", "b".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH + 1))))
                .andExpect(status().isBadRequest());

        assertThat(providers.calls()).isEmpty();
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }

    @Test
    void invalidRequestsAreRejectedWithoutCallingProvider() throws Exception {
        String token = register(email());
        long ledgerBefore = ledger.count();

        // Missing model.
        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isBadRequest());
        // Blank model.
        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("  ", CLEAN)))
                .andExpect(status().isBadRequest());
        // Missing content.
        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\"}"))
                .andExpect(status().isBadRequest());

        assertThat(providers.calls()).isEmpty();
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }

    @Test
    void providerResponseWithPiiIsBlockedWithoutReturningContent() throws Exception {
        String token = register(email());
        providers.respondNext("contact " + EMAIL + " for access.");
        long ledgerBefore = ledger.count();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.reasons[0]").value("PII_DETECTED"))
                .andExpect(jsonPath("$.detectedPiiTypes[0]").value("EMAIL"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andReturn();

        assertThat(providers.calls()).containsExactly(new LlmRequest("local-test-model", CLEAN));
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain(EMAIL);
        assertThat(response).doesNotContain("fake-completion");
    }

    @Test
    void providerResponseWithSecretIsBlockedWithoutReturningContent() throws Exception {
        String token = register(email());
        providers.respondNext("use key " + SYNTHETIC_KEY + " for deploy.");
        long ledgerBefore = ledger.count();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andReturn();

        assertThat(providers.calls()).containsExactly(new LlmRequest("local-test-model", CLEAN));
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
        String secretResponse = result.getResponse().getContentAsString();
        assertThat(secretResponse).contains("SECRET_DETECTED");
        assertThat(secretResponse).doesNotContain(SYNTHETIC_KEY, EMAIL);
    }

    @Test
    void providerResponseWithBothBlocksWithDeterministicReasons() throws Exception {
        String token = register(email());
        providers.respondNext("contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".");

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"))
                .andExpect(jsonPath("$.reasons[0]").value("PII_DETECTED"))
                .andExpect(jsonPath("$.reasons[1]").value("SECRET_DETECTED"))
                .andExpect(jsonPath("$.detectedPiiTypes[0]").value("EMAIL"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andReturn();

        assertThat(providers.calls()).containsExactly(new LlmRequest("local-test-model", CLEAN));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SYNTHETIC_KEY, EMAIL);
    }

    @Test
    void cleanProviderResponseReturnsContentUnchanged() throws Exception {
        String token = register(email());
        providers.respondNext("quarterly revenue grew steadily with no sensitive data.");

        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"))
                .andExpect(jsonPath("$.provider.model").value("local-test-model"))
                .andExpect(jsonPath("$.provider.content").value("quarterly revenue grew steadily with no sensitive data."))
                .andExpect(jsonPath("$.reasons").isEmpty())
                .andExpect(jsonPath("$.detectedPiiTypes").isEmpty());

        assertThat(providers.calls()).containsExactly(new LlmRequest("local-test-model", CLEAN));
    }

    @Test
    void providerFailureReturnsSafe500WithSingleAuditEntry() throws Exception {
        String token = register(email());
        providers.fail(new RuntimeException("simulated-provider-boom-9z"));
        long ledgerBefore = ledger.count();

        MvcResult result = mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("local-test-model", CLEAN)))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(response).get("message").asText())
                .isEqualTo("Unable to complete gateway request.");
        assertThat(response).doesNotContain("simulated-provider-boom-9z", CLEAN, "actorSubject", "Exception");
        assertThat(providers.calls()).containsExactly(new LlmRequest("local-test-model", CLEAN));
        // The inspection audit already recorded stays single — never duplicated.
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
    }
}

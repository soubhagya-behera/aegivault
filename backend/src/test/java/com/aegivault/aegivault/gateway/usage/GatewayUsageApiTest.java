package com.aegivault.aegivault.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.identity.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Self-service gateway usage HTTP endpoint against real PostgreSQL.
 * The actor always comes from the verified JWT subject: the endpoint
 * exposes no actor parameter of any kind, returns at most the 100
 * newest records plus the database-side aggregate, and carries usage
 * metadata only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayUsageApiTest {

    private static final String MODEL = "usage-test-model";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayUsageRepository usage;

    @Autowired
    private UserRepository users;

    private static String email() {
        return "gateway-usage-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "usage-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String actorFor(String email) {
        return users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElseThrow().getId().toString();
    }

    private GatewayUsageRecord store(
            String actor, Long prompt, Long completion, Long total, GatewayUsageOutcome outcome) {
        GatewayUsageRecord saved = usage.saveAndFlush(
                new GatewayUsageRecord(UUID.randomUUID(), actor, MODEL, prompt, completion, total, outcome));
        pause();
        return saved;
    }

    private static void pause() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult getUsage(String token) throws Exception {
        var request = get("/api/gateway/usage");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    @Test
    void emptyHistoryReturnsEmptyListAndZeroAggregate() throws Exception {
        String token = register(email());

        mvc.perform(get("/api/gateway/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history").isEmpty())
                .andExpect(jsonPath("$.aggregate.recordCount").value(0))
                .andExpect(jsonPath("$.aggregate.promptTokens").doesNotExist())
                .andExpect(jsonPath("$.aggregate.completionTokens").doesNotExist())
                .andExpect(jsonPath("$.aggregate.totalTokens").doesNotExist());
    }

    @Test
    void authenticatedUserReceivesOnlyTheirOwnUsage() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        String secondToken = register(secondEmail);
        String firstActor = actorFor(firstEmail);
        String secondActor = actorFor(secondEmail);

        GatewayUsageRecord firstRecord = store(firstActor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED);
        store(secondActor, 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED);

        MvcResult result = getUsage(firstToken);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.get("history")).hasSize(1);
        assertThat(body.get("history").get(0).get("requestId").asText())
                .isEqualTo(firstRecord.getRequestId().toString());
        assertThat(body.get("aggregate").get("recordCount").asLong()).isEqualTo(1L);
        assertThat(body.get("aggregate").get("promptTokens").asLong()).isEqualTo(10L);
        assertThat(body.get("aggregate").get("completionTokens").asLong()).isEqualTo(20L);
        assertThat(body.get("aggregate").get("totalTokens").asLong()).isEqualTo(30L);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secondActor);
    }

    @Test
    void secondUserCannotSeeFirstUsersRecords() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        String secondToken = register(secondEmail);
        String firstActor = actorFor(firstEmail);
        String secondActor = actorFor(secondEmail);

        GatewayUsageRecord firstRecord = store(firstActor, 1L, 2L, 3L, GatewayUsageOutcome.DELIVERED);
        GatewayUsageRecord secondRecord = store(secondActor, 4L, 5L, 6L, GatewayUsageOutcome.DELIVERED);

        JsonNode secondBody =
                objectMapper.readTree(getUsage(secondToken).getResponse().getContentAsString());
        assertThat(secondBody.get("history")).hasSize(1);
        assertThat(secondBody.get("history").get(0).get("requestId").asText())
                .isEqualTo(secondRecord.getRequestId().toString());
        assertThat(secondBody.get("history").get(0).get("requestId").asText())
                .isNotEqualTo(firstRecord.getRequestId().toString());

        JsonNode firstBody =
                objectMapper.readTree(getUsage(firstToken).getResponse().getContentAsString());
        assertThat(firstBody.get("history")).hasSize(1);
        assertThat(firstBody.get("history").get(0).get("requestId").asText())
                .isEqualTo(firstRecord.getRequestId().toString());
    }

    @Test
    void actorSubjectQueryParameterIsIgnored() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        register(secondEmail);
        String firstActor = actorFor(firstEmail);
        String secondActor = actorFor(secondEmail);

        GatewayUsageRecord own = store(firstActor, 7L, 8L, 15L, GatewayUsageOutcome.DELIVERED);
        store(secondActor, 70L, 80L, 150L, GatewayUsageOutcome.DELIVERED);

        // Unknown query parameters are ignored: the caller still sees
        // only their own JWT-scoped usage, never the named actor's rows.
        MvcResult result = mvc.perform(get("/api/gateway/usage")
                        .header("Authorization", "Bearer " + firstToken)
                        .queryParam("actorSubject", secondActor))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("history")).hasSize(1);
        assertThat(body.get("history").get(0).get("requestId").asText())
                .isEqualTo(own.getRequestId().toString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secondActor);
    }

    @Test
    void actorSubjectPathIsNotUsed() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 1L, 1L, 2L, GatewayUsageOutcome.DELIVERED);

        // No usage sub-path exists: a caller-supplied actor in the path
        // cannot reach another actor's usage.
        mvc.perform(get("/api/gateway/usage/" + actor).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(get("/api/gateway/usage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gateway/usage").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void historyIsLimitedTo100NewestRecords() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);

        List<GatewayUsageRecord> stored = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            stored.add(store(actor, 1L, 1L, 2L, GatewayUsageOutcome.DELIVERED));
        }

        MvcResult result = getUsage(token);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.get("history")).hasSize(100);
        // The aggregate still covers every persisted row.
        assertThat(body.get("aggregate").get("recordCount").asLong()).isEqualTo(105L);
        assertThat(body.get("aggregate").get("promptTokens").asLong()).isEqualTo(105L);

        List<String> returnedIds = new ArrayList<>();
        body.get("history").forEach(node -> returnedIds.add(node.get("requestId").asText()));
        List<String> newestHundred = new ArrayList<>(stored.subList(5, 105).stream()
                .map(record -> record.getRequestId().toString())
                .toList());
        java.util.Collections.reverse(newestHundred);
        assertThat(returnedIds).containsExactlyElementsOf(newestHundred);
    }

    @Test
    void historyIsOrderedNewestFirst() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);

        GatewayUsageRecord first = store(actor, 1L, 1L, 2L, GatewayUsageOutcome.DELIVERED);
        GatewayUsageRecord second = store(actor, 3L, 4L, 7L, GatewayUsageOutcome.DELIVERED);
        GatewayUsageRecord third = store(actor, 5L, 6L, 11L, GatewayUsageOutcome.SECURITY_BLOCKED);

        MvcResult result = getUsage(token);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        List<String> returnedIds = new ArrayList<>();
        body.get("history").forEach(node -> returnedIds.add(node.get("requestId").asText()));
        assertThat(returnedIds).containsExactly(
                third.getRequestId().toString(),
                second.getRequestId().toString(),
                first.getRequestId().toString());

        // Deterministic across reads: a second call returns the same order.
        MvcResult repeat = getUsage(token);
        assertThat(objectMapper.readTree(repeat.getResponse().getContentAsString()).get("history"))
                .isEqualTo(body.get("history"));
    }

    @Test
    void aggregatePreservesNullTokenSemantics() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);

        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED);
        store(actor, null, null, null, GatewayUsageOutcome.DELIVERED);
        store(actor, 5L, null, null, GatewayUsageOutcome.SECURITY_BLOCKED);

        mvc.perform(get("/api/gateway/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregate.recordCount").value(3))
                .andExpect(jsonPath("$.aggregate.promptTokens").value(15))
                .andExpect(jsonPath("$.aggregate.completionTokens").value(20))
                .andExpect(jsonPath("$.aggregate.totalTokens").value(30));
    }

    @Test
    void allUnknownTokensAggregateToUnknownWithExactCount() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);

        store(actor, null, null, null, GatewayUsageOutcome.DELIVERED);
        store(actor, null, null, null, GatewayUsageOutcome.SECURITY_BLOCKED);

        mvc.perform(get("/api/gateway/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregate.recordCount").value(2))
                .andExpect(jsonPath("$.aggregate.promptTokens").doesNotExist())
                .andExpect(jsonPath("$.aggregate.completionTokens").doesNotExist())
                .andExpect(jsonPath("$.aggregate.totalTokens").doesNotExist());
    }

    @Test
    void securityBlockedRecordsAreIncluded() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);

        GatewayUsageRecord blocked = store(actor, 10L, 20L, 30L, GatewayUsageOutcome.SECURITY_BLOCKED);

        mvc.perform(get("/api/gateway/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].requestId").value(blocked.getRequestId().toString()))
                .andExpect(jsonPath("$.history[0].outcome").value("SECURITY_BLOCKED"))
                .andExpect(jsonPath("$.aggregate.recordCount").value(1))
                .andExpect(jsonPath("$.aggregate.promptTokens").value(10));
    }

    @Test
    void responseContainsNoActorSubject() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 1L, 2L, 3L, GatewayUsageOutcome.DELIVERED);

        MvcResult result = mvc.perform(get("/api/gateway/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].actorSubject").doesNotExist())
                .andExpect(jsonPath("$.aggregate.actorSubject").doesNotExist())
                .andExpect(jsonPath("$.actorSubject").doesNotExist())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("actorSubject");
        assertThat(response).doesNotContain(actor);
    }

    @Test
    void responseContainsNoPromptOrResponseContent() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 1L, 2L, 3L, GatewayUsageOutcome.DELIVERED);

        MvcResult result = mvc.perform(get("/api/gateway/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].requestId").exists())
                .andExpect(jsonPath("$.history[0].model").value(MODEL))
                .andExpect(jsonPath("$.history[0].promptTokens").value(1))
                .andExpect(jsonPath("$.history[0].completionTokens").value(2))
                .andExpect(jsonPath("$.history[0].totalTokens").value(3))
                .andExpect(jsonPath("$.history[0].outcome").value("DELIVERED"))
                .andExpect(jsonPath("$.history[0].createdAt").exists())
                .andExpect(jsonPath("$.history[0].id").doesNotExist())
                .andExpect(jsonPath("$.history[0].prompt").doesNotExist())
                .andExpect(jsonPath("$.history[0].response").doesNotExist())
                .andExpect(jsonPath("$.history[0].content").doesNotExist())
                .andExpect(jsonPath("$.history[0].secret").doesNotExist())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response)
                .doesNotContain("\"prompt\"", "\"response\"", "\"content\"", "\"secret\"", "\"redis\"");
        // History items expose exactly the seven documented metadata keys.
        JsonNode item = objectMapper.readTree(response).get("history").get(0);
        List<String> keys = new ArrayList<>(item.propertyNames());
        assertThat(keys).containsExactlyInAnyOrder(
                "requestId", "model", "promptTokens", "completionTokens", "totalTokens", "outcome", "createdAt");
    }
}

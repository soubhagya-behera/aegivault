package com.aegivault.aegivault.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.identity.UserRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated time-windowed usage aggregate HTTP endpoint against real
 * PostgreSQL. The actor always comes from the verified JWT subject — the
 * endpoint accepts no actor parameter of any kind — and the UTC half-open
 * window ({@code from} inclusive, {@code to} exclusive) is the one the
 * query service already owns. Responses carry the four aggregate fields
 * only, with null-means-unknown preserved; invalid windows are rejected
 * with a safe 400 before any query runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayUsageAggregateApiTest {

    private static final String MODEL = "usage-window-test-model";

    private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");

    private static final Instant TO = Instant.parse("2026-04-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayUsageRepository usage;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private static String email() {
        return "gateway-usage-window-" + UUID.randomUUID() + "@example.com";
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

    /** Stores one usage row and pins its created_at so windows are exact. */
    private GatewayUsageRecord store(
            String actor, Long prompt, Long completion, Long total, GatewayUsageOutcome outcome, Instant createdAt) {
        GatewayUsageRecord saved = usage.saveAndFlush(
                new GatewayUsageRecord(UUID.randomUUID(), actor, MODEL, prompt, completion, total, outcome));
        jdbc.update(
                "UPDATE gateway_usage_records SET created_at = ? WHERE id = ?",
                createdAt.atOffset(ZoneOffset.UTC),
                saved.getId());
        return saved;
    }

    private String aggregateQuery(String from, String to) {
        return "/api/gateway/usage/aggregate?from=" + from + "&to=" + to;
    }


    @Test
    void validWindowReturnsTheActorsAggregate() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-15T12:00:00Z"));

        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(10))
                .andExpect(jsonPath("$.completionTokens").value(20))
                .andExpect(jsonPath("$.totalTokens").value(30));
    }

    @Test
    void rowExactlyAtFromIsIncluded() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 11L, 22L, 33L, GatewayUsageOutcome.DELIVERED, FROM);

        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(11));
    }

    @Test
    void rowExactlyAtToIsExcluded() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-15T00:00:00Z"));
        store(actor, 99L, 99L, 198L, GatewayUsageOutcome.DELIVERED, TO);

        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(10))
                .andExpect(jsonPath("$.completionTokens").value(20))
                .andExpect(jsonPath("$.totalTokens").value(30));
    }

    @Test
    void rowsOutsideTheWindowAreExcluded() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-02-28T23:59:59Z"));
        store(actor, 500L, 600L, 1100L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-04-01T00:00:01Z"));
        store(actor, 1L, 2L, 3L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-31T23:59:59Z"));

        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(1))
                .andExpect(jsonPath("$.completionTokens").value(2))
                .andExpect(jsonPath("$.totalTokens").value(3));
    }

    @Test
    void multipleActorsRemainIsolated() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        register(secondEmail);
        String firstActor = actorFor(firstEmail);
        String secondActor = actorFor(secondEmail);

        GatewayUsageRecord own =
                store(firstActor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        store(secondActor, 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        MvcResult result = mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(10))
                .andReturn();

        // The caller sees only their own rows: the response leaks neither
        // the other actor's subject nor that actor's token totals.
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(secondActor)
                .doesNotContain(own.getRequestId().toString())
                .doesNotContain("100");
    }


    @Test
    void actorSubjectQueryParameterIsIgnored() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        register(secondEmail);
        String firstActor = actorFor(firstEmail);
        String secondActor = actorFor(secondEmail);
        store(firstActor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        store(secondActor, 100L, 200L, 300L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        // An actor in a query parameter is never bound: the JWT subject
        // alone still decides whose usage is aggregated.
        mvc.perform(get("/api/gateway/usage/aggregate")
                        .header("Authorization", "Bearer " + firstToken)
                        .queryParam("from", FROM.toString())
                        .queryParam("to", TO.toString())
                        .queryParam("actorSubject", secondActor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(10));
    }

    @Test
    void securityBlockedUsageIsIncluded() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-12T00:00:00Z"));

        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.promptTokens").value(10))
                .andExpect(jsonPath("$.completionTokens").value(20))
                .andExpect(jsonPath("$.totalTokens").value(30));
    }

    @Test
    void allUnknownTokenValuesRemainNull() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, null, null, null, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        store(actor, null, null, null, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-20T00:00:00Z"));

        MvcResult result = mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(2))
                .andReturn();

        // Unknown is preserved as unknown, never reported as zero.
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(token(body, "promptTokens")).isNull();
        assertThat(token(body, "completionTokens")).isNull();
        assertThat(token(body, "totalTokens")).isNull();
    }

    @Test
    void mixedKnownAndUnknownValuesAggregateCorrectly() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-05T00:00:00Z"));
        store(actor, null, null, null, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));
        store(actor, 5L, null, null, GatewayUsageOutcome.SECURITY_BLOCKED, Instant.parse("2026-03-15T00:00:00Z"));

        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(3))
                .andExpect(jsonPath("$.promptTokens").value(15))
                .andExpect(jsonPath("$.completionTokens").value(20))
                .andExpect(jsonPath("$.totalTokens").value(30));
    }

    @Test
    void emptyWindowReturnsZeroCountAndUnknownTokens() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        // Rows exist for this actor, but none inside the queried window.
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-01-05T00:00:00Z"));

        MvcResult result = mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(0))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(token(body, "promptTokens")).isNull();
        assertThat(token(body, "completionTokens")).isNull();
        assertThat(token(body, "totalTokens")).isNull();
    }

    /** Reads a token field, treating an omitted key and an explicit null alike. */
    private Long token(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asLong();
    }

    @Test
    void missingFromReturnsBadRequest() throws Exception {
        String token = register(email());

        mvc.perform(get("/api/gateway/usage/aggregate")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("to", TO.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void missingToReturnsBadRequest() throws Exception {
        String token = register(email());

        mvc.perform(get("/api/gateway/usage/aggregate")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("from", FROM.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void malformedFromReturnsBadRequest() throws Exception {
        String token = register(email());

        mvc.perform(get(aggregateQuery("not-an-instant", TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void malformedToReturnsBadRequest() throws Exception {
        String token = register(email());

        mvc.perform(get(aggregateQuery(FROM.toString(), "2026-04-01"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void malformedBoundsAreNotEchoedBack() throws Exception {
        String token = register(email());

        MvcResult result = mvc.perform(get(aggregateQuery("not-an-instant", "also-not-an-instant"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("not-an-instant");
        assertThat(body).doesNotContain("DateTimeParseException");
    }

    @Test
    void equalBoundsReturnBadRequest() throws Exception {
        String token = register(email());

        mvc.perform(get(aggregateQuery(FROM.toString(), FROM.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from must be strictly before to."));
    }

    @Test
    void reversedBoundsReturnBadRequest() throws Exception {
        String token = register(email());

        mvc.perform(get(aggregateQuery(TO.toString(), FROM.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from must be strictly before to."));
    }

    @Test
    void invalidWindowNeverReachesTheDatabase() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        // A rejected window is a 400 carrying no aggregate at all, so the
        // query service rejected it before running the database aggregate.
        mvc.perform(get(aggregateQuery(TO.toString(), FROM.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.recordCount").doesNotExist())
                .andExpect(jsonPath("$.promptTokens").doesNotExist());
    }


    @Test
    void responseCarriesOnlyTheFourAggregateFields() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        MvcResult result = mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        assertThat(new ArrayList<>(node.propertyNames()))
                .containsExactlyInAnyOrder("recordCount", "promptTokens", "completionTokens", "totalTokens");
        // Exactly one aggregate object: no history, no page, no window echo.
        assertThat(body)
                .doesNotContain("actorSubject", "\"from\"", "\"to\"", "history", "requestId", "\"model\"", "\"id\"");
        assertThat(body).doesNotContain(actor);
    }

    @Test
    void existingUsageEndpointIsUnchanged() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        GatewayUsageRecord record =
                store(actor, 10L, 20L, 30L, GatewayUsageOutcome.DELIVERED, Instant.parse("2026-03-10T00:00:00Z"));

        // The history endpoint still returns history plus the all-time
        // aggregate, and it still ignores any supplied window.
        mvc.perform(get("/api/gateway/usage")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("from", FROM.toString())
                        .queryParam("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].requestId").value(record.getRequestId().toString()))
                .andExpect(jsonPath("$.aggregate.recordCount").value(1))
                .andExpect(jsonPath("$.aggregate.promptTokens").value(10));
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorized() throws Exception {
        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(aggregateQuery(FROM.toString(), TO.toString()))
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }
}

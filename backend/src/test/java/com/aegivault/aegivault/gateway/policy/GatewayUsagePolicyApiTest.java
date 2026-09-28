package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
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
 * Owner-scoped gateway usage policy HTTP endpoints against real
 * PostgreSQL. The actor always comes from the verified JWT subject — no
 * endpoint accepts an owner from request data — and foreign ids are
 * indistinguishable from missing ones.
 *
 * <p>These endpoints manage definitions only. No gateway traffic path reads
 * a policy, so nothing here changes rate limiting or completion behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayUsagePolicyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    /** Real lifecycle entries about one policy, in the order the ledger stored them. */
    private List<AuditLedgerEntry> lifecycleEntriesFor(UUID policyId, String eventType) {
        return ledger.findAll().stream()
                .filter(entry -> eventType.equals(entry.getEventType()))
                .filter(entry -> policyId.equals(entry.getResourceId()))
                .toList();
    }

    private long lifecycleEntryCount(String eventType) {
        return ledger.findAll().stream()
                .filter(entry -> eventType.equals(entry.getEventType()))
                .count();
    }

    private static String email() {
        return "gateway-usage-policy-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "policy-pass-1", null));
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

    private String createBody(String name) {
        return """
                {
                  "name": "%s",
                  "description": "monthly cap",
                  "requestsPerMinute": 60,
                  "requestsPerDay": 10000,
                  "tokensPerDay": 1000000,
                  "reservationTokensPerRequest": 4000
                }
                """.formatted(name);
    }

    private String create(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void authenticatedCreateReturns201WithALocation() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("team-default")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("team-default"))
                .andExpect(jsonPath("$.description").value("monthly cap"))
                .andExpect(jsonPath("$.requestsPerMinute").value(60))
                .andExpect(jsonPath("$.requestsPerDay").value(10000))
                .andExpect(jsonPath("$.tokensPerDay").value(1000000))
                .andExpect(jsonPath("$.reservationTokensPerRequest").value(4000))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(header().string("Location", containsString("/api/gateway/policies/")));
    }

    @Test
    void enabledDefaultsToTrueWhenOmitted() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"default-on\",\"requestsPerMinute\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void authenticatedListReturnsOnlyTheCallersPolicies() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        register(secondEmail);
        create(firstToken, "mine-one");
        create(firstToken, "mine-two");

        MvcResult result = mvc.perform(get("/api/gateway/policies")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("mine-three");
    }

    @Test
    void emptyOwnerGetsAnEmptyList() throws Exception {
        String token = register(email());

        mvc.perform(get("/api/gateway/policies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void authenticatedGetReturnsTheCallersPolicy() throws Exception {
        String token = register(email());
        String id = create(token, "readable");

        mvc.perform(get("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("readable"));
    }

    @Test
    void authenticatedUpdateReplacesInPlace() throws Exception {
        String token = register(email());
        String id = create(token, "before");

        mvc.perform(put("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "after",
                                  "description": "changed",
                                  "tokensPerDay": 500,
                                  "reservationTokensPerRequest": 250,
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("after"))
                .andExpect(jsonPath("$.description").value("changed"))
                .andExpect(jsonPath("$.requestsPerMinute").doesNotExist())
                .andExpect(jsonPath("$.tokensPerDay").value(500))
                .andExpect(jsonPath("$.reservationTokensPerRequest").value(250))
                .andExpect(jsonPath("$.enabled").value(false));

        // Same id, one row, and the change is durable.
        mvc.perform(get("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("after"));
    }

    @Test
    void authenticatedDeleteRemovesThePolicy() throws Exception {
        String token = register(email());
        String id = create(token, "doomed");

        mvc.perform(delete("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestsReturnUnauthorized() throws Exception {
        mvc.perform(get("/api/gateway/policies")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("nope")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gateway/policies/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/gateway/policies/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void foreignPolicyIsNotFoundAndUnchanged() throws Exception {
        String firstToken = register(email());
        String secondToken = register(email());
        String id = create(firstToken, "private");

        mvc.perform(get("/api/gateway/policies/" + id).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
        mvc.perform(put("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("hijacked")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/gateway/policies/" + id).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());

        // Still the original owner's policy, untouched.
        mvc.perform(get("/api/gateway/policies/" + id).header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("private"));
    }

    @Test
    void missingPolicyIsNotFound() throws Exception {
        String token = register(email());

        mvc.perform(get("/api/gateway/policies/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/gateway/policies/not-a-uuid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerSubjectInTheBodyIsNeverTrusted() throws Exception {
        String firstEmail = email();
        String secondEmail = email();
        String firstToken = register(firstEmail);
        String secondToken = register(secondEmail);
        String secondActor = actorFor(secondEmail);

        MvcResult result = mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "spoofed",
                                  "requestsPerMinute": 5,
                                  "ownerSubject": "%s"
                                }
                                """.formatted(secondActor)))
                .andExpect(status().isCreated())
                .andReturn();

        // The property is not bound: the JWT subject still owns the
        // policy, and the spoofed owner sees nothing.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secondActor);
        mvc.perform(get("/api/gateway/policies").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/gateway/policies").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("spoofed"));
    }

    @Test
    void invalidLimitsAreRejected() throws Exception {
        String token = register(email());

        // Zero, negative, and unset-everything are all client errors.
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"zero\",\"requestsPerMinute\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"negative\",\"requestsPerDay\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"zero-tokens\",\"tokensPerDay\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"constrains-nothing\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void blankNameOverlongLabelAndEmptyBodyAreRejected() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"requestsPerMinute\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"requestsPerMinute\":5}".formatted("n".repeat(256))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTokenLimitWithoutAReservationAmountIsRejected() throws Exception {
        String token = register(email());

        // The reservation amount is policy configuration, so a daily token
        // limit cannot be declared without one.
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tokens-only\",\"tokensPerDay\":1000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReservationGreaterThanTheTokenLimitIsRejected() throws Exception {
        String token = register(email());

        // Rejected rather than clamped: the stored value must always be one
        // the owner actually chose.
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"over","tokensPerDay":1000,"reservationTokensPerRequest":1001}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReservationEqualToTheTokenLimitIsAccepted() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"whole-day","tokensPerDay":1000,"reservationTokensPerRequest":1000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokensPerDay").value(1000))
                .andExpect(jsonPath("$.reservationTokensPerRequest").value(1000));
    }

    @Test
    void aNonPositiveReservationAmountIsRejected() throws Exception {
        String token = register(email());

        // Caught by the @Positive bound before the aggregate is reached.
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"zero","tokensPerDay":1000,"reservationTokensPerRequest":0}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"negative","tokensPerDay":1000,"reservationTokensPerRequest":-5}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReservationWithoutATokenLimitIsAccepted() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"requests","requestsPerMinute":60,"reservationTokensPerRequest":500}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokensPerDay").doesNotExist())
                .andExpect(jsonPath("$.reservationTokensPerRequest").value(500));
    }

    @Test
    void requestOnlyPoliciesAreUnaffectedByTheNewField() throws Exception {
        String token = register(email());

        // A pre-existing non-token policy still creates and reads back exactly
        // as it did before this field existed.
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"legacy\",\"requestsPerMinute\":60,\"requestsPerDay\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestsPerMinute").value(60))
                .andExpect(jsonPath("$.requestsPerDay").value(100))
                .andExpect(jsonPath("$.tokensPerDay").doesNotExist())
                .andExpect(jsonPath("$.reservationTokensPerRequest").doesNotExist());
    }

    @Test
    void responseNeverContainsTheOwnerSubject() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        String id = create(token, "readable");

        MvcResult result = mvc.perform(get("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("ownerSubject").doesNotContain(actor);
        JsonNode node = objectMapper.readTree(body);
        assertThat(new ArrayList<>(node.propertyNames()))
                .containsExactlyInAnyOrder(
                        "id", "name", "description", "requestsPerMinute", "requestsPerDay",
                        "tokensPerDay", "reservationTokensPerRequest", "enabled", "createdAt", "updatedAt");
    }

    @Test
    void policiesCarryNoPricingOrCurrencyFields() throws Exception {
        String token = register(email());
        String id = create(token, "no-money");

        MvcResult result = mvc.perform(get("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase(Locale.ROOT))
                .doesNotContain("cost")
                .doesNotContain("price")
                .doesNotContain("currency");
    }

    @Test
    void creatingAPolicyDoesNotAffectGatewayTraffic() throws Exception {
        String token = register(email());
        // Declared limits are stored but never applied: gateway usage and
        // completion behavior are unchanged by their existence.
        create(token, "unused");
        create(token, "also-unused");

        mvc.perform(get("/api/gateway/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history").isEmpty());
        mvc.perform(get("/api/gateway/policies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void creatingAPolicyAppendsExactlyOneCreatedEventToTheRealLedger() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);

        String id = create(token, "audited-create");

        List<AuditLedgerEntry> entries =
                lifecycleEntriesFor(UUID.fromString(id), AuditEventData.GATEWAY_USAGE_POLICY_CREATED);
        assertThat(entries).hasSize(1);
        AuditLedgerEntry entry = entries.get(0);
        assertThat(entry.getEventData()).isEqualTo("{\"action\":\"CREATED\"}");
        assertThat(entry.getResourceType()).isEqualTo("GATEWAY_USAGE_POLICY");
        assertThat(entry.getResourceId()).isEqualTo(UUID.fromString(id));
        // The actor lives in the ledger's own column and nowhere else.
        assertThat(entry.getActorSubject()).isEqualTo(actor);
        assertThat(entry.getEventData()).doesNotContain(actor);
    }

    @Test
    void updatingAPolicyAppendsExactlyOneUpdatedEvent() throws Exception {
        String token = register(email());
        String id = create(token, "before");

        mvc.perform(put("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("after")))
                .andExpect(status().isOk());

        var created =
                lifecycleEntriesFor(UUID.fromString(id), AuditEventData.GATEWAY_USAGE_POLICY_CREATED);
        var updated =
                lifecycleEntriesFor(UUID.fromString(id), AuditEventData.GATEWAY_USAGE_POLICY_UPDATED);
        assertThat(created).hasSize(1);
        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).getEventData()).isEqualTo("{\"action\":\"UPDATED\"}");
    }

    @Test
    void deletingAPolicyAppendsExactlyOneDeletedEvent() throws Exception {
        String token = register(email());
        String id = create(token, "doomed");

        mvc.perform(delete("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        var deleted =
                lifecycleEntriesFor(UUID.fromString(id), AuditEventData.GATEWAY_USAGE_POLICY_DELETED);
        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).getEventData()).isEqualTo("{\"action\":\"DELETED\"}");
        assertThat(deleted.get(0).getResourceId()).isEqualTo(UUID.fromString(id));
    }

    @Test
    void aRejectedPolicyWriteAppendsNoLifecycleEvent() throws Exception {
        String token = register(email());
        long createdBefore = lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_CREATED);

        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"constrains-nothing\"}"))
                .andExpect(status().isBadRequest());

        // The mutation never happened, so nothing is claimed to have happened.
        assertThat(lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_CREATED))
                .isEqualTo(createdBefore);
    }

    @Test
    void aForeignOrMissingPolicyWriteAppendsNoLifecycleEvent() throws Exception {
        String firstToken = register(email());
        String secondToken = register(email());
        String id = create(firstToken, "private");
        long updatedBefore = lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED);
        long deletedBefore = lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_DELETED);

        mvc.perform(put("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("hijacked")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/gateway/policies/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ghost")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/gateway/policies/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());

        // Foreign and missing are indistinguishable, and neither is audited:
        // no change occurred, so there is no change to evidence.
        assertThat(lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED))
                .isEqualTo(updatedBefore);
        assertThat(lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_DELETED))
                .isEqualTo(deletedBefore);
    }

    @Test
    void lifecycleEntriesCopyNoPolicyContentIntoTheLedger() throws Exception {
        String token = register(email());
        String id = create(token, "confidential-label");

        mvc.perform(put("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("confidential-label")))
                .andExpect(status().isOk());

        for (AuditLedgerEntry entry : lifecycleEntriesFor(
                UUID.fromString(id), AuditEventData.GATEWAY_USAGE_POLICY_UPDATED)) {
            // The ledger proves the policy changed; it is not a second copy of
            // the policy, and it holds no request body, counter, or content.
            assertThat(entry.getEventData())
                    .isEqualTo("{\"action\":\"UPDATED\"}")
                    .doesNotContain("confidential-label")
                    .doesNotContain("monthly cap")
                    .doesNotContain("1000000")
                    .doesNotContain("enabled")
                    .doesNotContain("ownerSubject")
                    .doesNotContain("policy-counter");
        }
    }

    @Test
    void theOwnerCanReadTheirPolicyAuditHistoryOverHttp() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        String id = create(token, "history-subject");

        mvc.perform(put("/api/gateway/policies/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("history-subject")))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString()).get("entries");
        assertThat(entries).isNotNull();
        assertThat(entries).hasSize(2);
        // Newest first, and only this owner's policy events.
        assertThat(entries.get(0).get("eventType").asText())
                .isEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED);
        assertThat(entries.get(0).get("eventData").asText()).isEqualTo("{\"action\":\"UPDATED\"}");
        assertThat(entries.get(1).get("eventType").asText())
                .isEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_CREATED);
        assertThat(entries.get(0).get("resourceId").asText()).isEqualTo(id);
        assertThat(entries.get(0).get("createdAt").asText()).isNotBlank();
        // Exactly the four safe fields: no hashes, no sequence number, no actor.
        assertThat(new ArrayList<>(entries.get(0).propertyNames()))
                .containsExactlyInAnyOrder("eventType", "resourceId", "eventData", "createdAt");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(actor)
                .doesNotContain("entryHash")
                .doesNotContain("previousHash")
                .doesNotContain("sequenceNumber");
    }

    @Test
    void aDeletedPolicyStillHasAnAuditableHistory() throws Exception {
        String token = register(email());
        String id = create(token, "history-then-deleted");

        mvc.perform(delete("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The policy row is gone, and the ledger still answers.
        mvc.perform(get("/api/gateway/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].eventType")
                        .value(AuditEventData.GATEWAY_USAGE_POLICY_DELETED));
    }

    @Test
    void anotherActorsPolicyHistoryIsEmpty() throws Exception {
        String firstToken = register(email());
        String secondToken = register(email());
        String id = create(firstToken, "history-private");

        // An empty 200 rather than a 404, so the response never confirms that
        // someone else's policy exists.
        mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void anUnknownPolicyHistoryIsEmpty() throws Exception {
        String token = register(email());

        mvc.perform(get("/api/gateway/policies/" + UUID.randomUUID() + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void thePolicyHistoryEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/gateway/policies/" + UUID.randomUUID() + "/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePolicyHistoryIgnoresAnyClientSuppliedActor() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String actor = actorFor(userEmail);
        String otherToken = register(email());
        String id = create(token, "history-actor-scope");

        // A spoofed actorSubject cannot widen the read: the JWT subject wins,
        // so the caller still sees only their own history.
        mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("actorSubject", actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void thePolicyHistoryExcludesUnrelatedAndInspectionEvents() throws Exception {
        String token = register(email());
        String id = create(token, "history-filtered");

        // An inspected completion against the same policy writes an
        // inspection event too; it must not appear in the policy history.
        mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"summarize revenue.\"}"))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(AuditEventData.GATEWAY_INSPECTION_ALLOWED)
                .doesNotContain(AuditEventData.GATEWAY_INSPECTION_BLOCKED)
                .doesNotContain("SANITIZATION_RUN");
        for (JsonNode entry : objectMapper.readTree(body).get("entries")) {
            assertThat(entry.get("eventType").asText()).startsWith("GATEWAY_USAGE_POLICY_");
        }
    }

    @Test
    void thePolicyHistoryReadIsReadOnly() throws Exception {
        String token = register(email());
        String id = create(token, "history-read-only");
        long createdBefore = lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_CREATED);
        long updatedBefore = lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED);

        mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/gateway/policies/" + id + "/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Reading history appends nothing: the ledger is not written to.
        assertThat(lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_CREATED))
                .isEqualTo(createdBefore);
        assertThat(lifecycleEntryCount(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED))
                .isEqualTo(updatedBefore);
    }
}

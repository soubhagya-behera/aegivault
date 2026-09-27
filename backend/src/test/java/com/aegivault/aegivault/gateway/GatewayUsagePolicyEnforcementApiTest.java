package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import java.util.List;
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
 * End-to-end policy request-limit enforcement on
 * {@code POST /api/gateway/complete} against real PostgreSQL, with the real
 * resolver, the real enforcement service, and the real (default in-memory)
 * atomic counter — nothing about the policy layer is stubbed.
 *
 * <p>Each test registers its own user, so each actor has its own counter and
 * the tests stay independent even though the counter is a shared bean. The
 * policy is created through its own API, which is what makes this a genuine
 * proof that a user-defined policy is actually enforced on live traffic.
 *
 * <p>Its annotations deliberately match the other plain
 * {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} gateway tests so the
 * Spring context cache reuses one context instead of adding another pooled
 * datasource.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayUsagePolicyEnforcementApiTest {

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    private static final String EMAIL = "alice@example.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    private List<AuditLedgerEntry> entriesOfType(String eventType) {
        return ledger.findAll().stream()
                .filter(entry -> eventType.equals(entry.getEventType()))
                .toList();
    }

    private String register() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("policy-" + UUID.randomUUID() + "@example.com", "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private void createPolicy(String token, String json) throws Exception {
        mvc.perform(post("/api/gateway/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }

    private MvcResult complete(String token, String content) throws Exception {
        return mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"" + content + "\"}"))
                .andReturn();
    }

    @Test
    void anActorWithNoPolicyCompletesNormally() throws Exception {
        String token = register();

        MvcResult result = complete(token, CLEAN);

        // No policy governs this actor, so nothing limits the request.
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("ALLOW");
    }

    @Test
    void anEnabledPolicyAllowsUpToItsRequestLimitThenRejectsWith429() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"one-per-minute\",\"requestsPerMinute\":1,\"enabled\":true}");

        // The limit is the highest permitted value, so request 1 passes.
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        MvcResult rejected = complete(token, CLEAN);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        String body = rejected.getResponse().getContentAsString();
        assertThat(body).contains("Gateway usage policy limit exceeded.");
        // Never the global rate limiter's message: the two stay distinct.
        assertThat(body).doesNotContain("Gateway rate limit exceeded.");
        // No window, actor, policy, or counter detail escapes.
        assertThat(body).doesNotContain("policy-counter");
        assertThat(body).doesNotContain("MINUTE");
        assertThat(body).doesNotContain("DAY");
    }

    @Test
    void thePolicyRejectionBodyCarriesOnlyTheMessage() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"one-per-minute\",\"requestsPerMinute\":1,\"enabled\":true}");
        complete(token, CLEAN);

        MvcResult rejected = complete(token, CLEAN);

        var json = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(json.propertyNames()).isEqualTo(new java.util.TreeSet<>(java.util.List.of("message")));
        assertThat(json.get("message").asText()).isEqualTo("Gateway usage policy limit exceeded.");
    }

    @Test
    void aDayLimitIsEnforcedToo() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"one-per-day\",\"requestsPerDay\":1,\"enabled\":true}");

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void aDisabledPolicyDoesNotLimitTheActor() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"switched-off\",\"requestsPerMinute\":1,\"enabled\":false}");

        // A disabled policy is not applied, so it neither blocks nor spends.
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aTokenOnlyPolicyDoesNotLimitTheActor() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"tokens-only\",\"tokensPerDay\":1000,\"enabled\":true}");

        // tokensPerDay is not enforced: there is no truthful token number
        // before the provider runs, so the request is simply not limited.
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void twoEnabledPoliciesFailAs500WithoutChoosingOne() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"first\",\"requestsPerMinute\":10,\"enabled\":true}");
        createPolicy(token, "{\"name\":\"second\",\"requestsPerMinute\":20,\"enabled\":true}");

        MvcResult result = complete(token, CLEAN);

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Unable to resolve gateway usage policy.");
        // No policy detail: not the ambiguity text, no id, owner, or count.
        assertThat(body).doesNotContain("Multiple enabled");
        assertThat(body).doesNotContain("aegivault");
    }

    @Test
    void aSecurityBlockUnderAnAllowedPolicyIsStill200() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"generous\",\"requestsPerMinute\":100,\"enabled\":true}");

        MvcResult result = complete(token, "contact " + EMAIL + " for access.");

        // BLOCK is data, never an error status; policy admission changed
        // nothing about the existing security outcome.
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("BLOCK");
    }

    @Test
    void anAdmittedRequestWritesAPolicyAllowedEventToTheRealLedger() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"audited\",\"requestsPerMinute\":50,\"enabled\":true}");
        long before = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size();

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        // A real ledger entry, written by the real audit seam.
        var created = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED);
        assertThat(created.size()).isEqualTo(before + 1);
        AuditLedgerEntry entry = created.get(created.size() - 1);
        assertThat(entry.getResourceType()).isEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_RESOURCE);
        assertThat(entry.getResourceId()).isNotNull();
        assertThat(entry.getEventData())
                .isEqualTo("{\"decision\":\"ALLOW\",\"enforcedWindows\":[\"MINUTE\"]}");
        // The actor is stored as its own column, never repeated in the data.
        assertThat(entry.getEventData()).doesNotContain(entry.getActorSubject());
    }

    @Test
    void aRejectedRequestIsAuditedBeforeItReturns429() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"one-per-minute\",\"requestsPerMinute\":1,\"enabled\":true}");
        complete(token, CLEAN);
        long before = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED).size();

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(429);

        // The refusal left evidence even though the request went nowhere.
        var created = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED);
        assertThat(created.size()).isEqualTo(before + 1);
        assertThat(created.get(created.size() - 1).getEventData())
                .isEqualTo("{\"decision\":\"REJECTED\",\"rejectedWindow\":\"MINUTE\"}");
    }

    @Test
    void aRejectedRequestProducesNoInspectionEvent() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"one-per-minute\",\"requestsPerMinute\":1,\"enabled\":true}");
        long allowedBefore = entriesOfType(AuditEventData.GATEWAY_INSPECTION_ALLOWED).size();
        long blockedBefore = entriesOfType(AuditEventData.GATEWAY_INSPECTION_BLOCKED).size();

        // The first request is admitted and inspected; the second is refused
        // before inspection, so the ledger gains no further inspection event.
        complete(token, CLEAN);
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(429);

        assertThat(entriesOfType(AuditEventData.GATEWAY_INSPECTION_ALLOWED).size())
                .isEqualTo(allowedBefore + 1);
        assertThat(entriesOfType(AuditEventData.GATEWAY_INSPECTION_BLOCKED).size())
                .isEqualTo(blockedBefore);
    }

    @Test
    void anActorWithNoPolicyWritesNoPolicyEvent() throws Exception {
        String token = register();
        long allowedBefore = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size();
        long rejectedBefore = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED).size();

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        // No policy consulted, so no policy decision to evidence.
        assertThat(entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size())
                .isEqualTo(allowedBefore);
        assertThat(entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED).size())
                .isEqualTo(rejectedBefore);
    }

    @Test
    void aDisabledPolicyWritesNoPolicyEvent() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"switched-off\",\"requestsPerMinute\":1,\"enabled\":false}");
        long allowedBefore = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size();
        long rejectedBefore = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED).size();

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        // Documented choice: a disabled policy is not applied, so an ALLOWED
        // event would claim a quota check that never ran.
        assertThat(entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size())
                .isEqualTo(allowedBefore);
        assertThat(entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED).size())
                .isEqualTo(rejectedBefore);
    }

    @Test
    void aTokenOnlyPolicyIsRecordedButEnforcesNoRequestLimit() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"tokens-only\",\"tokensPerDay\":1000,\"enabled\":true}");
        long before = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size();

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        // The policy was applied (and so is recorded), but with no request
        // window configured, so tokensPerDay is still unenforced.
        var created = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED);
        assertThat(created.size()).isEqualTo(before + 1);
        assertThat(created.get(created.size() - 1).getEventData())
                .isEqualTo("{\"decision\":\"ALLOW\",\"enforcedWindows\":[]}");
    }

    @Test
    void aSecurityBlockStillUsesTheExistingInspectionBlockEvent() throws Exception {
        String token = register();
        createPolicy(token, "{\"name\":\"generous\",\"requestsPerMinute\":100,\"enabled\":true}");
        long blockedBefore = entriesOfType(AuditEventData.GATEWAY_INSPECTION_BLOCKED).size();

        assertThat(complete(token, "contact " + EMAIL + " for access.").getResponse().getStatus())
                .isEqualTo(200);

        // The inspection audit path is untouched: a BLOCK is still recorded as
        // an inspection event, alongside the policy event, not instead of it.
        assertThat(entriesOfType(AuditEventData.GATEWAY_INSPECTION_BLOCKED).size())
                .isEqualTo(blockedBefore + 1);
    }

    @Test
    void anotherActorsPolicyDoesNotLimitThisActor() throws Exception {
        String strict = register();
        String other = register();
        createPolicy(strict, "{\"name\":\"one-per-minute\",\"requestsPerMinute\":1,\"enabled\":true}");

        assertThat(complete(strict, CLEAN).getResponse().getStatus()).isEqualTo(200);
        assertThat(complete(strict, CLEAN).getResponse().getStatus()).isEqualTo(429);

        // Limits are owner-scoped: a different actor is unaffected.
        assertThat(complete(other, CLEAN).getResponse().getStatus()).isEqualTo(200);
    }
}
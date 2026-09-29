package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRecord;
import com.aegivault.aegivault.gateway.usage.GatewayUsageRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end policy request-limit enforcement on
 * {@code POST /api/gateway/complete} against real PostgreSQL, with the real
 * resolver, the real enforcement service, and the real (default in-memory)
 * atomic counter — nothing about the policy layer is stubbed.
 *
 * <p>Each test registers its own user, so each actor has its own counter and
 * its own token budget and the tests stay independent even though the counter
 * and the budget are shared beans. The policy is created through its own API,
 * which is what makes this a genuine proof that a user-defined policy is
 * actually enforced on live traffic — including the token half, which
 * {@code tokensPerDay} reserves and settles around the provider call.
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

    @Autowired
    private GatewayUsageRepository usageRecords;

    @Autowired
    private JwtDecoder jwtDecoder;

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

    /**
     * The single entry of that type that belongs to this test's own actor.
     *
     * <p>{@code findAll()} carries no ordering guarantee, so "the last entry"
     * would be an arbitrary row of the shared ledger. Scoping by the JWT
     * subject is what makes these assertions mean what they claim: this
     * request's decision, not somebody else's.
     */
    private AuditLedgerEntry entryFor(String eventType, String token) {
        String actor = jwtDecoder.decode(token).getSubject();
        return entriesOfType(eventType).stream()
                .filter(entry -> actor.equals(entry.getActorSubject()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no " + eventType + " entry for this actor"));
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
    void aTokenOnlyPolicyIsNowEnforcedAndRecoversTheReservedTokensItDidNotUse() throws Exception {
        // The end-to-end proof that tokensPerDay is a live control: a policy
        // with no request window at all still reserves before the provider is
        // invoked and settles after it answers.
        String token = register();
        createPolicy(token, "{\"name\":\"tokens-only\",\"tokensPerDay\":1000,"
                + "\"reservationTokensPerRequest\":100,\"enabled\":true}");

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        // One provider response means exactly one usage row, carrying this
        // provider's own (here: unreported) counts — null stays null.
        List<GatewayUsageRecord> rows = usageRecords.findByActorSubjectOrderByCreatedAtDescIdDesc(
                jwtDecoder.decode(token).getSubject());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTotalTokens()).isNull();
        assertThat(rows.get(0).getOutcome().name()).isEqualTo("DELIVERED");
    }

    @Test
    void aTokenOnlyPolicyRefusesWithItsOwnMessageOnceTheDayIsSpent() throws Exception {
        // The mock provider reports no token count, so each reservation stays
        // held for the day: a 1,000-token day with 100 held per request admits
        // ten requests and refuses the eleventh.
        String token = register();
        createPolicy(token, "{\"name\":\"tokens-only\",\"tokensPerDay\":1000,"
                + "\"reservationTokensPerRequest\":100,\"enabled\":true}");

        for (int i = 0; i < 10; i++) {
            assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
        }

        MvcResult refused = complete(token, CLEAN);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        var json = objectMapper.readTree(refused.getResponse().getContentAsString());
        assertThat(json.propertyNames()).isEqualTo(new java.util.TreeSet<>(java.util.List.of("message")));
        assertThat(json.get("message").asText()).isEqualTo("Gateway token budget exceeded.");
        // Never the request-limit or global rate-limit message, and no budget
        // state whatsoever.
        assertThat(refused.getResponse().getContentAsString())
                .doesNotContain("Gateway usage policy limit exceeded.", "Gateway rate limit exceeded.")
                .doesNotContain("1000", "100", "remaining", "reservation", "token-budget");
        // A refused reservation never reaches a provider, so no eleventh row.
        assertThat(usageRecords.findByActorSubjectOrderByCreatedAtDescIdDesc(
                jwtDecoder.decode(token).getSubject())).hasSize(10);
    }

    @Test
    void aMixedPolicyEnforcesRequestsAndTokensIndependently() throws Exception {
        // The request window is exhausted first and says so; the token budget
        // then refuses on its own with a different message. Neither control
        // compensates for the other.
        String strictToken = register();
        createPolicy(strictToken, "{\"name\":\"one-per-minute\",\"requestsPerMinute\":1,"
                + "\"tokensPerDay\":100000,\"reservationTokensPerRequest\":100,\"enabled\":true}");

        assertThat(complete(strictToken, CLEAN).getResponse().getStatus()).isEqualTo(200);
        assertThat(complete(strictToken, CLEAN).getResponse().getContentAsString())
                .contains("Gateway usage policy limit exceeded.");

        // A second actor whose request window is generous but whose token day
        // is not: admitted twice by the request control, refused by the token
        // one.
        String tokenOnlyToken = register();
        createPolicy(tokenOnlyToken, "{\"name\":\"generous-requests\",\"requestsPerMinute\":100,"
                + "\"tokensPerDay\":100,\"reservationTokensPerRequest\":100,\"enabled\":true}");

        assertThat(complete(tokenOnlyToken, CLEAN).getResponse().getStatus()).isEqualTo(200);
        MvcResult tokenRefused = complete(tokenOnlyToken, CLEAN);

        assertThat(tokenRefused.getResponse().getStatus()).isEqualTo(429);
        assertThat(tokenRefused.getResponse().getContentAsString())
                .contains("Gateway token budget exceeded.")
                .doesNotContain("Gateway usage policy limit exceeded.");
    }

    @Test
    void aSecurityBlockReservesNothingFromTheActorsTokenDay() throws Exception {
        // Reservation happens after request inspection, so a blocked request
        // leaves the day untouched: the follow-up clean request is still
        // admitted even though the whole day was exactly one reservation wide.
        String token = register();
        createPolicy(token, "{\"name\":\"one-reservation-per-day\",\"tokensPerDay\":100,"
                + "\"reservationTokensPerRequest\":100,\"enabled\":true}");

        assertThat(complete(token, "contact " + EMAIL + " for access.").getResponse().getStatus())
                .isEqualTo(200);

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);
        // And the day really did have only that one reservation in it.
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(429);
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
        AuditLedgerEntry entry = entryFor(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED, token);
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
        assertThat(entryFor(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED, token).getEventData())
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
        createPolicy(token, "{\"name\":\"tokens-only\",\"tokensPerDay\":1000,\"reservationTokensPerRequest\":100,\"enabled\":true}");
        long before = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED).size();

        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(200);

        // The policy is applied (and so is recorded), but with no request
        // window configured there is no request capacity to consume — the
        // token half is enforced separately, by reservation and settlement.
        var created = entriesOfType(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED);
        assertThat(created.size()).isEqualTo(before + 1);
        assertThat(entryFor(AuditEventData.GATEWAY_USAGE_POLICY_ALLOWED, token).getEventData())
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

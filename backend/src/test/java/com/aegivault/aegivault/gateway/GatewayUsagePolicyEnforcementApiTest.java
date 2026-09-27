package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
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
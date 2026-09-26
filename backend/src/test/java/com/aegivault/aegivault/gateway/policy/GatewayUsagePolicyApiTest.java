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

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.identity.UserRepository;
import java.util.ArrayList;
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
                  "tokensPerDay": 1000000
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
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("after"))
                .andExpect(jsonPath("$.description").value("changed"))
                .andExpect(jsonPath("$.requestsPerMinute").doesNotExist())
                .andExpect(jsonPath("$.tokensPerDay").value(500))
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
                        "tokensPerDay", "enabled", "createdAt", "updatedAt");
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
}

package com.aegivault.aegivault.sanitization.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owner-scoped policy API against real PostgreSQL: creation, listing, and
 * retrieval of reusable policies with deterministic ordering, identical
 * 404s for foreign and missing ids, and validation that rejects duplicates,
 * empty rule sets, unknown enum names, and overlong labels before anything
 * persists. Ownership comes only from the verified JWT subject; USER and
 * ADMIN behave identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SanitizationPolicyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    private static String email() {
        return "policy-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, "policy-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token")
                .asText();
    }

    private String subject(String token) {
        return jwtDecoder.decode(token).getSubject();
    }

    private static String oneRule(String name, String version) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\","
                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"SYNTHETIC_EMAIL\"}]}";
    }

    private MvcResult postPolicy(String token, String body) throws Exception {
        return mvc.perform(post("/api/policies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode create(String token, String body) throws Exception {
        MvcResult result = postPolicy(token, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listPolicies(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/policies")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void ownerCreatesPolicyWith201LocationAndExactResponseShape() throws Exception {
        String token = register(email());

        MvcResult result = postPolicy(token,
                "{\"name\":\"pii-default\",\"version\":\"v1\",\"description\":\"Covers emails\","
                        + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"SYNTHETIC_EMAIL\"}]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = body.get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/policies/" + id);
        assertThat(body.get("name").asText()).isEqualTo("pii-default");
        assertThat(body.get("version").asText()).isEqualTo("v1");
        assertThat(body.get("description").asText()).isEqualTo("Covers emails");
        assertThat(body.get("createdAt").asText()).isNotBlank();
        assertThat(body.get("updatedAt").asText()).isNotBlank();
        assertThat(body.get("rules")).hasSize(1);
        assertThat(body.get("rules").get(0).get("piiType").asText()).isEqualTo("EMAIL");
        assertThat(body.get("rules").get(0).get("strategy").asText()).isEqualTo("SYNTHETIC_EMAIL");
        assertThat(body.get("ownerSubject")).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"ownerSubject\"");
    }

    @Test
    void ownerSubjectAndRawFieldsFromJsonAreIgnored() throws Exception {
        String token = register(email());
        String subject = subject(token);

        JsonNode created = create(token,
                "{\"name\":\"override\",\"version\":\"v1\",\"ownerSubject\":\"attacker\","
                        + "\"createdAt\":\"2020-01-01T00:00:00Z\","
                        + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}");

        JsonNode stored = objectMapper.readTree(
                mvc.perform(get("/api/policies/" + created.get("id").asText())
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(created.get("ownerSubject")).isNull();
        assertThat(stored.get("ownerSubject")).isNull();
        assertThat(stored.get("name").asText()).isEqualTo("override");
        assertThat(stored.get("createdAt").asText()).isNotEqualTo("2020-01-01T00:00:00Z");
        assertThat(subject).isEqualTo(subject(token));
        JsonNode listed = listPolicies(token);
        assertThat(listed).hasSize(1);
        assertThat(stored.get("id").asText()).isEqualTo(created.get("id").asText());
    }

    @Test
    void ownerListsOnlyOwnPolicies() throws Exception {
        String mine = register(email());
        String theirs = register(email());
        create(mine, oneRule("mine", "v1"));
        create(theirs, oneRule("theirs", "v1"));

        JsonNode listed = listPolicies(mine);

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("name").asText()).isEqualTo("mine");
        assertThat(listed.get(0).get("ownerSubject")).isNull();
    }

    @Test
    void emptyListIs200ForANewOwner() throws Exception {
        String token = register(email());

        assertThat(listPolicies(token)).isEmpty();
    }

    @Test
    void ownerRetrievesOwnPolicy() throws Exception {
        String token = register(email());
        JsonNode created = create(token, oneRule("mine", "v1"));
        String id = created.get("id").asText();

        mvc.perform(get("/api/policies/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("mine"))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.rules[0].piiType").value("EMAIL"))
                .andExpect(jsonPath("$.rules[0].strategy").value("SYNTHETIC_EMAIL"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }

    @Test
    void foreignPolicyIdReturnsTheSame404AsAMissingPolicyId() throws Exception {
        String owner = register(email());
        String stranger = register(email());
        String mine = create(owner, oneRule("mine", "v1")).get("id").asText();

        MvcResult foreign = mvc.perform(get("/api/policies/" + mine)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mvc.perform(get("/api/policies/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andReturn();

        String foreignBody = foreign.getResponse().getContentAsString();
        assertThat(foreignBody).isEqualTo("{\"message\":\"Policy not found.\"}");
        assertThat(foreignBody).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(foreignBody).doesNotContain(mine, "ownerSubject", stranger);
    }

    @Test
    void unauthenticatedRequestsAreRejected401() throws Exception {
        String token = register(email());
        String id = create(token, oneRule("mine", "v1")).get("id").asText();

        mvc.perform(get("/api/policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/policies/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneRule("x", "v1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/policies/" + id).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedPolicyUuidReturns400() throws Exception {
        String token = register(email());

        MvcResult result = mvc.perform(get("/api/policies/not-a-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"message\":\"Invalid policy id.\"}");
    }

    @Test
    void listingIsDeterministicNewestFirst() throws Exception {
        String token = register(email());
        String firstId = create(token, oneRule("first", "v1")).get("id").asText();
        Thread.sleep(15L);
        String secondId = create(token, oneRule("second", "v1")).get("id").asText();
        Thread.sleep(15L);
        String thirdId = create(token, oneRule("third", "v1")).get("id").asText();

        JsonNode listed = listPolicies(token);

        assertThat(listed).hasSize(3);
        assertThat(listed.get(0).get("id").asText()).isEqualTo(thirdId);
        assertThat(listed.get(1).get("id").asText()).isEqualTo(secondId);
        assertThat(listed.get(2).get("id").asText()).isEqualTo(firstId);
        assertThat(listed.get(0).get("createdAt").asText())
                .isGreaterThanOrEqualTo(listed.get(1).get("createdAt").asText());
        assertThat(listed.get(1).get("createdAt").asText())
                .isGreaterThanOrEqualTo(listed.get(2).get("createdAt").asText());
    }

    @Test
    void duplicatePiiTypeReturns400AndPersistsNothing() throws Exception {
        String token = register(email());

        MvcResult result = postPolicy(token,
                "{\"name\":\"dupes\",\"version\":\"v1\",\"rules\":"
                        + "[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"},"
                        + "{\"piiType\":\"EMAIL\",\"strategy\":\"MASK\"}]}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"message\":\"Invalid policy request.\"}");
        assertThat(listPolicies(token)).isEmpty();
    }

    @Test
    void emptyOrMissingRulesReturn400() throws Exception {
        String token = register(email());

        assertThat(postPolicy(token, "{\"name\":\"n\",\"version\":\"v1\",\"rules\":[]}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(postPolicy(token, "{\"name\":\"n\",\"version\":\"v1\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(listPolicies(token)).isEmpty();
    }

    @Test
    void invalidEnumValuesReturn400() throws Exception {
        String token = register(email());

        assertThat(postPolicy(token,
                                "{\"name\":\"n\",\"version\":\"v1\",\"rules\":"
                                        + "[{\"piiType\":\"NOT_A_TYPE\",\"strategy\":\"REDACT\"}]}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(postPolicy(token,
                                "{\"name\":\"n\",\"version\":\"v1\",\"rules\":"
                                        + "[{\"piiType\":\"EMAIL\",\"strategy\":\"SHRED\"}]}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(listPolicies(token)).isEmpty();
    }

    @Test
    void labelLengthBoundariesAreEnforced() throws Exception {
        String token = register(email());

        create(token, "{\"name\":\"" + "n".repeat(255) + "\",\"version\":\"" + "v".repeat(255)
                + "\",\"description\":\"" + "d".repeat(1024)
                + "\",\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}");

        assertThat(postPolicy(token, oneRule("n".repeat(256), "v1"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(postPolicy(token, oneRule("n", "v".repeat(256)))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(postPolicy(
                                token,
                                "{\"name\":\"n\",\"version\":\"v1\",\"description\":\""
                                        + "d".repeat(1025)
                                        + "\",\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        JsonNode listed = listPolicies(token);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("name").asText()).hasSize(255);
        assertThat(listed.get(0).get("version").asText()).hasSize(255);
        assertThat(listed.get(0).get("description").asText()).hasSize(1024);
    }

    @Test
    void creationStoresNoRawPiiOrCsvData() throws Exception {
        String token = register(email());
        String body = "{\"name\":\"no-raw\",\"version\":\"v1\","
                + "\"csv\":\"name,email,bob@example.com\","
                + "\"content\":\"password=hunter2\","
                + "\"values\":[\"bob@example.com\"],"
                + "\"ownerSubject\":\"attacker\","
                + "\"rules\":[{\"piiType\":\"EMAIL\",\"strategy\":\"REDACT\"}]}";

        JsonNode created = create(token, body);
        String id = created.get("id").asText();
        String createdBody = created.toString();
        String fetched = mvc.perform(get("/api/policies/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String response : new String[] {createdBody, fetched, listPolicies(token).toString()}) {
            assertThat(response)
                    .doesNotContain("bob@example.com", "hunter2", "\"csv\"", "\"content\"", "\"values\"");
            assertThat(response).doesNotContain("ownerSubject", "attacker");
        }
        assertThat(created.get("csv")).isNull();
        assertThat(created.get("content")).isNull();
        assertThat(created.get("values")).isNull();
    }
}

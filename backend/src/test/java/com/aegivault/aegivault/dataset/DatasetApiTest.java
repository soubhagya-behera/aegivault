package com.aegivault.aegivault.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.identity.Role;
import com.aegivault.aegivault.identity.RoleRepository;
import com.aegivault.aegivault.identity.User;
import com.aegivault.aegivault.identity.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owner-scoped dataset API against real PostgreSQL. Ownership always comes
 * from the verified JWT subject; USER and ADMIN behave identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    private static String email() {
        return "dataset-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, "dataset-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"dataset-pass-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    private String grantAdminAndRelogin(String email) throws Exception {
        User user = users.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow();
        Role admin = roles.findByName("ADMIN").orElseThrow();
        user.getRoles().add(admin);
        users.saveAndFlush(user);
        return login(email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private JsonNode create(String token, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }


    @Test
    void createReturns201WithLocationAndUploadedStatus() throws Exception {
        String token = register(email());

        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"customers.csv\",\"originalFilename\":\"customers.csv\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("customers.csv"))
                .andExpect(jsonPath("$.sourceType").value("CSV"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.originalFilename").value("customers.csv"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/datasets/" + id);
    }

    @Test
    void ownerSubjectIsDerivedFromJwtSubject() throws Exception {
        String token = register(email());
        String subject = jwtDecoder.decode(token).getSubject();

        JsonNode created = create(token, "{\"name\":\"owned.csv\"}");

        Dataset stored = datasets.findById(UUID.fromString(created.get("id").asText()))
                .orElseThrow();
        assertThat(stored.getOwnerSubject()).isEqualTo(subject);
    }

    @Test
    void attemptedOwnerOverrideIsIgnored() throws Exception {
        String token = register(email());
        String subject = jwtDecoder.decode(token).getSubject();

        JsonNode created = create(token,
                "{\"name\":\"override.csv\",\"ownerSubject\":\"attacker\",\"status\":\"READY\"}");

        Dataset stored = datasets.findById(UUID.fromString(created.get("id").asText()))
                .orElseThrow();
        assertThat(stored.getOwnerSubject()).isEqualTo(subject);
        assertThat(stored.getStatus()).isEqualTo("UPLOADED");
    }

    @Test
    void clientCannotControlStatusSourceTypeOrRowCount() throws Exception {
        String token = register(email());

        JsonNode created = create(token,
                "{\"name\":\"locked.csv\",\"status\":\"READY\",\"sourceType\":\"CSV\",\"rowCount\":999}");

        assertThat(created.get("status").asText()).isEqualTo("UPLOADED");
        assertThat(created.get("sourceType").asText()).isEqualTo("CSV");
        assertThat(created.get("rowCount").isNull()).isTrue();
        Dataset stored = datasets.findById(UUID.fromString(created.get("id").asText()))
                .orElseThrow();
        assertThat(stored.getRowCount()).isNull();
    }

    @Test
    void eachUserListsOnlyTheirOwnDatasets() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());

        create(tokenA, "{\"name\":\"a-one.csv\"}");
        create(tokenA, "{\"name\":\"a-two.csv\"}");
        create(tokenB, "{\"name\":\"b-one.csv\"}");

        MvcResult listA = mvc.perform(get("/api/datasets")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult listB = mvc.perform(get("/api/datasets")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();

        List<String> namesA = new ArrayList<>();
        objectMapper.readTree(listA.getResponse().getContentAsString())
                .forEach(node -> namesA.add(node.get("name").asText()));
        List<String> namesB = new ArrayList<>();
        objectMapper.readTree(listB.getResponse().getContentAsString())
                .forEach(node -> namesB.add(node.get("name").asText()));
        namesA.sort(String::compareTo);
        namesB.sort(String::compareTo);
        assertThat(namesA).containsExactly("a-one.csv", "a-two.csv");
        assertThat(namesB).containsExactly("b-one.csv");
    }

    @Test
    void userCanRetrieveOwnDataset() throws Exception {
        String token = register(email());
        String id = create(token, "{\"name\":\"mine.csv\"}").get("id").asText();

        mvc.perform(get("/api/datasets/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("mine.csv"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }

    @Test
    void anotherUsersDatasetAndMissingDatasetReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String othersId = create(tokenA, "{\"name\":\"not-yours.csv\"}").get("id").asText();
        String missingId = UUID.randomUUID().toString();

        String otherBody = mvc.perform(
                        get("/api/datasets/" + othersId)
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missingBody = mvc.perform(
                        get("/api/datasets/" + missingId)
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(otherBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(otherBody).get("message").asText())
                .isEqualTo("Dataset not found.");
    }

    @Test
    void unauthenticatedAndInvalidTokensAreRejected() throws Exception {
        String token = register(email());
        String id = create(token, "{\"name\":\"guarded.csv\"}").get("id").asText();

        mvc.perform(post("/api/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"no-auth.csv\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/datasets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/datasets/" + id)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/datasets").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/datasets/" + id).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "n".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(
                        get("/api/datasets/not-a-uuid")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("Invalid dataset id.");
    }

    @Test
    void adminBehavesLikeUserWithNoCrossAccess() throws Exception {
        String adminEmail = email();
        register(adminEmail);
        String adminToken = grantAdminAndRelogin(adminEmail);
        String userToken = register(email());

        String adminDatasetId = create(adminToken, "{\"name\":\"admin-own.csv\"}")
                .get("id").asText();
        String userDatasetId = create(userToken, "{\"name\":\"user-own.csv\"}")
                .get("id").asText();

        mvc.perform(get("/api/datasets/" + adminDatasetId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/datasets/" + userDatasetId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void findByIdAndOwnerSubjectIsOwnerScoped() throws Exception {
        String tokenA = register(email());
        String subjectA = jwtDecoder.decode(tokenA).getSubject();
        String tokenB = register(email());
        String subjectB = jwtDecoder.decode(tokenB).getSubject();

        UUID id = UUID.fromString(
                create(tokenA, "{\"name\":\"scoped.csv\"}").get("id").asText());

        assertThat(datasets.findByIdAndOwnerSubject(id, subjectA)).isPresent();
        assertThat(datasets.findByIdAndOwnerSubject(id, subjectB)).isEmpty();
        assertThat(datasets.findByIdAndOwnerSubject(UUID.randomUUID(), subjectA)).isEmpty();
    }

    @Test
    void serviceTrimsNameBeforePersistence() throws Exception {
        String token = register(email());

        JsonNode created = create(token, "{\"name\":\"  spaced.csv  \"}");

        assertThat(created.get("name").asText()).isEqualTo("spaced.csv");
        Dataset stored = datasets.findById(UUID.fromString(created.get("id").asText()))
                .orElseThrow();
        assertThat(stored.getName()).isEqualTo("spaced.csv");
    }
}

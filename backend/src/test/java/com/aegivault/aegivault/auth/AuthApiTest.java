package com.aegivault.aegivault.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end authentication against real PostgreSQL: registration issues a
 * token for a {@code USER}, duplicates are rejected, login succeeds only
 * with correct credentials (always the same 401 body otherwise), the JWT
 * carries the expected subject/roles/expiry, and {@code /me} requires a
 * bearer token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    private static String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, password, "Api Case"));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void registerIssuesJwtWithExpectedSubjectRolesAndExpiry() throws Exception {
        String email = email();
        String token = register(email, "register-pass-1");

        Jwt jwt = jwtDecoder.decode(token);
        JsonNode profile = objectMapper.readTree(
                mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(jwt.getSubject()).isEqualTo(profile.get("userId").asText());
        assertThat(jwt.getClaimAsString("email")).isEqualTo(email.toLowerCase());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(60));
        assertThat(profile.get("email").asText()).isEqualTo(email.toLowerCase());
    }

    @Test
    void registerNormalizesEmailToLowercase() throws Exception {
        String email = "MiXeD-" + UUID.randomUUID() + "@Example.COM";
        register(email, "register-pass-1");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toLowerCase() + "\",\"password\":\"register-pass-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = email();
        register(email, "register-pass-1");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"register-pass-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void loginSucceedsWithCorrectCredentials() throws Exception {
        String email = email();
        register(email, "login-pass-22");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"login-pass-22\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnSame401() throws Exception {
        String email = email();
        register(email, "login-pass-22");

        String wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + UUID.randomUUID()
                                + "@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail);
        assertThat(objectMapper.readTree(wrongPassword).get("message").asText())
                .isEqualTo("Invalid email or password.");
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        String token = register(email(), "register-pass-1");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").isNotEmpty());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }
}

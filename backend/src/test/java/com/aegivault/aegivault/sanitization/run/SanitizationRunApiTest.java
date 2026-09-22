package com.aegivault.aegivault.sanitization.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.dataset.Dataset;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
 * Owner-scoped run read endpoint against real PostgreSQL. Ownership comes
 * from the verified JWT subject; USER and ADMIN behave identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SanitizationRunApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private SanitizationRunExecutor executor;

    @Autowired
    private DatasetRepository datasets;

    private static String email() {
        return "run-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "run-api-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID completedRun(String token) {
        String subject = jwtDecoder.decode(token).getSubject();
        Dataset dataset = datasets.save(new Dataset("customers.csv", subject));
        SanitizationRunView view = executor.executeCsv(
                subject,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream("name,email\nbob,bob@example.com\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        return view.id();
    }

    private UUID failedRun(String token) {
        String subject = jwtDecoder.decode(token).getSubject();
        Dataset dataset = datasets.save(new Dataset("ragged.csv", subject));
        SanitizationRunView view = executor.executeCsv(
                subject,
                dataset.getId(),
                DefaultTransformationPolicy.plan(),
                "default",
                "v1",
                new ByteArrayInputStream("a,b\n1,2,3\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        return view.id();
    }

    @Test
    void ownerRetrievesCompletedRun() throws Exception {
        String token = register(email());
        UUID id = completedRun(token);

        MvcResult result = mvc.perform(get("/api/runs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.inputRowCount").value(1))
                .andExpect(jsonPath("$.outputRowCount").value(1))
                .andExpect(jsonPath("$.columnCount").value(2))
                .andExpect(jsonPath("$.policyName").value("default"))
                .andExpect(jsonPath("$.ownerSubject").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bob@example.com", "password", "Exception", "at com.aegivault");
    }

    @Test
    void ownerRetrievesFailedRun() throws Exception {
        String token = register(email());
        UUID id = failedRun(token);

        mvc.perform(get("/api/runs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("CSV_PARSE_ERROR"))
                .andExpect(jsonPath("$.errorStage").value("TOKENIZE"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }

    @Test
    void foreignAndMissingRunsReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        UUID foreignId = completedRun(tokenA);
        String missingId = UUID.randomUUID().toString();

        String foreignBody = mvc.perform(
                        get("/api/runs/" + foreignId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(
                        get("/api/runs/" + missingId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo("Sanitization run not found.");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        String token = register(email());
        UUID id = completedRun(token);

        mvc.perform(get("/api/runs/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/runs/" + id).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(get("/api/runs/not-a-uuid").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Invalid run id.");
    }
}

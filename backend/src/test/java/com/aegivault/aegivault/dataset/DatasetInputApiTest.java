package com.aegivault.aegivault.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import java.io.InputStream;
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
 * Owner-scoped CSV input upload against real PostgreSQL. The endpoint
 * stores raw bytes only — no parsing, no detection, no execution — and the
 * stored bytes are verified through the storage boundary, never through an
 * API body.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetInputApiTest {

    private static final String CSV = "name,email\nbob,bob@example.com\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private DatabaseDatasetInputSource inputs;

    private static String email() {
        return "input-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "input-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String createDataset(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/datasets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private MvcResult upload(String token, String id, byte[] body) throws Exception {
        return mvc.perform(post("/api/datasets/" + id + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(body))
                .andReturn();
    }

    private static String stored(DatabaseDatasetInputSource inputs, String token, JwtDecoder decoder, String id)
            throws Exception {
        String subject = decoder.decode(token).getSubject();
        try (InputStream open = inputs.openInput(subject, UUID.fromString(id))) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ownerUploadsValidCsv() throws Exception {
        String token = register(email());
        String id = createDataset(token, "customers.csv");

        MvcResult result = upload(token, id, CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("datasetId").asText())
                .isEqualTo(id);
        assertThat(stored(inputs, token, jwtDecoder, id)).isEqualTo(CSV);
    }

    @Test
    void reuploadReplacesExistingInput() throws Exception {
        String token = register(email());
        String id = createDataset(token, "customers.csv");
        upload(token, id, CSV.getBytes(StandardCharsets.UTF_8));

        upload(token, id, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));

        assertThat(stored(inputs, token, jwtDecoder, id)).isEqualTo("a,b\n1,2\n");
    }

    @Test
    void foreignAndMissingDatasetsReturnSame404() throws Exception {
        String tokenA = register(email());
        String tokenB = register(email());
        String foreignId = createDataset(tokenA, "not-yours.csv");
        String missingId = UUID.randomUUID().toString();

        String foreignBody = mvc.perform(post("/api/datasets/" + foreignId + "/input")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("text/csv")
                        .content(CSV.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missingBody = mvc.perform(post("/api/datasets/" + missingId + "/input")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("text/csv")
                        .content(CSV.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignBody).isEqualTo(missingBody);
        assertThat(objectMapper.readTree(foreignBody).get("message").asText())
                .isEqualTo("Dataset not found.");
    }

    @Test
    void unauthenticatedUploadIsRejected() throws Exception {
        String token = register(email());
        String id = createDataset(token, "guarded.csv");

        mvc.perform(post("/api/datasets/" + id + "/input")
                        .contentType("text/csv")
                        .content(CSV.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/datasets/" + id + "/input")
                        .header("Authorization", "Bearer not-a-token")
                        .contentType("text/csv")
                        .content(CSV.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        String token = register(email());

        String body = mvc.perform(post("/api/datasets/not-a-uuid/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(CSV.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).isEqualTo("Invalid dataset id.");
    }

    @Test
    void oversizedInputIsRejectedWithoutReplacing() throws Exception {
        String token = register(email());
        String id = createDataset(token, "customers.csv");
        upload(token, id, CSV.getBytes(StandardCharsets.UTF_8));
        byte[] huge = new byte[10 * 1024 * 1024 + 1];

        String body = mvc.perform(post("/api/datasets/" + id + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("message").asText()).contains("10485760");
        assertThat(stored(inputs, token, jwtDecoder, id)).isEqualTo(CSV);
    }

    @Test
    void emptyInputFollowsStorageContract() throws Exception {
        String token = register(email());
        String id = createDataset(token, "empty.csv");

        mvc.perform(post("/api/datasets/" + id + "/input")
                        .header("Authorization", "Bearer " + token)
                        .contentType("text/csv")
                        .content(new byte[0]))
                .andExpect(status().isOk());

        assertThat(stored(inputs, token, jwtDecoder, id)).isEmpty();
    }

    @Test
    void responseExposesNoBytesOrOwner() throws Exception {
        String token = register(email());
        String id = createDataset(token, "customers.csv");

        MvcResult result = upload(token, id, CSV.getBytes(StandardCharsets.UTF_8));

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).doesNotContain("bob@example.com", "ownerSubject");
        mvc.perform(get("/api/datasets/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }
}

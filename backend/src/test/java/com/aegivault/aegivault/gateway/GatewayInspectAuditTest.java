package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.identity.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Gateway inspection audit events against real PostgreSQL. Every
 * successfully inspected request appends exactly one entry to the
 * existing tamper-evident ledger — actor from the JWT, resource id from
 * the server-generated request id, metadata-only event data — while
 * requests that never reach inspection append nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayInspectAuditTest {

    // Synthetic fixtures only: fragments are joined at runtime so no
    // complete credential-like literal ever appears in this source file.
    private static final String EMAIL = "alice@example.com";

    private static final String SYNTHETIC_KEY = "s" + "k-" + "abcdefghijklmnopqrstuvwxyz1234567890ABCD";

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    @Autowired
    private UserRepository users;

    private static String email() {
        return "gateway-audit-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private static String inspectBody(String model, String content) {
        return "{\"model\":\"" + model + "\",\"content\":\"" + content + "\"}";
    }

    private Set<UUID> entryIds() {
        return ledger.findAll().stream().map(AuditLedgerEntry::getId).collect(Collectors.toSet());
    }

    private List<AuditLedgerEntry> newEntries(Set<UUID> before) {
        return ledger.findAll().stream()
                .filter(entry -> !before.contains(entry.getId()))
                .toList();
    }

    private String actorFor(String email) {
        return users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElseThrow().getId().toString();
    }

    private void assertSafeEventData(AuditLedgerEntry entry, String content, String token) throws Exception {
        String eventData = entry.getEventData();
        JsonNode data = objectMapper.readTree(eventData);
        assertThat(data.propertyNames())
                .containsExactlyInAnyOrder("model", "verdict", "reasons", "detectedPiiTypes");
        assertThat(eventData)
                .doesNotContain(content, EMAIL, SYNTHETIC_KEY, token, entry.getActorSubject(), "actorSubject");
    }

    @Test
    void cleanRequestAppendsExactlyOneAllowedEvent() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", CLEAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ALLOW"));

        List<AuditLedgerEntry> created = newEntries(before);
        assertThat(created).hasSize(1);
        AuditLedgerEntry entry = created.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventData.GATEWAY_INSPECTION_ALLOWED);
        assertThat(entry.getActorSubject()).isEqualTo(actorFor(userEmail));
        assertThat(entry.getResourceType()).isEqualTo(AuditEventData.AI_GATEWAY_INSPECTION_RESOURCE);
        assertThat(entry.getResourceId()).isNotNull();
        JsonNode data = objectMapper.readTree(entry.getEventData());
        assertThat(data.get("model").asText()).isEqualTo("local-test-model");
        assertThat(data.get("verdict").asText()).isEqualTo("ALLOW");
        assertThat(data.get("reasons")).isEmpty();
        assertThat(data.get("detectedPiiTypes")).isEmpty();
        assertSafeEventData(entry, CLEAN, token);
    }

    @Test
    void piiRequestAppendsExactlyOneBlockedEvent() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String content = "contact " + EMAIL + " for access.";
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"));

        List<AuditLedgerEntry> created = newEntries(before);
        assertThat(created).hasSize(1);
        AuditLedgerEntry entry = created.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventData.GATEWAY_INSPECTION_BLOCKED);
        assertThat(entry.getActorSubject()).isEqualTo(actorFor(userEmail));
        assertThat(entry.getResourceType()).isEqualTo(AuditEventData.AI_GATEWAY_INSPECTION_RESOURCE);
        assertThat(entry.getResourceId()).isNotNull();
        JsonNode data = objectMapper.readTree(entry.getEventData());
        assertThat(data.get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(data.get("reasons").get(0).asText()).isEqualTo("PII_DETECTED");
        assertThat(data.get("detectedPiiTypes").get(0).asText()).isEqualTo("EMAIL");
        assertSafeEventData(entry, content, token);
    }

    @Test
    void secretRequestAppendsExactlyOneBlockedEvent() throws Exception {
        String userEmail = email();
        String token = register(userEmail);
        String content = "use key " + SYNTHETIC_KEY + " for deploy.";
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"));

        List<AuditLedgerEntry> created = newEntries(before);
        assertThat(created).hasSize(1);
        AuditLedgerEntry entry = created.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventData.GATEWAY_INSPECTION_BLOCKED);
        assertThat(entry.getActorSubject()).isEqualTo(actorFor(userEmail));
        assertThat(entry.getResourceId()).isNotNull();
        JsonNode data = objectMapper.readTree(entry.getEventData());
        assertThat(data.get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(data.get("reasons").get(0).asText()).isEqualTo("PII_DETECTED");
        assertThat(data.get("reasons").get(1).asText()).isEqualTo("SECRET_DETECTED");
        assertSafeEventData(entry, content, token);
    }

    @Test
    void combinedRequestAppendsOneBlockedEventWithDeterministicMetadata() throws Exception {
        String token = register(email());
        String content = "contact " + EMAIL + " with key " + SYNTHETIC_KEY + ".";
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCK"));

        List<AuditLedgerEntry> created = newEntries(before);
        assertThat(created).hasSize(1);
        JsonNode data = objectMapper.readTree(created.get(0).getEventData());
        List<String> reasons = new java.util.ArrayList<>();
        data.get("reasons").forEach(node -> reasons.add(node.asText()));
        List<String> types = new java.util.ArrayList<>();
        data.get("detectedPiiTypes").forEach(node -> types.add(node.asText()));
        assertThat(reasons).containsExactly("PII_DETECTED", "SECRET_DETECTED");
        assertThat(types).containsExactly("API_KEY", "EMAIL");
        assertSafeEventData(created.get(0), content, token);
    }

    @Test
    void repeatedInspectionsCreateSeparateEventsWithSeparateRequestIds() throws Exception {
        String token = register(email());
        String body = inspectBody("local-test-model", "contact " + EMAIL + " for access.");
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        List<AuditLedgerEntry> created = newEntries(before);
        assertThat(created).hasSize(2);
        assertThat(created.get(0).getResourceId()).isNotNull();
        assertThat(created.get(1).getResourceId()).isNotNull();
        assertThat(created.get(0).getResourceId()).isNotEqualTo(created.get(1).getResourceId());
        // Same request, same decision: identical metadata, distinct ids.
        assertThat(created.get(0).getEventData()).isEqualTo(created.get(1).getEventData());
    }

    @Test
    void unauthenticatedRequestAppendsNoEvent() throws Exception {
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", CLEAN)))
                .andExpect(status().isUnauthorized());

        assertThat(newEntries(before)).isEmpty();
    }

    @Test
    void validationFailureAppendsNoEvent() throws Exception {
        String token = register(email());
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(newEntries(before)).isEmpty();
    }

    @Test
    void oversizedRequestAppendsNoEvent() throws Exception {
        String token = register(email());
        Set<UUID> before = entryIds();

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody(
                                "local-test-model", "b".repeat(GatewayInspectRequest.MAX_CONTENT_LENGTH + 1))))
                .andExpect(status().isBadRequest());

        assertThat(newEntries(before)).isEmpty();
    }

    @Test
    void ledgerStillVerifiesAfterGatewayEvents() throws Exception {
        String token = register(email());

        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", CLEAN)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/gateway/inspect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inspectBody("local-test-model", "contact " + EMAIL + " for access.")))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/audit/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("valid").asBoolean()).isTrue();
    }
}

package com.aegivault.aegivault.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.identity.Role;
import com.aegivault.aegivault.identity.RoleRepository;
import com.aegivault.aegivault.identity.User;
import com.aegivault.aegivault.identity.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated ledger-verification endpoint against real PostgreSQL:
 * {@code GET /api/audit/verify} replays the shared chain and reports a
 * safe verdict — verdict plus replayed-entry count plus (only when
 * invalid) the failure code, never ledger content. USER and ADMIN behave
 * identically because integrity is global, not per-owner.
 *
 * <p>State-mutating tests run in one rolled-back transaction each, so the
 * shared tables stay clean for the rest of the suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditVerifyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLedgerService ledger;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @PersistenceContext
    private EntityManager entities;

    private static String email() {
        return "audit-verify-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, "audit-verify-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"audit-verify-pass-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String grantAdminAndRelogin(String email) {
        User user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElseThrow();
        Role admin = roles.findByName("ADMIN").orElseThrow();
        user.getRoles().add(admin);
        users.saveAndFlush(user);
        try {
            return login(email.trim().toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalStateException("Admin relogin failed.", ex);
        }
    }

    private JsonNode verify(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/audit/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void append(String eventType, String actor) {
        ledger.append(eventType, actor, "DATASET", UUID.randomUUID(), "{\"rows\":1}");
    }

    @Test
    void authenticatedUserReceivesValidTrueOnIntactLedger() throws Exception {
        String token = register(email());
        append("DATASET_UPLOADED", "owner-1");
        append("DATASET_SANITIZED", "owner-1");

        JsonNode body = verify(token);

        assertThat(body.get("valid").asBoolean()).isTrue();
        assertThat(body.get("entriesChecked").asLong()).isGreaterThanOrEqualTo(2L);
        assertThat(body.get("failureCode")).isNull();
        assertThat(body.toString()).doesNotContain("failureCode");
    }

    @Test
    void authenticatedAdminReceivesValidTrue() throws Exception {
        String email = email();
        register(email);
        String admin = grantAdminAndRelogin(email);

        JsonNode body = verify(admin);

        assertThat(body.get("valid").asBoolean()).isTrue();
        assertThat(body.get("entriesChecked").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(body.get("failureCode")).isNull();
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(get("/api/audit/verify")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit/verify").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void emptyLedgerVerifiesSafely() throws Exception {
        String token = register(email());
        entities.createNativeQuery("DELETE FROM audit_ledger_entries").executeUpdate();
        entities.flush();
        entities.clear();

        JsonNode body = verify(token);

        assertThat(body.get("valid").asBoolean()).isTrue();
        assertThat(body.get("entriesChecked").asLong()).isZero();
        assertThat(body.get("failureCode")).isNull();
    }

    @Test
    @Transactional
    void entriesCheckedCountsReplayedEntries() throws Exception {
        String token = register(email());
        long before = verify(token).get("entriesChecked").asLong();
        append("DATASET_UPLOADED", "owner-1");
        append("DATASET_SANITIZED", "owner-1");
        append("RUN_COMPLETED", "owner-1");

        JsonNode body = verify(token);

        assertThat(body.get("valid").asBoolean()).isTrue();
        assertThat(body.get("entriesChecked").asLong()).isEqualTo(before + 3L);
    }

    @Test
    @Transactional
    void tamperedLedgerProducesValidFalseWithoutLeaking() throws Exception {
        String token = register(email());
        String actor = "tamper-victim-" + UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        ledger.append("DATASET_UPLOADED", actor, "DATASET", resource, "{\"rows\":1}");
        AuditLedgerEntryView second =
                ledger.append("DATASET_SANITIZED", actor, "DATASET", resource, "{\"rows\":1}");
        entities
                .createNativeQuery(
                        "UPDATE audit_ledger_entries SET event_data = '{\"rows\":999}'"
                                + " WHERE sequence_number = " + second.sequenceNumber())
                .executeUpdate();
        entities.flush();
        entities.clear();

        MvcResult result = mvc.perform(get("/api/audit/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String raw = result.getResponse().getContentAsString();

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("failureCode").asText()).isEqualTo("ENTRY_HASH_MISMATCH");
        assertThat(body.get("entriesChecked").asLong()).isGreaterThanOrEqualTo(2L);
        java.util.List<String> fields = new java.util.ArrayList<>(body.propertyNames());
        assertThat(fields).containsExactlyInAnyOrder("valid", "entriesChecked", "failureCode");
        assertThat(raw).doesNotContain(
                actor, resource.toString(), "{\"rows\":999}", "entryHash", "previousHash",
                "event_data", "eventData", "actorSubject", "Exception");
    }
}

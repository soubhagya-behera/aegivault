package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntry;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.gateway.GatewayAuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /api/gateway/policies/{policyId}/audit} at the HTTP boundary,
 * without a Spring context (standalone controller setup) and without a
 * database: the ledger repository is mocked while the query service and the
 * audit seam are real, so the response shape, the owner scoping, and the
 * event filtering are exercised exactly as the endpoint produces them.
 *
 * <p>Every test authenticates as its own JWT subject, so the actor scoping
 * is decided here rather than by test ordering.
 */
class GatewayUsagePolicyAuditHistoryApiTest {

    private static final String ACTOR = "history-actor";

    private static final UUID POLICY_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private MockMvc mvc;

    private AuditLedgerEntryRepository repository;

    @BeforeEach
    void setup() {
        repository = mock(AuditLedgerEntryRepository.class);
        historyIs(List.of());
        mvc = MockMvcBuilders
                .standaloneSetup(new GatewayUsagePolicyController(
                        mock(GatewayUsagePolicyService.class),
                        new GatewayAuditService(mock(AuditLedgerService.class)),
                        new GatewayUsagePolicyAuditQueryService(repository)))
                .setCustomArgumentResolvers(authenticatedJwt(ACTOR))
                .build();
    }

    /** Standalone stand-in for the JWT authentication principal (no security filter chain here). */
    private static HandlerMethodArgumentResolver authenticatedJwt(String subject) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .build();
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer container,
                    NativeWebRequest request,
                    WebDataBinderFactory factory) {
                return jwt;
            }
        };
    }

    /**
     * A committed ledger row as the query would read it. Mocked rather than
     * constructed because {@code id} and {@code createdAt} are assigned on
     * persist, which a hand-built entity never sees.
     */
    private static AuditLedgerEntry row(String eventType, String actor, UUID resourceId, String data) {
        AuditLedgerEntry row = mock(AuditLedgerEntry.class);
        when(row.getEventType()).thenReturn(eventType);
        when(row.getActorSubject()).thenReturn(actor);
        when(row.getResourceType()).thenReturn("GATEWAY_USAGE_POLICY");
        when(row.getResourceId()).thenReturn(resourceId);
        when(row.getEventData()).thenReturn(data);
        when(row.getCreatedAt()).thenReturn(Instant.parse("2026-03-01T10:15:30Z"));
        when(row.getId()).thenReturn(UUID.fromString("99999999-0000-0000-0000-000000000001"));
        when(row.getSequenceNumber()).thenReturn(1L);
        when(row.getEntryHash()).thenReturn("f".repeat(64));
        when(row.getPreviousHash()).thenReturn(AuditLedgerService.GENESIS_PREVIOUS_HASH);
        return row;
    }

    /**
     * Stubs the repository the way the real query behaves: it honours the
     * actor and the resource id, so a row belonging to another actor is never
     * returned no matter what the caller asked for.
     */
    private void historyIs(List<AuditLedgerEntry> rows) {
        when(repository.findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    String actor = invocation.getArgument(2);
                    UUID resourceId = invocation.getArgument(1);
                    return rows.stream()
                            .filter(row -> actor.equals(row.getActorSubject()))
                            .filter(row -> resourceId.equals(row.getResourceId()))
                            .toList();
                });
    }

    private ResultActions getHistory() throws Exception {
        return mvc.perform(get("/api/gateway/policies/" + POLICY_ID + "/audit"));
    }

    @Test
    void theOwnerSeesTheirOwnPolicyHistory() throws Exception {
        historyIs(List.of(
                row(AuditEventData.GATEWAY_USAGE_POLICY_DELETED, ACTOR, POLICY_ID, "{\"action\":\"DELETED\"}"),
                row(AuditEventData.GATEWAY_USAGE_POLICY_CREATED, ACTOR, POLICY_ID, "{\"action\":\"CREATED\"}")));

        getHistory()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].eventType")
                        .value(AuditEventData.GATEWAY_USAGE_POLICY_DELETED))
                .andExpect(jsonPath("$.entries[0].resourceId").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.entries[0].eventData").value("{\"action\":\"DELETED\"}"))
                .andExpect(jsonPath("$.entries[0].createdAt").exists());
    }

    @Test
    void theActorFilterComesFromTheJwtSubjectOnly() throws Exception {
        // A client-supplied actorSubject is not a bound parameter, so it
        // cannot widen the query: the JWT subject is the only actor input.
        mvc.perform(get("/api/gateway/policies/" + POLICY_ID + "/audit")
                        .param("actorSubject", "someone-else")
                        .param("actor", "someone-else")
                        .header("X-Actor-Subject", "someone-else"))
                .andExpect(status().isOk());

        verify(repository)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        eq("GATEWAY_USAGE_POLICY"), eq(POLICY_ID), eq(ACTOR), anyList());
    }

    @Test
    void aDeletedPolicyStillHasItsHistory() throws Exception {
        // Nothing consults the policy row: the ledger is the record, so the
        // create/update/delete trail survives the policy itself.
        historyIs(List.of(
                row(AuditEventData.GATEWAY_USAGE_POLICY_DELETED, ACTOR, POLICY_ID, "{\"action\":\"DELETED\"}"),
                row(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED, ACTOR, POLICY_ID, "{\"action\":\"UPDATED\"}"),
                row(AuditEventData.GATEWAY_USAGE_POLICY_CREATED, ACTOR, POLICY_ID, "{\"action\":\"CREATED\"}")));

        getHistory()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3));
    }

    @Test
    void anUnknownPolicyIdIsAnEmptyTwoHundred() throws Exception {
        historyIs(List.of());

        mvc.perform(get("/api/gateway/policies/" + UUID.randomUUID() + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void anotherActorsHistoryIsAnEmptyTwoHundredNotAFourOhFour() throws Exception {
        historyIs(List.of(row(
                AuditEventData.GATEWAY_USAGE_POLICY_CREATED, "other-actor", POLICY_ID, "{\"action\":\"CREATED\"}")));

        // A 404 here would confirm that someone else's policy exists; an
        // empty 200 reveals nothing and is the same answer as "never existed".
        getHistory()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());

        verify(repository)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        eq("GATEWAY_USAGE_POLICY"), eq(POLICY_ID), eq(ACTOR), anyList());
    }

    @Test
    void onlyTheFivePolicyEventTypesAreRequested() throws Exception {
        getHistory().andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> eventTypes =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository)
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), eventTypes.capture());
        // Inspection and unrelated events never reach a policy history.
        assertThat(eventTypes.getValue()).containsExactlyInAnyOrder(
                "GATEWAY_USAGE_POLICY_CREATED",
                "GATEWAY_USAGE_POLICY_UPDATED",
                "GATEWAY_USAGE_POLICY_DELETED",
                "GATEWAY_USAGE_POLICY_ALLOWED",
                "GATEWAY_USAGE_POLICY_REJECTED");
    }

    @Test
    void theResponseExposesNoHashesSequenceNumberOrActor() throws Exception {
        AuditLedgerEntry entry = row(
                AuditEventData.GATEWAY_USAGE_POLICY_UPDATED, ACTOR, POLICY_ID, "{\"action\":\"UPDATED\"}");
        historyIs(List.of(entry));

        String body = getHistory()
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Exactly the four safe fields, and none of the ledger's internals.
        assertThat(body)
                .doesNotContain(entry.getEntryHash())
                .doesNotContain(entry.getPreviousHash())
                .doesNotContain(AuditLedgerService.GENESIS_PREVIOUS_HASH)
                .doesNotContain("sequenceNumber")
                .doesNotContain("previousHash")
                .doesNotContain("entryHash")
                .doesNotContain("actorSubject")
                .doesNotContain(ACTOR);
    }

    @Test
    void eachEntryCarriesExactlyTheFourSafeFields() throws Exception {
        historyIs(List.of(row(
                AuditEventData.GATEWAY_USAGE_POLICY_UPDATED, ACTOR, POLICY_ID, "{\"action\":\"UPDATED\"}")));

        String body = getHistory()
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode first = new ObjectMapper().readTree(body).get("entries").get(0);
        assertThat(new ArrayList<>(first.propertyNames()))
                .containsExactlyInAnyOrder("eventType", "resourceId", "eventData", "createdAt");
    }

    @Test
    void theNewestHundredEntriesAreBounded() throws Exception {
        List<AuditLedgerEntry> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(row(
                    AuditEventData.GATEWAY_USAGE_POLICY_UPDATED, ACTOR, POLICY_ID, "{\"action\":\"UPDATED\"}"));
        }
        historyIs(rows);

        // The bound is applied in the database query, so the endpoint never
        // trims a longer list in Java and never returns more than it was given.
        getHistory()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(100));
    }

    @Test
    void theHistoryEndpointIsReadOnlyAndWritesNoLedgerEntry() throws Exception {
        historyIs(List.of(row(
                AuditEventData.GATEWAY_USAGE_POLICY_CREATED, ACTOR, POLICY_ID, "{\"action\":\"CREATED\"}")));

        getHistory().andExpect(status().isOk());

        // Reading history appends nothing, rewrites nothing, deletes nothing.
        verify(repository, times(1))
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), anyList());
        verify(repository, never()).save(any(AuditLedgerEntry.class));
        verify(repository, never()).saveAndFlush(any(AuditLedgerEntry.class));
        verify(repository, never()).delete(any(AuditLedgerEntry.class));
        verify(repository, never()).deleteAll();
    }

    @Test
    void aMalformedPolicyIdIsRejectedBeforeAnyQuery() throws Exception {
        mvc.perform(get("/api/gateway/policies/not-a-uuid/audit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verify(repository, never())
                .findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), anyList());
    }

    @Test
    void theEventDataIsPassedThroughVerbatim() throws Exception {
        // A runtime enforcement entry keeps its own metadata, unchanged: the
        // history layer neither rewrites nor enriches the stored document.
        String stored = "{\"decision\":\"REJECTED\",\"rejectedWindow\":\"MINUTE\"}";
        historyIs(List.of(row(
                AuditEventData.GATEWAY_USAGE_POLICY_REJECTED, ACTOR, POLICY_ID, stored)));

        getHistory()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].eventType")
                        .value(AuditEventData.GATEWAY_USAGE_POLICY_REJECTED))
                .andExpect(jsonPath("$.entries[0].eventData").value(stored));
    }
}

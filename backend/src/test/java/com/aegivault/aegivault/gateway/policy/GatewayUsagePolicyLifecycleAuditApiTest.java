package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditEventData;
import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.audit.AuditLedgerService;
import com.aegivault.aegivault.gateway.GatewayAuditService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Gateway usage policy lifecycle auditing at the HTTP boundary, without a
 * Spring context (standalone controller setup, so no database pool and no
 * application context) and without a database: the policy service is a mock
 * standing in for the mutation, while the audit seam is the real
 * {@link GatewayAuditService} over a mocked ledger.
 *
 * <p>These pin the integration contract the endpoints must keep: the event is
 * appended only after the mutation returns, exactly once per successful
 * create / update / delete; a rejected, foreign, or missing policy appends
 * nothing; an append failure is a fail-closed generic 500 that compensates
 * nothing; and the ordering is mutation first, evidence second.
 */
class GatewayUsagePolicyLifecycleAuditApiTest {

    private static final String ACTOR = "lifecycle-actor-subject";

    private static final UUID POLICY_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static final String POLICY_BODY =
            "{\"name\":\"audited-policy\",\"description\":\"secret cap\","
                    + "\"requestsPerMinute\":60,\"requestsPerDay\":10000,\"tokensPerDay\":1000000}";

    private MockMvc mvc;

    private GatewayUsagePolicyService policies;

    private AuditLedgerService ledger;

    @BeforeEach
    void setup() {
        policies = mock(GatewayUsagePolicyService.class);
        ledger = mock(AuditLedgerService.class);
        // The read-only history dependency is the real query service over the
        // same mocked ledger, so mutations and reads are both exercised here
        // without a Spring context or a database.
        mvc = MockMvcBuilders
                .standaloneSetup(new GatewayUsagePolicyController(
                        policies,
                        new GatewayAuditService(ledger),
                        new GatewayUsagePolicyAuditQueryService(ledgerRepository())))
                .setCustomArgumentResolvers(authenticatedJwt())
                .build();
    }

    /** The repository seam behind the real query service. */
    private AuditLedgerEntryRepository ledgerRepository() {
        AuditLedgerEntryRepository repository = mock(AuditLedgerEntryRepository.class);
        when(repository.findTop100ByResourceTypeAndResourceIdAndActorSubjectAndEventTypeInOrderByCreatedAtDescSequenceNumberDesc(
                        anyString(), any(), anyString(), anyList()))
                .thenReturn(List.of());
        return repository;
    }

    /** Standalone stand-in for the JWT authentication principal (no security filter chain here). */
    private static HandlerMethodArgumentResolver authenticatedJwt() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(ACTOR)
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

    private static GatewayUsagePolicyResponse stored(String name) {
        Instant now = Instant.now();
        return new GatewayUsagePolicyResponse(
                POLICY_ID, name, "secret cap", 60L, 10000L, 1000000L, true, now, now);
    }

    private void policyExists() {
        when(policies.create(eq(ACTOR), anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(stored("audited-policy"));
        when(policies.update(
                        eq(ACTOR), eq(POLICY_ID), anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(stored("audited-policy"));
    }

    /** The single appended event type, failing when there was not exactly one. */
    private String singleAppendedEventType() {
        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(1)).append(
                eventType.capture(), anyString(), any(), any(), anyString());
        return eventType.getValue();
    }

    private String singleAppendedEventData() {
        ArgumentCaptor<String> eventData = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(1)).append(anyString(), anyString(), any(), any(), eventData.capture());
        return eventData.getValue();
    }

    @Test
    void aSuccessfulCreateWritesExactlyOneCreatedEvent() throws Exception {
        policyExists();

        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()));

        assertThat(singleAppendedEventType())
                .isEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_CREATED);
        assertThat(singleAppendedEventData()).isEqualTo("{\"action\":\"CREATED\"}");
    }

    @Test
    void aSuccessfulUpdateWritesExactlyOneUpdatedEvent() throws Exception {
        policyExists();

        mvc.perform(put("/api/gateway/policies/" + POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()));

        assertThat(singleAppendedEventType())
                .isEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_UPDATED);
        assertThat(singleAppendedEventData()).isEqualTo("{\"action\":\"UPDATED\"}");
    }

    @Test
    void aSuccessfulDeleteWritesExactlyOneDeletedEvent() throws Exception {
        mvc.perform(delete("/api/gateway/policies/" + POLICY_ID)).andExpect(status().isNoContent());

        assertThat(singleAppendedEventType())
                .isEqualTo(AuditEventData.GATEWAY_USAGE_POLICY_DELETED);
        assertThat(singleAppendedEventData()).isEqualTo("{\"action\":\"DELETED\"}");
    }

    @Test
    void theLifecycleEventNamesThePolicyAndTheAuthenticatedActor() throws Exception {
        policyExists();

        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> actorSubject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resourceId = ArgumentCaptor.forClass(UUID.class);
        verify(ledger).append(
                anyString(), actorSubject.capture(), resourceType.capture(), resourceId.capture(), any());
        // The actor is the authenticated JWT subject, in its own column only.
        assertThat(actorSubject.getValue()).isEqualTo(ACTOR);
        assertThat(resourceType.getValue()).isEqualTo("GATEWAY_USAGE_POLICY");
        assertThat(resourceId.getValue()).isEqualTo(POLICY_ID);
    }

    @Test
    void theEventCarriesNoPolicyContent() throws Exception {
        policyExists();

        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isCreated());

        // Not the label, the description, the limits, the enabled switch, the
        // owner, or the request body: the ledger proves the change happened.
        String data = singleAppendedEventData();
        assertThat(data)
                .isEqualTo("{\"action\":\"CREATED\"}")
                .doesNotContain("audited-policy")
                .doesNotContain("secret cap")
                .doesNotContain("10000")
                .doesNotContain("1000000")
                .doesNotContain(ACTOR)
                .doesNotContain("ownerSubject")
                .doesNotContain("enabled");
    }

    @Test
    void aValidationFailureWritesNoLifecycleEvent() throws Exception {
        when(policies.create(eq(ACTOR), anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("policy must define at least one limit"));

        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"n\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest());

        // The mutation failed, so there is no change to evidence.
        verifyNoInteractions(ledger);
    }

    @Test
    void aForeignOrMissingUpdateWritesNoLifecycleEvent() throws Exception {
        when(policies.update(
                        eq(ACTOR), eq(POLICY_ID), anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new GatewayUsagePolicyNotFoundException());

        mvc.perform(put("/api/gateway/policies/" + POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isNotFound());

        verifyNoInteractions(ledger);
    }

    @Test
    void aForeignOrMissingDeleteWritesNoLifecycleEvent() throws Exception {
        doThrow(new GatewayUsagePolicyNotFoundException()).when(policies).delete(ACTOR, POLICY_ID);

        mvc.perform(delete("/api/gateway/policies/" + POLICY_ID)).andExpect(status().isNotFound());

        verifyNoInteractions(ledger);
    }

    @Test
    void theMutationIsCommittedBeforeTheEventIsAppended() throws Exception {
        policyExists();

        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isCreated());

        // Durability ordering: the policy is persisted first, its evidence
        // second. Never an event for an operation that ultimately failed.
        InOrder order = inOrder(policies, ledger);
        order.verify(policies).create(eq(ACTOR), anyString(), any(), any(), any(), any(), anyBoolean());
        order.verify(ledger).append(anyString(), anyString(), any(), any(), anyString());
    }

    /** Makes the real audit seam fail the way a broken ledger would. */
    private void ledgerAppendFails() {
        when(ledger.append(anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("psql-connection-reset-4f"));
    }

    @Test
    void anAuditFailureOnCreateIsAGeneric500WithNoLeakedDetail() throws Exception {
        policyExists();
        ledgerAppendFails();

        mvc.perform(post("/api/gateway/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("Unable to record gateway policy audit event."));

        // Nothing about the failure escapes: no SQL text, hash, actor, or id.
        assertThat(mvc.perform(post("/api/gateway/policies")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(POLICY_BODY))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("psql-connection-reset")
                .doesNotContain(ACTOR)
                .doesNotContain(POLICY_ID.toString())
                .doesNotContain("audited-policy")
                .doesNotContain("secret cap");
    }

    @Test
    void anAuditFailureOnUpdateIsAGeneric500() throws Exception {
        policyExists();
        ledgerAppendFails();

        mvc.perform(put("/api/gateway/policies/" + POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(POLICY_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("Unable to record gateway policy audit event."));
    }

    @Test
    void anAuditFailureOnDeleteIsAGeneric500AndNeverResurrectsThePolicy() throws Exception {
        ledgerAppendFails();

        mvc.perform(delete("/api/gateway/policies/" + POLICY_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("Unable to record gateway policy audit event."));

        // The delete already committed. No compensation transaction exists:
        // the policy is never re-created to make its audit entry succeed.
        verify(policies, never()).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(policies, times(1)).delete(ACTOR, POLICY_ID);
    }
}

package com.aegivault.aegivault.dataset.postgres.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Standalone MockMvc tests for the binding deletion endpoint's HTTP contract:
 * success, ownership, the active-run guard mapping, and the fact that the
 * actor comes only from the token.
 *
 * <p><strong>No Spring context.</strong> {@code standaloneSetup} with a mocked
 * service, so no application context and no datasource pool are added — which
 * matters given the repository's prior PostgreSQL connection pressure.
 *
 * <p>Which run statuses and source types block deletion is the service's
 * decision and is pinned by its own unit tests plus the real-database
 * integration tests; here the service is a mock, and only the web contract —
 * {@code 204} on success, {@code 404} for foreign/missing/unbound, {@code 409}
 * with the fixed safe message while an active run exists — is under test.
 */
class PostgresDatasetBindingDeleteControllerTest {

    private static final String SUBJECT = "owner-1";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-0000000006d1");

    private static final String PATH = "/api/datasets/" + DATASET_ID + "/postgres/binding";

    private PostgresDatasetBindingService bindings;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bindings = mock(PostgresDatasetBindingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PostgresDatasetBindingController(bindings))
                .setCustomArgumentResolvers(new PrincipalArgumentResolver())
                .build();
    }

    /** Supplies the verified principal, standing in for the security filter. */
    private static final class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(
                    org.springframework.security.core.annotation.AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer container,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return Jwt.withTokenValue("token").header("alg", "none").claim("sub", SUBJECT).build();
        }
    }

    @Test
    void theOwnerDeletesAnInactiveBindingAndGetsTwoHundredAndFour() throws Exception {
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH)).andExpect(status().isNoContent());

        verify(bindings).delete(SUBJECT, DATASET_ID);
    }

    @Test
    void aForeignBindingIsTheSameGenericNotFound() throws Exception {
        doThrow(new PostgresDatasetBindingNotFoundException())
                .when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aMissingDatasetIsTheSameGenericNotFound() throws Exception {
        doThrow(new DatasetNotFoundException())
                .when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void anUnboundDatasetIsThatSameGenericNotFound() throws Exception {
        // Not an empty object and never a guessed CSV state.
        doThrow(new PostgresDatasetBindingNotFoundException())
                .when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.schemaName").doesNotExist())
                .andExpect(jsonPath("$.tableName").doesNotExist());
    }

    @Test
    void aMalformedDatasetIdIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(delete("/api/datasets/not-a-uuid/postgres/binding"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid dataset id."));

        verifyNoInteractions(bindings);
    }

    @Test
    void anActiveQueuedPostgresRunBlocksDeletionWithAConflict() throws Exception {
        doThrow(new PostgresDatasetBindingActiveRunException())
                .when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(PostgresDatasetBindingActiveRunException.MESSAGE))
                .andExpect(jsonPath("$.message").value(
                        "PostgreSQL dataset binding cannot be deleted while a sanitization run is active."));
    }

    @Test
    void anActiveRunningPostgresRunBlocksDeletionWithTheSameConflict() throws Exception {
        // The service does not distinguish which active status matched; the
        // controller maps the guard to one fixed 409 either way.
        doThrow(new PostgresDatasetBindingActiveRunException())
                .when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("PostgreSQL dataset binding cannot be deleted while a sanitization run is active."));
    }

    @Test
    void aCompletedPostgresRunDoesNotBlockDeletion() throws Exception {
        // Terminal runs never reach the guard exception: the service deletes
        // and the controller answers 204.
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH)).andExpect(status().isNoContent());

        verify(bindings).delete(SUBJECT, DATASET_ID);
    }

    @Test
    void aFailedPostgresRunDoesNotBlockDeletion() throws Exception {
        // Failed is terminal like completed: the service deletes and the
        // controller answers 204.
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH)).andExpect(status().isNoContent());

        verify(bindings).delete(SUBJECT, DATASET_ID);
    }

    @Test
    void aCsvRunDoesNotBlockPostgresBindingDeletion() throws Exception {
        // The guard counts POSTGRESQL runs only, so a CSV run — even a queued
        // one — surfaces here as a successful deletion.
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        mvc.perform(delete(PATH)).andExpect(status().isNoContent());

        verify(bindings).delete(SUBJECT, DATASET_ID);
    }

    @Test
    void theDeleteActorComesOnlyFromTheTokenAndOwnerHintsAreIgnored() throws Exception {
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        // Deletion takes no body, so the only channels a caller could try to
        // name a different actor are query parameters and headers.
        mvc.perform(delete(PATH)
                        .param("ownerSubject", "someone-else")
                        .param("actorSubject", "someone-else")
                        .header("X-Owner-Subject", "someone-else"))
                .andExpect(status().isNoContent());

        // Only the token subject reached the service.
        verify(bindings).delete(SUBJECT, DATASET_ID);
    }

    @Test
    void anOwnerSubjectBodyOrHeaderCannotOverrideTheToken() throws Exception {
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        // Even a body smuggling an owner field changes nothing: the endpoint
        // declares no body and the owner argument is the token subject.
        mvc.perform(delete(PATH)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ownerSubject\":\"someone-else\"}")
                        .header("X-Owner-Subject", "someone-else"))
                .andExpect(status().isNoContent());

        verify(bindings).delete(SUBJECT, DATASET_ID);
    }

    @Test
    void theResponseBodyIsEmptyOnTwoHundredAndFour() throws Exception {
        doNothing().when(bindings).delete(anyString(), eq(DATASET_ID));

        String body = mvc.perform(delete(PATH))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEmpty();
    }

    @Test
    void theSafeConflictMessageContainsNoRunPolicyOrTableDetail() throws Exception {
        doThrow(new PostgresDatasetBindingActiveRunException())
                .when(bindings).delete(anyString(), eq(DATASET_ID));

        String body = mvc.perform(delete(PATH))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("PostgreSQL dataset binding cannot be deleted while a sanitization run is active.")
                .doesNotContain(SUBJECT)
                .doesNotContain("ownerSubject")
                .doesNotContain("actorSubject")
                .doesNotContain("QUEUED")
                .doesNotContain("RUNNING")
                .doesNotContain("policy")
                .doesNotContain("customers")
                .doesNotContain(DATASET_ID.toString())
                .doesNotContain("runId")
                .doesNotContain("count");
    }
}

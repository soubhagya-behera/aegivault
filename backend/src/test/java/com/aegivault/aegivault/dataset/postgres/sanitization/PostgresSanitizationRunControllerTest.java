package com.aegivault.aegivault.dataset.postgres.sanitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.sanitization.policy.PolicyNotFoundException;
import com.aegivault.aegivault.sanitization.run.ReferencedDatasetNotFoundException;
import com.aegivault.aegivault.sanitization.run.RunStatus;
import com.aegivault.aegivault.sanitization.run.SanitizationRunNotFoundException;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLaunchException;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunNotLaunchableException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Standalone MockMvc tests for the PostgreSQL run-creation endpoint's HTTP
 * contract.
 *
 * <p><strong>No Spring context, no datasource, no pool.</strong> The orchestrator
 * is a mock, so this adds no application context, no PostgreSQL connection, and
 * no executor — which matters given this repository's prior connection
 * pressure.
 *
 * <p>The orchestrator's ordering, the real launcher's behaviour, and the
 * end-to-end PostgreSQL execution are covered elsewhere; this suite is about the
 * web contract only.
 */
class PostgresSanitizationRunControllerTest {

    private static final String SUBJECT = "pg-run-owner";

    private static final String OTHER = "pg-run-other";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000f11e");

    private static final UUID POLICY_ID = UUID.fromString("00000000-0000-0000-0000-00000000f11f");

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000f120");

    private static final String PATH = "/api/datasets/" + DATASET_ID + "/postgres/runs";

    private PostgresSanitizationRunRequestService requests;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        requests = mock(PostgresSanitizationRunRequestService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PostgresSanitizationRunController(requests))
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

    private static SanitizationRunView queuedRun() {
        return new SanitizationRunView(
                RUN_ID,
                DATASET_ID,
                RunStatus.QUEUED,
                "pol",
                "v1",
                "{\"rules\":[]}",
                SanitizationSourceType.POSTGRESQL,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private void givenQueued() {
        when(requests.queueAndLaunch(SUBJECT, DATASET_ID, POLICY_ID)).thenReturn(queuedRun());
    }

    private static String body() {
        return "{\"policyId\":\"" + POLICY_ID + "\"}";
    }

    @Test
    void theOwnerGetsAcceptedWithTheRunLocationAndAQueuedPostgresRun() throws Exception {
        givenQueued();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/runs/" + RUN_ID))
                .andExpect(jsonPath("$.id").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.datasetId").value(DATASET_ID.toString()))
                // The run is queued, not finished: nothing waited for completion.
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.sourceType").value("POSTGRESQL"))
                .andExpect(jsonPath("$.policyName").value("pol"))
                .andExpect(jsonPath("$.policyVersion").value("v1"));

        verify(requests).queueAndLaunch(SUBJECT, DATASET_ID, POLICY_ID);
    }

    @Test
    void theLocationHeaderPointsAtTheExistingRunResource() throws Exception {
        givenQueued();

        // One established location: the run resource that already serves
        // GET /api/runs/{runId}, not a new PostgreSQL-specific run endpoint.
        String location = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("/api/runs/").doesNotContain("postgres");
    }

    @Test
    void theResponseCarriesNoCredentialsSourceOrRowDetail() throws Exception {
        givenQueued();

        String body = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Run metadata only: no connection detail, no schema/table, no row value,
        // and no owner echo.
        assertThat(body)
                .doesNotContain("jdbc")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("host")
                .doesNotContain("SELECT")
                .doesNotContain("schemaName")
                .doesNotContain("tableName")
                .doesNotContain(SUBJECT);
    }

    @Test
    void theActorComesOnlyFromTheTokenAndBodyQueryAndHeaderCannotOverrideIt() throws Exception {
        givenQueued();

        // The request has no owner field, so an owner hint in the body, the
        // query string, or a header is simply not read.
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("ownerSubject", OTHER)
                        .param("actorSubject", OTHER)
                        .header("X-Owner-Subject", OTHER)
                        .content("{\"policyId\":\"" + POLICY_ID + "\","
                                + "\"ownerSubject\":\"" + OTHER + "\","
                                + "\"sourceType\":\"CSV\",\"schemaName\":\"other\","
                                + "\"tableName\":\"other\",\"jdbcUrl\":\"jdbc:postgresql://x\","
                                + "\"password\":\"hunter2\",\"rowLimit\":100000,"
                                + "\"transformationRules\":[],\"sql\":\"SELECT 1\","
                                + "\"tokens\":[\"real-value\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceType").value("POSTGRESQL"));

        // Only the token subject, the path id, and the policy id were ever used.
        verify(requests).queueAndLaunch(SUBJECT, DATASET_ID, POLICY_ID);
    }

    @Test
    void anUnknownJsonPropertyIsIgnoredRatherThanFatal() throws Exception {
        givenQueued();

        // The project's existing unknown-property behaviour: extra fields do not
        // fail the request and cannot influence anything.
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + POLICY_ID + "\",\"unexpected\":\"x\"}"))
                .andExpect(status().isAccepted());

        verify(requests).queueAndLaunch(SUBJECT, DATASET_ID, POLICY_ID);
    }

    @Test
    void aMissingDatasetAndAMissingBindingAreTheSameGenericNotFound() throws Exception {
        // Both arrive as the binding layer's not-found signal, which extends the
        // dataset one; the caller cannot tell them apart.
        when(requests.queueAndLaunch(eq(SUBJECT), any(UUID.class), any(UUID.class)))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aForeignDatasetIsThatSameGenericNotFound() throws Exception {
        when(requests.queueAndLaunch(eq(SUBJECT), any(UUID.class), any(UUID.class)))
                .thenThrow(new ReferencedDatasetNotFoundException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aMissingOrForeignPolicyIsAlsoThatSameGenericNotFound() throws Exception {
        when(requests.queueAndLaunch(eq(SUBJECT), any(UUID.class), any(UUID.class)))
                .thenThrow(new PolicyNotFoundException());

        // Same status and same body as the dataset failures: the response must
        // not reveal which of the three references failed.
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aMissingDatasetIdIsThatSameGenericNotFoundToo() throws Exception {
        when(requests.queueAndLaunch(eq(SUBJECT), any(UUID.class), any(UUID.class)))
                .thenThrow(new SanitizationRunNotFoundException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aMalformedDatasetIdIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(post("/api/datasets/not-a-uuid/postgres/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid run request."));

        verifyNoInteractions(requests);
    }

    @Test
    void aMalformedPolicyIdIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requests);
    }

    @Test
    void anAbsentOrUnparseableBodyIsABadRequest() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requests);
    }

    @Test
    void launchSubmissionFailureIsSafeAndNamesNoExecutorDetail() throws Exception {
        // The run exists but the bounded pool refused the work: the caller must
        // be told the launch did not happen.
        when(requests.queueAndLaunch(eq(SUBJECT), any(UUID.class), any(UUID.class)))
                .thenThrow(new SanitizationRunJobLaunchException(
                        new RejectedExecutionException("pool at capacity")));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(SanitizationRunJobLaunchException.MESSAGE))
                // No pool, queue, thread, or executor detail reaches the client.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.containsString("pool"),
                                org.hamcrest.Matchers.containsString("thread"),
                                org.hamcrest.Matchers.containsString("queue"),
                                org.hamcrest.Matchers.containsString("executor")))));
    }

    @Test
    void aRunThatIsNotLaunchableIsAlsoASafeServiceUnavailable() throws Exception {
        when(requests.queueAndLaunch(eq(SUBJECT), any(UUID.class), any(UUID.class)))
                .thenThrow(new SanitizationRunNotLaunchableException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(SanitizationRunNotLaunchableException.MESSAGE));
    }

    @Test
    void theEndpointExistsOnlyToCreateRuns() throws Exception {
        // Exactly one operation, and it is a create. No read, delete, or other
        // run verb is added on this path.
        assertThat(java.util.Arrays.stream(
                        PostgresSanitizationRunController.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("create");

        // Read and delete are not offered here: a POST-only path answers 405
        // Method Not Allowed, and no handler was ever invoked for either.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(PATH))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(PATH))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(requests);
    }

    @Test
    void theControllerHoldsOnlyTheOrchestrator() {
        // No repository, sanitizer, artifact store, row source, executor, or
        // policy service is reachable, so this endpoint cannot read rows,
        // persist credentials, sanitize, or launch anything by itself.
        assertThat(java.util.Arrays.stream(
                        PostgresSanitizationRunController.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactly("PostgresSanitizationRunRequestService");
    }

    @Test
    void theRequestCarriesAPolicyIdAndNothingElse() {
        // One component: no owner, schema, table, source, credential, rule, or
        // row-limit field exists to be supplied.
        assertThat(java.util.Arrays.stream(PostgresRunCreationRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("policyId");
        assertThat(PostgresRunCreationRequest.class.getRecordComponents()[0].getType())
                .isEqualTo(UUID.class);
    }

    @Test
    void aBodyNamingADifferentDatasetCannotRedirectTheRun() throws Exception {
        when(requests.queueAndLaunch(anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(queuedRun());

        // The dataset is the path, not a body field, so a body-supplied dataset
        // id is ignored.
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":\"" + POLICY_ID + "\",\"datasetId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isAccepted());

        verify(requests).queueAndLaunch(SUBJECT, DATASET_ID, POLICY_ID);
    }
}
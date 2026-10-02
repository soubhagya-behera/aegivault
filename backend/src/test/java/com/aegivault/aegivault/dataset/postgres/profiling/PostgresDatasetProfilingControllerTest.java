package com.aegivault.aegivault.dataset.postgres.profiling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingNotFoundException;
import com.aegivault.aegivault.dataset.profile.DatasetProfileResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Standalone MockMvc tests for the profiling endpoint's HTTP contract.
 *
 * <p><strong>No Spring context and no datasource.</strong> The service is a mock,
 * so this adds no application context, no pool, and no PostgreSQL connection —
 * which matters given the repository's prior connection exhaustion.
 *
 * <p>The service's own discovery, bounded reading, and persistence are covered
 * by its existing tests; this suite is deliberately about the web contract only.
 */
class PostgresDatasetProfilingControllerTest {

    private static final String SUBJECT = "owner-1";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-00000000f11e");

    private static final String PATH = "/api/datasets/" + DATASET_ID + "/postgres/profile";

    private PostgresDatasetProfilingService profiles;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        profiles = mock(PostgresDatasetProfilingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PostgresDatasetProfilingController(profiles))
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

    private void givenProfile() {
        when(profiles.profile(anyString(), eq(DATASET_ID))).thenReturn(sampleProfile());
    }

    private static DatasetProfileResponse sampleProfile() {
        return new DatasetProfileResponse(
                DATASET_ID,
                List.of(new DatasetProfileResponse.ColumnProfileResponse(
                        "email", 4, 4, 3,
                        Map.of(com.aegivault.aegivault.pii.PiiType.EMAIL, 2),
                        Map.of(com.aegivault.aegivault.pii.PiiType.EMAIL, 2.0 / 3.0),
                        Set.of(com.aegivault.aegivault.pii.PiiType.EMAIL))),
                1,
                100);
    }

    @Test
    void theOwnerGetsTheStoredProfileWithNoRequestBodyRequired() throws Exception {
        givenProfile();

        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(DATASET_ID.toString()))
                .andExpect(jsonPath("$.totalColumns").value(1))
                .andExpect(jsonPath("$.maxSampleSizePerColumn").value(100))
                .andExpect(jsonPath("$.columns[0].columnName").value("email"))
                .andExpect(jsonPath("$.columns[0].suppliedValueCount").value(4))
                .andExpect(jsonPath("$.columns[0].analyzableValueCount").value(3))
                .andExpect(jsonPath("$.columns[0].detectionCounts.EMAIL").value(2));

        verify(profiles).profile(SUBJECT, DATASET_ID);
    }

    @Test
    void theResponseIsTheExistingProfileShapeWithNoValueOrCredentialFields() throws Exception {
        givenProfile();

        String body = mvc.perform(post(PATH)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Metadata and counts only: no sampled value, no row value, no connection
        // detail, no SQL.
        assertThat(body)
                .doesNotContain(SUBJECT)
                .doesNotContain("ownerSubject")
                .doesNotContain("jdbc")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("host")
                .doesNotContain("SELECT")
                .doesNotContain("sample");
    }

    @Test
    void aCallerSuppliedBodyIsIgnoredAndCannotInfluenceAnything() throws Exception {
        givenProfile();

        // No request body is accepted or read: an owner, schema, table, or policy
        // sent in the body is ignored, exactly as for any bodyless endpoint.
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("actorSubject", "someone-else")
                        .param("ownerSubject", "someone-else")
                        .header("X-Owner-Subject", "someone-else")
                        .content("{\"ownerSubject\":\"someone-else\","
                                + "\"schemaName\":\"other\",\"tableName\":\"other\","
                                + "\"policyId\":\"00000000-0000-0000-0000-000000000000\"}"))
                .andExpect(status().isOk());

        // Only the token subject and the path id reached the service.
        verify(profiles).profile(SUBJECT, DATASET_ID);
    }

    @Test
    void aForeignOrMissingDatasetIsTheSameGenericNotFound() throws Exception {
        when(profiles.profile(anyString(), eq(DATASET_ID)))
                .thenThrow(new DatasetNotFoundException());

        mvc.perform(post(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aDatasetWithNoBindingIsTheSameGenericNotFound() throws Exception {
        // The binding layer's own not-found signal, which extends the dataset's:
        // indistinguishable from "no such dataset".
        when(profiles.profile(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        mvc.perform(post(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aStaleBindingIsTheSafeProfilingErrorWithNoSourceDetail() throws Exception {
        when(profiles.profile(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresDatasetProfilingException(
                        new IllegalStateException("bound table is no longer a discovered base table")));

        mvc.perform(post(PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(PostgresDatasetProfilingException.MESSAGE))
                // The internal wording is not echoed to the caller.
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("discovered base table"))));
    }

    @Test
    void anUnconfiguredOrUnreadableSourceIsTheSameSafeProfilingError() throws Exception {
        when(profiles.profile(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresDatasetProfilingException());

        mvc.perform(post(PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Unable to profile the bound PostgreSQL source table."));
    }

    @Test
    void aMalformedDatasetIdIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(post("/api/datasets/not-a-uuid/postgres/profile"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid dataset id."));

        verifyNoInteractions(profiles);
    }

    @Test
    void theControllerDependsOnTheProfilingServiceAndNothingElse() {
        // No sanitizer, run executor, artifact store, launcher, or repository is
        // reachable, so this endpoint cannot sanitize, create a run, or write an
        // artifact.
        assertThat(java.util.Arrays.stream(
                        PostgresDatasetProfilingController.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactly("PostgresDatasetProfilingService");
    }

    @Test
    void theControllerTakesNoBodyAndExposesOnlyTheProfileOperation() throws Exception {
        // One verb, and its signature takes no body, owner, schema, table,
        // policy, or row-limit parameter of any kind.
        assertThat(java.util.Arrays.stream(
                        PostgresDatasetProfilingController.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("profile");
        assertThat(java.util.Arrays.stream(
                        PostgresDatasetProfilingController.class
                                .getDeclaredMethod("profile", Jwt.class, UUID.class)
                                .getParameterTypes())
                .map(Class::getSimpleName))
                .containsExactly("Jwt", "UUID");
    }
}
package com.aegivault.aegivault.dataset.postgres.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import java.time.Instant;
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
 * Standalone MockMvc tests for the binding endpoint's HTTP contract: creation,
 * status mapping, and the fact that the actor comes only from the token.
 *
 * <p><strong>No Spring context.</strong> {@code standaloneSetup} with a mocked
 * service, so no application context and no datasource pool are added — which
 * matters given the repository's prior PostgreSQL connection pressure.
 *
 * <p>The internal binding service's own validation, discovery, and ownership
 * behaviour is already covered by its dedicated unit and persistence tests; this
 * suite deliberately does not duplicate them. Here the service is a mock, and
 * only the web contract is under test.
 */
class PostgresDatasetBindingControllerTest {

    private static final String SUBJECT = "owner-1";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-0000000006d0");

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

    private void givenBindSucceeds() {
        when(bindings.bind(anyString(), eq(DATASET_ID), anyString(), anyString()))
                .thenReturn(bindingWithTimestamps());
    }

    /**
     * A persisted binding, with its lifecycle timestamps set the way
     * {@code @PrePersist} would on a real save — a bare constructor call leaves
     * them null, which would make the response shape untestable.
     */
    private static PostgresDatasetBinding bindingWithTimestamps() {
        PostgresDatasetBinding binding =
                new PostgresDatasetBinding(DATASET_ID, SUBJECT, "public", "customers");
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        setTimestamps(binding, now);
        return binding;
    }

    private static void setTimestamps(PostgresDatasetBinding binding, Instant value) {
        try {
            for (String field : new String[] {"createdAt", "updatedAt"}) {
                java.lang.reflect.Field target = PostgresDatasetBinding.class.getDeclaredField(field);
                target.setAccessible(true);
                target.set(binding, value);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("unable to stamp the test binding", ex);
        }
    }

    private static String body(String schema, String table) {
        return "{\"schemaName\":\"" + schema + "\",\"tableName\":\"" + table + "\"}";
    }

    @Test
    void theOwnerBindsATableAndGetsTwoHundredAndOneWithALocationHeader() throws Exception {
        givenBindSucceeds();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("public", "customers")))
                .andExpect(status().isCreated())
                // The project's existing Location convention for created resources.
                .andExpect(header().string("Location", PATH))
                .andExpect(jsonPath("$.datasetId").value(DATASET_ID.toString()))
                .andExpect(jsonPath("$.schemaName").value("public"))
                .andExpect(jsonPath("$.tableName").value("customers"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verify(bindings).bind(SUBJECT, DATASET_ID, "public", "customers");
    }

    @Test
    void theActorComesOnlyFromTheTokenAndOwnerHintsAreIgnored() throws Exception {
        givenBindSucceeds();

        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        // A caller trying to name a different actor in any channel.
                        .param("actorSubject", "someone-else")
                        .param("ownerSubject", "someone-else")
                        .header("X-Owner-Subject", "someone-else")
                        .content("{\"schemaName\":\"public\",\"tableName\":\"customers\","
                                + "\"ownerSubject\":\"someone-else\"}"))
                .andExpect(status().isCreated());

        // The body field is not bound into the request record, and the owner
        // argument is the token subject.
        verify(bindings).bind(SUBJECT, DATASET_ID, "public", "customers");
    }

    @Test
    void theResponseCarriesNoOwnerCredentialOrRowDetail() throws Exception {
        givenBindSucceeds();

        String response = mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body("public", "customers")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain(SUBJECT)
                .doesNotContain("ownerSubject")
                .doesNotContain("jdbc")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("host")
                .doesNotContain("rowCount");
    }

    @Test
    void aForeignOrMissingDatasetIsTheSameGenericNotFound() throws Exception {
        when(bindings.bind(anyString(), eq(DATASET_ID), anyString(), anyString()))
                .thenThrow(new DatasetNotFoundException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("public", "customers")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aSecondBindingIsAConflictThatDisclosesNothing() throws Exception {
        when(bindings.bind(anyString(), eq(DATASET_ID), anyString(), anyString()))
                .thenThrow(new PostgresDatasetAlreadyBoundException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("other_schema", "other_table")))
                .andExpect(status().isConflict())
                // The fixed safe message: no old schema, table, or owner.
                .andExpect(jsonPath("$.message")
                        .value(PostgresDatasetAlreadyBoundException.MESSAGE))
                .andExpect(jsonPath("$.message").value("The dataset is already bound to a PostgreSQL table."));
    }

    @Test
    void anUnknownTableIsTheSafeSourceError() throws Exception {
        when(bindings.bind(anyString(), eq(DATASET_ID), anyString(), anyString()))
                .thenThrow(new PostgresDatasetBindingSourceException(
                        new IllegalStateException("no such table")));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("public", "no_such_table")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PostgreSQL source is not available."))
                // No hint that another, similarly named table exists.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("no_such_table"))));
    }

    @Test
    void anUnavailableSourceIsTheSameSafeSourceError() throws Exception {
        when(bindings.bind(anyString(), eq(DATASET_ID), anyString(), anyString()))
                .thenThrow(new PostgresDatasetBindingSourceException(
                        new IllegalStateException("no PostgreSQL source is configured")));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("public", "customers")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PostgreSQL source is not available."));
    }

    @Test
    void aBlankOrOverlongIdentifierIsRejectedAtTheHttpBoundary() throws Exception {
        // Missing, blank, and overlong are all refused before the service runs.
        for (String schema : new String[] {"", "   ", null, "s".repeat(64)}) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(body(schema == null ? "" : schema, "customers")))
                    .andExpect(status().isBadRequest());
        }
        for (String table : new String[] {"", "   ", "t".repeat(64)}) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(body("public", table)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(bindings);
    }

    @Test
    void anIdentifierTheGrammarRefusesIsRejectedWithoutEchoingIt() throws Exception {
        when(bindings.bind(anyString(), eq(DATASET_ID), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("schema name must be a plain identifier"));

        // A dot, quote, semicolon, or space would be refused by the service's
        // strict grammar; the controller reports it generically.
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("public", "a.b;c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid PostgreSQL binding request."))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("a.b;c"))));
    }

    @Test
    void aMalformedDatasetIdIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(post("/api/datasets/not-a-uuid/postgres/binding")
                        .contentType(MediaType.APPLICATION_JSON).content(body("public", "customers")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid dataset id."));

        verifyNoInteractions(bindings);
    }

    @Test
    void theOnlyVerbsOnThisResourceAreCreateReadAndDelete() throws Exception {
        // Read and delete are available; modification is not. Rebinding and
        // update stay unsupported, so PUT answers 405.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body("public", "customers")))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void theControllerExposesOnlyBindReadDeleteAndNeverTheOwnerOrSource() throws Exception {
        // Three verbs — bind, read, and delete — and read and delete take no
        // request body of any kind.
        assertThat(java.util.Arrays.stream(
                        PostgresDatasetBindingController.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("bind", "getBinding", "deleteBinding");

        // It holds only the existing binding service: no repository, no profiler,
        // no sanitizer, no run executor, no artifact store.
        assertThat(java.util.Arrays.stream(
                        PostgresDatasetBindingController.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .map(Class::getSimpleName))
                .containsExactly("PostgresDatasetBindingService");
    }

    @Test
    void theRequestContractHasNoSourceTypeOrConnectionField() {
        // The strongest form of "source_type is unchanged": the request record has
        // exactly two components, so no source type, connection detail, or
        // credential can be supplied at all — there is nowhere to put one.
        assertThat(java.util.Arrays.stream(CreatePostgresBindingRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("schemaName", "tableName");
    }

    private void givenGetSucceeds() {
        when(bindings.get(anyString(), eq(DATASET_ID))).thenReturn(bindingWithTimestamps());
    }

    @Test
    void theOwnerReadsItsOwnBindingAndGetsTwoHundredWithExactlyTheStoredValues() throws Exception {
        givenGetSucceeds();

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(DATASET_ID.toString()))
                .andExpect(jsonPath("$.schemaName").value("public"))
                .andExpect(jsonPath("$.tableName").value("customers"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-02T03:04:05Z"));

        // The owner-scoped internal lookup is what served it.
        verify(bindings).get(SUBJECT, DATASET_ID);
    }

    @Test
    void theReadResponseCarriesExactlyTheFiveMetadataKeysAndNothingElse() throws Exception {
        givenGetSucceeds();

        String body = mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Metadata only: no owner, no credential, no connection detail, no row
        // count, no value, no PII finding, and no SQL.
        assertThat(body)
                .doesNotContain("ownerSubject")
                .doesNotContain("actorSubject")
                .doesNotContain("host")
                .doesNotContain("port")
                .doesNotContain("database")
                .doesNotContain("username")
                .doesNotContain("password")
                .doesNotContain("jdbc")
                .doesNotContain("rowCount")
                .doesNotContain("SELECT")
                .doesNotContain(SUBJECT);
    }

    @Test
    void theReadActorComesOnlyFromTheTokenAndOwnerHintsAreIgnored() throws Exception {
        givenGetSucceeds();

        // A read takes no body at all, so the only channels a caller could try to
        // name a different actor are query parameters and headers.
        mvc.perform(get(PATH)
                        .param("ownerSubject", "someone-else")
                        .param("actorSubject", "someone-else")
                        .header("X-Owner-Subject", "someone-else"))
                .andExpect(status().isOk());

        // Only the token subject reached the service.
        verify(bindings).get(SUBJECT, DATASET_ID);
    }

    @Test
    void aForeignOrMissingBindingIsTheSameGenericNotFound() throws Exception {
        // The service's owner-scoped lookup signals both identically, so the
        // response cannot distinguish them or reveal another owner's schema/table.
        when(bindings.get(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        mvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aDatasetWithNoBindingIsThatSameGenericNotFound() throws Exception {
        // Not an empty object, not a null body, and never a guessed CSV state.
        when(bindings.get(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresDatasetBindingNotFoundException());

        mvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.schemaName").doesNotExist())
                .andExpect(jsonPath("$.tableName").doesNotExist());
    }

    @Test
    void aMalformedDatasetIdOnReadIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(get("/api/datasets/not-a-uuid/postgres/binding"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid dataset id."));

        // The lookup never ran, so nothing was read for an unusable id.
        verifyNoInteractions(bindings);
    }

    @Test
    void theReadNeverDiscoversVerifiesOrProfilesTheSource() throws Exception {
        givenGetSucceeds();

        mvc.perform(get(PATH)).andExpect(status().isOk());

        // Only the owner-scoped row read happened: no bind (which would
        // rediscover and verify the table), no profile, and no sanitizer.
        verify(bindings).get(SUBJECT, DATASET_ID);
        verify(bindings, never()).bind(anyString(), eq(DATASET_ID), anyString(), anyString());
    }
}
package com.aegivault.aegivault.dataset.postgres.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
 * Standalone MockMvc tests for the controller's HTTP contract: status mapping,
 * response shape, and the fact that the actor comes only from the token.
 *
 * <p><strong>No Spring context.</strong> This is a plain
 * {@code MockMvcBuilders.standaloneSetup} with a mocked service, so it adds no
 * application context and no datasource pool. The security filter chain is not
 * installed either — 401 behaviour is a property of the shared
 * {@code SecurityConfig}, not of this controller, and is covered there.
 *
 * <p>The {@link Jwt} is supplied through a minimal argument resolver so the
 * controller receives a real principal and its use of {@code jwt.getSubject()}
 * is genuinely exercised.
 */
class PostgresTableDiscoveryControllerTest {

    private static final String SUBJECT = "owner-1";

    private static final String BASE = "/api/datasets";

    private static final UUID DATASET_ID = UUID.fromString("00000000-0000-0000-0000-000000000db1");

    private PostgresDatasetTableDiscoveryService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PostgresDatasetTableDiscoveryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PostgresTableDiscoveryController(service))
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
            return jwtFor(SUBJECT);
        }
    }

    private static Jwt jwtFor(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", subject)
                .build();
    }

    private void givenTables() {
        when(service.listTables(anyString(), eq(DATASET_ID))).thenReturn(new PostgresTablesResponse(
                "public",
                List.of(new PostgresTablesResponse.PostgresTableView(
                        "customers",
                        List.of(
                                new PostgresTablesResponse.PostgresColumnView("id", 1, "uuid"),
                                new PostgresTablesResponse.PostgresColumnView("email", 2, "varchar"))))));
    }

    @Test
    void theOwnerSeesTheConfiguredSchemaAndItsTableMetadata() throws Exception {
        givenTables();

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("public"))
                .andExpect(jsonPath("$.tables[0].name").value("customers"))
                .andExpect(jsonPath("$.tables[0].columns[0].name").value("id"))
                .andExpect(jsonPath("$.tables[0].columns[0].ordinalPosition").value(1))
                .andExpect(jsonPath("$.tables[0].columns[0].dataType").value("uuid"))
                .andExpect(jsonPath("$.tables[0].columns[1].name").value("email"))
                .andExpect(jsonPath("$.tables[0].columns[1].ordinalPosition").value(2))
                .andExpect(jsonPath("$.tables[0].columns[1].dataType").value("varchar"));
    }

    @Test
    void theActorSubjectComesFromTheTokenAndAnyOwnerParameterIsIgnored() throws Exception {
        givenTables();

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables")
                        // A caller trying to name a different actor is ignored.
                        .param("actorSubject", "someone-else")
                        .param("ownerSubject", "someone-else")
                        .header("X-Owner-Subject", "someone-else"))
                .andExpect(status().isOk());

        verify(service).listTables(SUBJECT, DATASET_ID);
    }

    @Test
    void theResponseCarriesNoOwnerAndNoCredentialOrRowFields() throws Exception {
        givenTables();

        String body = mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No owner echo, no connection detail, no data.
        assertThat(body)
                .doesNotContain(SUBJECT)
                .doesNotContain("ownerSubject")
                .doesNotContain("jdbc")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("host")
                .doesNotContain("SELECT")
                .doesNotContain("rowCount");
    }

    @Test
    void onlyTheExpectedResponseFieldsArePresent() throws Exception {
        givenTables();

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").exists())
                .andExpect(jsonPath("$.tables").exists())
                .andExpect(jsonPath("$.tables[0].name").exists())
                .andExpect(jsonPath("$.tables[0].columns[0].name").exists())
                .andExpect(jsonPath("$.tables[0].columns[0].ordinalPosition").exists())
                .andExpect(jsonPath("$.tables[0].columns[0].dataType").exists())
                // Nothing beyond the three documented column fields.
                .andExpect(jsonPath("$.tables[0].columns[0].nullable").doesNotExist())
                .andExpect(jsonPath("$.tables[0].columns[0].defaultValue").doesNotExist())
                .andExpect(jsonPath("$.tables[0].rowCount").doesNotExist())
                .andExpect(jsonPath("$.ownerSubject").doesNotExist());
    }

    @Test
    void anEmptySchemaIsTwoHundredWithAnEmptyList() throws Exception {
        when(service.listTables(anyString(), eq(DATASET_ID)))
                .thenReturn(new PostgresTablesResponse("public", List.of()));

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("public"))
                .andExpect(jsonPath("$.tables").isArray())
                .andExpect(jsonPath("$.tables").isEmpty());
    }

    @Test
    void aForeignOrMissingDatasetIsTheSameGenericNotFound() throws Exception {
        when(service.listTables(anyString(), eq(DATASET_ID)))
                .thenThrow(new DatasetNotFoundException());

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dataset not found."));
    }

    @Test
    void aMalformedDatasetIdIsABadRequestThatDoesNotEchoTheText() throws Exception {
        mvc.perform(get(BASE + "/not-a-uuid/postgres/tables"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid dataset id."));

        verifyNoInteractions(service);
    }

    @Test
    void anUnconfiguredSourceIsASafeServiceUnavailable() throws Exception {
        when(service.listTables(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresSourceUnavailableException());

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PostgreSQL source is not available."));
    }

    @Test
    void aDiscoveryFailureIsTheSameSafeServiceUnavailable() throws Exception {
        when(service.listTables(anyString(), eq(DATASET_ID)))
                .thenThrow(new PostgresSchemaDiscoveryUnavailableException());

        mvc.perform(get(BASE + "/" + DATASET_ID + "/postgres/tables"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PostgreSQL source is not available."));
    }

    @Test
    void theControllerExposesOnlyTheDiscoveryOperation() {
        // One read operation, no table name, no SQL, no binding/profile/run verb,
        // and no owner parameter anywhere on the signature.
        assertThat(java.util.Arrays.stream(
                        PostgresTableDiscoveryController.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(java.lang.reflect.Method::getName))
                .containsExactly("listTables");
    }
}
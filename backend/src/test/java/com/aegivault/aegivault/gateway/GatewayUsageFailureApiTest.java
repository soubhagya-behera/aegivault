package com.aegivault.aegivault.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.gateway.usage.GatewayUsageException;
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
 * Usage-persistence failure at the HTTP boundary without a Spring
 * context (standalone controller setup, so no database pool and no new
 * application context): when the usage row cannot be stored, the
 * completion fails as a generic 500 carrying only the safe message —
 * no SQL details, actor, request id, or exception text — and the success
 * response is never claimed.
 */
class GatewayUsageFailureApiTest {

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    private MockMvc mvc;

    private GatewayCompletionService completions;

    @BeforeEach
    void setup() {
        completions = mock(GatewayCompletionService.class);
        GatewayController controller = new GatewayController(
                mock(SecurityInspectionService.class), mock(GatewayAuditService.class), completions);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticatedJwt())
                .build();
    }

    /** Standalone stand-in for the JWT authentication principal (no security filter chain here). */
    private static HandlerMethodArgumentResolver authenticatedJwt() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("test-actor")
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

    @Test
    void usagePersistenceFailureReturnsGeneric500() throws Exception {
        when(completions.complete(any()))
                .thenThrow(new GatewayUsageException(
                        "Unable to record gateway usage.",
                        new RuntimeException("simulated-usage-store-boom-6k")));

        mvc.perform(post("/api/gateway/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to record gateway usage."));
    }

    @Test
    void usageFailureResponseLeaksNoInternals() throws Exception {
        when(completions.complete(any()))
                .thenThrow(new GatewayUsageException(
                        "Unable to record gateway usage.",
                        new RuntimeException("simulated-usage-store-boom-6k")));

        String response = mvc.perform(post("/api/gateway/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"local-test-model\",\"content\":\"" + CLEAN + "\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain("simulated-usage-store-boom-6k", CLEAN, "actorSubject", "requestId", "Exception");
    }
}

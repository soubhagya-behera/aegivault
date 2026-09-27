package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyAmbiguousException;
import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pure unit tests for the gateway controller's error mapping (no Spring
 * context, no database, no HTTP dispatch, no Redis). They pin the status and
 * body of every policy-related failure so the safe-message contract cannot
 * drift, including the failure that cannot be produced through the real
 * in-memory counter: a policy-counter outage.
 *
 * <p>The handler methods are invoked directly, which is enough because the
 * whole point of each is the {@link GatewayError} it returns and the
 * {@link HttpStatus} it is annotated with.
 */
class GatewayControllerPolicyErrorTest {

    private final GatewayController controller = new GatewayController(
            mock(SecurityInspectionService.class),
            mock(GatewayAuditService.class),
            mock(GatewayCompletionService.class));

    @Test
    void aPolicyRejectionIs429WithOnlyTheSafeMessage() {
        GatewayError error =
                controller.usagePolicyLimitExceeded(new GatewayUsagePolicyLimitExceededException());

        assertThat(error).isEqualTo(new GatewayError("Gateway usage policy limit exceeded."));
        assertThat(error.message()).doesNotContain("MINUTE", "DAY", "redis", "localhost");
    }

    @Test
    void theThreePolicyHandlersDeclareTheirIntendedStatuses() throws Exception {
        // The annotations are the contract: 429 for a rejection, 500 for the
        // two failure-to-decide cases. Read off the methods directly.
        assertThat(statusOf("usagePolicyLimitExceeded")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(statusOf("usagePolicyUnavailable")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(statusOf("usagePolicyAmbiguous")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void anUnavailablePolicyCounterIs500AndNot429() {
        GatewayError error = controller.usagePolicyUnavailable(
                new GatewayUsagePolicyEnforcementException(new RuntimeException("redis-connection-refused-9z")));

        // Fail closed, and never reported as an exceeded limit.
        assertThat(error).isEqualTo(new GatewayError("Unable to enforce gateway usage policy."));
        assertThat(error.message())
                .doesNotContain("redis-connection-refused")
                .doesNotContain("localhost")
                .doesNotContain("6379")
                .doesNotContain("exceeded");
    }

    @Test
    void anAmbiguousPolicyIs500WithAGenericResolutionMessage() {
        GatewayError error = controller.usagePolicyAmbiguous(new GatewayUsagePolicyAmbiguousException());

        assertThat(error).isEqualTo(new GatewayError("Unable to resolve gateway usage policy."));
        // The exception's own message names the problem but is never surfaced,
        // and no policy detail rides along with it.
        assertThat(error.message())
                .isNotEqualTo("Multiple enabled gateway usage policies are configured.")
                .doesNotContain("Multiple enabled")
                .doesNotContain("aegivault");
    }

    @Test
    void thePolicyAndRateLimitMessagesStayDistinct() {
        // Two independent controls, so a client can tell which one stopped a
        // request from the body alone.
        assertThat(new GatewayError(GatewayUsagePolicyLimitExceededException.MESSAGE).message())
                .isNotEqualTo(GatewayRateLimitExceededException.MESSAGE)
                .isNotEqualTo(GatewayRateLimitUnavailableException.MESSAGE);
    }

    /** Reads the {@code @ResponseStatus} value off a named handler method. */
    private static HttpStatus statusOf(String methodName) throws NoSuchMethodException {
        for (var candidate : GatewayController.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 1) {
                var annotation =
                        candidate.getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
                assertThat(annotation).as("handler %s must declare a response status", methodName).isNotNull();
                return annotation.value();
            }
        }
        throw new AssertionError("no single-argument handler named " + methodName);
    }
}

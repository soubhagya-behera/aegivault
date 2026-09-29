package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyEnforcementException;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementException;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pure unit tests for the token-budget half of the gateway controller's error
 * mapping (no Spring context, no database, no HTTP dispatch, no Redis). They
 * pin the status and the exact body of the three outcomes this integration can
 * produce — a refusal, a budget that could not answer, and a settlement that
 * could not be finalised — because the one thing all three must guarantee is
 * that a client learns <em>which</em> control stopped the request and nothing
 * whatsoever about the budget itself.
 *
 * <p>The handler methods are invoked directly, which is enough because the
 * whole contract of each is the {@link GatewayError} it returns and the
 * {@link HttpStatus} it is annotated with.
 */
class GatewayControllerTokenBudgetErrorTest {

    private final GatewayController controller = new GatewayController(
            mock(SecurityInspectionService.class),
            mock(GatewayAuditService.class),
            mock(GatewayCompletionService.class));

    @Test
    void aTokenBudgetRejectionIs429WithOnlyTheSafeMessage() {
        GatewayError error =
                controller.tokenBudgetLimitExceeded(new GatewayTokenBudgetLimitExceededException());

        assertThat(error).isEqualTo(new GatewayError("Gateway token budget exceeded."));
        // No current or remaining usage, no limit, no reserved amount, no
        // reservation id, no actor, and no storage detail.
        assertThat(error.message())
                .doesNotContain("redis", "localhost", "reservation", "token-budget", "actor", "tokens");
    }

    @Test
    void anUncheckableBudgetIs500AndNot429() {
        GatewayError error = controller.tokenBudgetUnavailable(
                new GatewayTokenBudgetEnforcementException(new RuntimeException("redis-connection-refused-9z")));

        // An outage is not an exhausted budget, and reporting it as one would
        // hide the outage behind a plausible-looking quota message.
        assertThat(error).isEqualTo(new GatewayError("Unable to enforce gateway token budget."));
        assertThat(error.message())
                .doesNotContain("redis-connection-refused", "localhost", "6379")
                .doesNotContain("exceeded");
    }

    @Test
    void anUnsettledReservationIs500WithOnlyTheSafeMessage() {
        GatewayError error = controller.tokenBudgetSettlementFailed(
                new GatewayTokenBudgetSettlementException(new RuntimeException("redis-down-4k")));

        // Fail closed: the day's accounting is unknown, so no provider content
        // and no BLOCK body is returned, and nothing about the hold is exposed.
        assertThat(error).isEqualTo(new GatewayError("Unable to settle gateway token budget."));
        assertThat(error.message())
                .doesNotContain("redis-down", "localhost", "reservation", "token-budget", "provider");
    }

    @Test
    void theTokenBudgetHandlersDeclareTheirIntendedStatuses() throws Exception {
        assertThat(statusOf("tokenBudgetLimitExceeded")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(statusOf("tokenBudgetUnavailable")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(statusOf("tokenBudgetSettlementFailed")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void theThreeRefusalsAndFailuresStayDistinctFromEachOther() {
        // Four independent outcomes now reach this controller. A client must be
        // able to tell them apart from the body alone, and changing one control
        // must never silently change another's meaning.
        assertThat(GatewayTokenBudgetLimitExceededException.MESSAGE)
                .isNotEqualTo(GatewayRateLimitExceededException.MESSAGE)
                .isNotEqualTo(GatewayRateLimitUnavailableException.MESSAGE)
                .isNotEqualTo(GatewayUsagePolicyLimitExceededException.MESSAGE)
                .isNotEqualTo(GatewayUsagePolicyEnforcementException.MESSAGE)
                .isNotEqualTo(GatewayTokenBudgetEnforcementException.MESSAGE)
                .isNotEqualTo(GatewayTokenBudgetSettlementException.MESSAGE);
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

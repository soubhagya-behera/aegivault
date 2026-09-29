package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.policy.GatewayUsagePolicyResolver;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudget;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetEnforcementService;
import com.aegivault.aegivault.gateway.policy.budget.GatewayTokenBudgetSettlementService;
import com.aegivault.aegivault.gateway.policy.budget.InMemoryGatewayTokenBudget;

/**
 * A real in-memory {@link GatewayTokenBudget} together with the two real
 * coordinators built over it, for gateway tests whose subject is something
 * else entirely.
 *
 * <p>It exists so a pre-existing gateway test does not have to reason about
 * token budgets at all: it passes the two coordinator collaborators straight
 * into {@link GatewayCompletionService} and nothing else changes. Both halves
 * are the production types rather than stubs, so the wiring is the real one;
 * what makes them inert is the actor's policy, not a doubled implementation.
 *
 * <p>Both coordinators deliberately share one budget, so a test that does
 * exercise reservations sees reservations and settlements land in the same
 * place, exactly as they do in production.
 */
final class InertTokenBudget {

    private final GatewayTokenBudget budget = new InMemoryGatewayTokenBudget();

    private final GatewayTokenBudgetEnforcementService enforcement;

    private final GatewayTokenBudgetSettlementService settlement;

    /**
     * @param resolver the same policy resolver the completion service's
     *        request-limit enforcement already uses, never null
     */
    InertTokenBudget(GatewayUsagePolicyResolver resolver) {
        this.enforcement = new GatewayTokenBudgetEnforcementService(resolver, budget);
        this.settlement = new GatewayTokenBudgetSettlementService(budget);
    }

    /** The production reservation coordinator over the shared budget. */
    GatewayTokenBudgetEnforcementService enforcement() {
        return enforcement;
    }

    /** The production settlement coordinator over the shared budget. */
    GatewayTokenBudgetSettlementService settlement() {
        return settlement;
    }

    /** The shared budget, for a test that wants to assert on real state. */
    GatewayTokenBudget budget() {
        return budget;
    }
}

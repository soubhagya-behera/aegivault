package com.aegivault.aegivault.gateway.usage;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated self-service gateway usage endpoint. The actor comes
 * exclusively from the verified JWT subject — never from a query
 * parameter, path variable, request body, or header — so an actor can
 * only ever read their own usage. There is no ADMIN bypass and no
 * cross-user reporting.
 *
 * <p>{@code GET /api/gateway/usage} is strictly read-only: it returns
 * at most the 100 newest usage records (newest first: {@code createdAt}
 * descending, then {@code id} descending, bounded in the
 * repository/database query) plus the actor's database-side aggregate
 * over all persisted rows. History items carry usage metadata only;
 * the entity itself is never exposed.
 */
@RestController
@RequestMapping("/api/gateway/usage")
@RequiredArgsConstructor
public class GatewayUsageController {

    private final GatewayUsageQueryService usage;

    /**
     * Returns the currently authenticated actor's gateway usage: at
     * most the 100 newest records plus the aggregate over all of the
     * actor's persisted rows.
     */
    @GetMapping
    public GatewayUsageResponse usage(@AuthenticationPrincipal Jwt jwt) {
        String actorSubject = jwt.getSubject();
        List<GatewayUsageHistoryItemResponse> history = usage.recentHistoryFor(actorSubject).stream()
                .map(GatewayUsageHistoryItemResponse::from)
                .toList();
        GatewayUsageAggregateResponse aggregate =
                GatewayUsageAggregateResponse.from(usage.aggregateFor(actorSubject));
        return new GatewayUsageResponse(history, aggregate);
    }
}

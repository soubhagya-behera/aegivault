package com.aegivault.aegivault.gateway.usage;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated self-service gateway usage endpoints. The actor comes
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
 *
 * <p>{@code GET /api/gateway/usage/aggregate} returns exactly one
 * aggregate object for an explicit UTC half-open window
 * ({@code from} inclusive, {@code to} exclusive — the existing service
 * semantics, never re-implemented here). It is metadata only: no
 * actor subject, no window echoes, no request ids, models, prompt or
 * response content, provider details, or database ids. There is no
 * pagination, and no budget, quota, cost, or pricing anywhere.
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

    /**
     * Returns the authenticated actor's usage aggregate over one explicit
     * UTC time window, {@code from <= createdAt < to}: both bounds are
     * ISO-8601 instants ({@code from} inclusive, {@code to} exclusive).
     * The window is validated by {@link GatewayUsageQueryService} before
     * any query runs, so an invalid interval never reaches the database.
     * Exactly one aggregate object comes back — never a list, never
     * history, never a page.
     */
    @GetMapping("/aggregate")
    public GatewayUsageAggregateResponse aggregate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("from") Instant from,
            @RequestParam("to") Instant to) {
        return GatewayUsageAggregateResponse.from(usage.aggregateFor(jwt.getSubject(), from, to));
    }

    /** A missing window bound is a client error; the message stays safe. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    GatewayUsageError missingWindowBound(MissingServletRequestParameterException ex) {
        return new GatewayUsageError("Both from and to are required ISO-8601 instants.");
    }

    /** A non-instant bound is a client error; the offending text is never echoed. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    GatewayUsageError malformedWindowBound(MethodArgumentTypeMismatchException ex) {
        return new GatewayUsageError("Both from and to must be ISO-8601 instants.");
    }

    /**
     * Window rejections from the query service (a non-positive interval)
     * are client errors too. The message is fixed and safe, so the
     * submitted bounds and the internal validation text stay out of the
     * response.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    GatewayUsageError invalidWindow(IllegalArgumentException ex) {
        return new GatewayUsageError("from must be strictly before to.");
    }
}

package com.aegivault.aegivault.gateway.policy;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated, owner-scoped management surface for gateway usage policy
 * definitions. The owner always comes from the verified JWT subject; the
 * client can never supply or override it (an attempted {@code ownerSubject}
 * property is not bound). This controller is thin: it validates and
 * delegates, and owns no persistence, ownership, or limit logic.
 *
 * <p><strong>Nothing here is enforced.</strong> These endpoints manage
 * definitions only — no gateway traffic path reads them, so creating,
 * editing, or deleting a policy has no runtime effect. Rate limiting remains
 * entirely controlled by {@code GatewayRateLimiter} configuration. There is
 * no policy inheritance, no global policy, and no ADMIN bypass: USER and
 * ADMIN behave identically.
 */
@RestController
@RequestMapping("/api/gateway/policies")
@RequiredArgsConstructor
public class GatewayUsagePolicyController {

    private final GatewayUsagePolicyService policies;

    /** Creates one policy; {@code Location} points at the matching GET. */
    @PostMapping
    public ResponseEntity<GatewayUsagePolicyResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GatewayUsagePolicyRequest request) {
        GatewayUsagePolicyResponse response = policies.create(
                jwt.getSubject(),
                request.name(),
                request.description(),
                request.requestsPerMinute(),
                request.requestsPerDay(),
                request.tokensPerDay(),
                request.enabledOrDefault());
        return ResponseEntity.created(URI.create("/api/gateway/policies/" + response.id()))
                .body(response);
    }

    /** Lists the caller's policies only, newest first; an empty owner gets {@code 200 []}. */
    @GetMapping
    public List<GatewayUsagePolicyResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return policies.list(jwt.getSubject());
    }

    /** Reads one caller's policy; foreign and missing ids are identical 404s. */
    @GetMapping("/{policyId}")
    public GatewayUsagePolicyResponse get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID policyId) {
        return policies.get(jwt.getSubject(), policyId);
    }

    /**
     * Replaces one caller's policy — label, limits, and enabled state — in
     * place: same id, same owner, no new row, {@code updatedAt} advanced.
     * Foreign and missing ids are identical 404s.
     */
    @PutMapping("/{policyId}")
    public GatewayUsagePolicyResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID policyId,
            @Valid @RequestBody GatewayUsagePolicyRequest request) {
        return policies.update(
                jwt.getSubject(),
                policyId,
                request.name(),
                request.description(),
                request.requestsPerMinute(),
                request.requestsPerDay(),
                request.tokensPerDay(),
                request.enabledOrDefault());
    }

    /** Deletes one caller's policy: 204 with no body. Foreign and missing ids are identical 404s. */
    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID policyId) {
        policies.delete(jwt.getSubject(), policyId);
    }

    @ExceptionHandler(GatewayUsagePolicyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    GatewayUsagePolicyError notFound(GatewayUsagePolicyNotFoundException ex) {
        return new GatewayUsagePolicyError("Usage policy not found.");
    }

    /** Domain rejections (blank/overlong labels, non-positive or absent limits) stay generic. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    GatewayUsagePolicyError badRequest(IllegalArgumentException ex) {
        return new GatewayUsagePolicyError("Invalid usage policy request.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    GatewayUsagePolicyError badUuid(MethodArgumentTypeMismatchException ex) {
        return new GatewayUsagePolicyError("Invalid policy id.");
    }
}
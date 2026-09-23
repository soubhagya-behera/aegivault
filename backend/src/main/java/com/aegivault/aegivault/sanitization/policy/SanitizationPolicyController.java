package com.aegivault.aegivault.sanitization.policy;

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
 * Authenticated, owner-scoped policy endpoints. The owner always comes from
 * the verified JWT subject; the client can never supply or override it
 * (an attempted {@code ownerSubject} property is not bound). This controller
 * is thin by design: it validates and delegates, and it owns no
 * persistence, ownership, or rule logic. Deletion removes one owned policy
 * and its rules only: runs created earlier hold copied snapshots, not a
 * reference to the policy, so they are never touched by a delete.
 */
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class SanitizationPolicyController {

    private final SanitizationPolicyService policyService;

    /** Creates a policy; {@code Location} points at the matching GET. */
    @PostMapping
    public ResponseEntity<PolicyResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePolicyRequest request) {
        PolicyResponse response = policyService.create(
                jwt.getSubject(),
                request.name(),
                request.version(),
                request.description(),
                request.rules());
        return ResponseEntity.created(URI.create("/api/policies/" + response.id())).body(response);
    }

    /** Lists the caller's policies only, newest first; an empty owner gets {@code 200 []}. */
    @GetMapping
    public List<PolicyResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return policyService.list(jwt.getSubject());
    }

    /** Reads one caller's policy; foreign and missing ids are identical 404s. */
    @GetMapping("/{policyId}")
    public PolicyResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID policyId) {
        return policyService.get(jwt.getSubject(), policyId);
    }

    /**
     * Replaces one caller's policy — labels and the entire rule set — in
     * place: same id, same owner, no new row, {@code updatedAt} advanced.
     * Foreign and missing ids are identical 404s; runs created earlier keep
     * the snapshots they froze at creation and never observe this change.
     * The response uses the same shape as the matching GET.
     */
    @PutMapping("/{policyId}")
    public PolicyResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdatePolicyRequest request) {
        return policyService.update(
                jwt.getSubject(),
                policyId,
                request.name(),
                request.version(),
                request.description(),
                request.rules());
    }

    /**
     * Deletes one caller's policy and its rules: 204 with no body. Foreign
     * and missing ids are identical 404s. Runs created earlier keep the
     * snapshots they froze at creation — and their artifacts stay
     * downloadable — because neither references the policy row.
     */
    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID policyId) {
        policyService.delete(jwt.getSubject(), policyId);
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    PolicyError notFound(PolicyNotFoundException ex) {
        return new PolicyError("Policy not found.");
    }

    /** Domain rejections (blank/overlong labels, empty or duplicate rules) stay generic. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    PolicyError badRequest(IllegalArgumentException ex) {
        return new PolicyError("Invalid policy request.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    PolicyError badUuid(MethodArgumentTypeMismatchException ex) {
        return new PolicyError("Invalid policy id.");
    }
}

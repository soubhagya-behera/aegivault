package com.aegivault.aegivault.gateway.policy;

import com.aegivault.aegivault.gateway.GatewayAuditService;
import com.aegivault.aegivault.gateway.GatewayUsagePolicyAuditException;
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
 *
 * <p><strong>Lifecycle mutations are auditable.</strong> Each successful
 * create, update, and delete appends exactly one ledger entry through
 * {@link GatewayAuditService}, under its own event type
 * ({@code GATEWAY_USAGE_POLICY_CREATED} / {@code _UPDATED} / {@code _DELETED})
 * — never the runtime enforcement types, which describe a quota decision
 * about one request rather than a change to a definition. The append happens
 * <em>after</em> the service call returns, so the mutation is already
 * committed: a rejected request (validation, or a foreign/missing id) appends
 * nothing, and an append failure is a fail-closed 500 that reports the
 * missing evidence without undoing the change that already committed.
 */
@RestController
@RequestMapping("/api/gateway/policies")
@RequiredArgsConstructor
public class GatewayUsagePolicyController {

    private final GatewayUsagePolicyService policies;

    private final GatewayAuditService audit;

    /**
     * Creates one policy; {@code Location} points at the matching GET.
     *
     * <p>The ledger entry is appended only once the policy is persisted: a
     * rejected create reaches no audit call at all.
     */
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
        // After the mutation, before the response: durable change, then its
        // evidence, then the client hears about it.
        audit.recordPolicyCreated(jwt.getSubject(), response.id());
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
        GatewayUsagePolicyResponse response = policies.update(
                jwt.getSubject(),
                policyId,
                request.name(),
                request.description(),
                request.requestsPerMinute(),
                request.requestsPerDay(),
                request.tokensPerDay(),
                request.enabledOrDefault());
        // Reached only after the update committed, so a rejected or foreign
        // update leaves no UPDATED entry behind.
        audit.recordPolicyUpdated(jwt.getSubject(), response.id());
        return response;
    }

    /**
     * Deletes one caller's policy: 204 with no body. Foreign and missing ids are identical 404s.
     *
     * <p>A DELETED entry is appended after the row is gone. If that append
     * fails, the delete is <em>not</em> undone to manufacture the evidence:
     * the client gets a generic 500 and the deletion stands.
     */
    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID policyId) {
        policies.delete(jwt.getSubject(), policyId);
        audit.recordPolicyDeleted(jwt.getSubject(), policyId);
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

    /**
     * Lifecycle-audit infrastructure failure: the policy mutation has already
     * committed, but the entry that would evidence it could not be stored, so
     * success is not claimed — generic 500 carrying no SQL detail, hash, actor,
     * policy id, or exception text, cause retained in server logs only.
     *
     * <p><strong>Fail closed, but do not pretend to compensate.</strong> The
     * 204/201/200 that would have reported the change is replaced, yet the
     * change itself is not undone: there is no compensation transaction, and a
     * delete in particular is never recreated to make its audit entry
     * succeed. The honest consequence is that a committed mutation may exist
     * with no ledger entry — the failure window is reported, not hidden.
     */
    @ExceptionHandler(GatewayUsagePolicyAuditException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    GatewayUsagePolicyError auditFailed(GatewayUsagePolicyAuditException ex) {
        return new GatewayUsagePolicyError(GatewayUsagePolicyAuditException.MESSAGE);
    }
}
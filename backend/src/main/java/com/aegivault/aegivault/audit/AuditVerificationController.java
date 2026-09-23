package com.aegivault.aegivault.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated ledger integrity endpoint. The ledger is shared rather
 * than owner-scoped, so integrity is global: USER and ADMIN may both call
 * it, and no owner filtering applies — the JWT principal only proves the
 * caller is authenticated.
 *
 * <p>This controller is thin by design: it triggers one full verification
 * through {@link AuditLedgerVerificationService} and renders the safe
 * view. No hash-chain logic lives here, and no ledger content ever leaves
 * through this endpoint — only the verdict, the replayed-entry count, and
 * (when invalid) the failure code.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditVerificationController {

    private final AuditLedgerVerificationService verification;

    /** Replays the current ledger and reports whether it holds. */
    @GetMapping("/verify")
    public AuditVerifyResponse verify(@AuthenticationPrincipal Jwt jwt) {
        try {
            return AuditVerifyResponse.from(verification.verify());
        } catch (RuntimeException ex) {
            throw new AuditVerificationException(ex);
        }
    }

    /**
     * Infrastructure failure while verifying: the replay never ran, so no
     * verdict is claimed — generic 500 with no storage details. A
     * verification that ran and found a break never reaches this handler;
     * it stays a normal 200 with {@code valid=false}.
     */
    @ExceptionHandler(AuditVerificationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    AuditError failed(AuditVerificationException ex) {
        return new AuditError("Unable to verify audit ledger.");
    }
}

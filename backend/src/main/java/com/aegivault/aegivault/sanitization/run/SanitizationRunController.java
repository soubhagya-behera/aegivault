package com.aegivault.aegivault.sanitization.run;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated run read endpoints (single run, owner run listing). The
 * owner always comes from the verified JWT subject; the client can never
 * supply or override it. Ownership is enforced entirely by the
 * owner-scoped {@link SanitizationRunService} queries — this controller
 * performs no ownership checks and holds no business logic.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class SanitizationRunController {

    private final SanitizationRunService runService;

    @GetMapping("/{runId}")
    public SanitizationRunView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        return runService.get(jwt.getSubject(), runId);
    }

    @GetMapping
    public List<SanitizationRunView> list(@AuthenticationPrincipal Jwt jwt) {
        return runService.list(jwt.getSubject());
    }

    @ExceptionHandler(SanitizationRunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    RunError notFound(SanitizationRunNotFoundException ex) {
        return new RunError("Sanitization run not found.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    RunError badUuid(MethodArgumentTypeMismatchException ex) {
        return new RunError("Invalid run id.");
    }
}

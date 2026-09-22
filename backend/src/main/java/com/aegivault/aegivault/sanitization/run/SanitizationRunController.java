package com.aegivault.aegivault.sanitization.run;

import jakarta.validation.Valid;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated run endpoints: execute a stored dataset input, read one
 * run, list the owner's runs. The owner always comes from the verified JWT
 * subject; the client can never supply or override it. This controller
 * performs no ownership checks and holds no business logic: reads delegate
 * to {@link SanitizationRunService}, execution delegates to
 * {@link SanitizationRunExecutor}.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class SanitizationRunController {

    private final SanitizationRunService runService;

    private final SanitizationRunExecutor runExecutor;

    /**
     * Creates and synchronously executes a sanitization run against the
     * dataset's already-uploaded input. The sanitized bytes are captured
     * into artifact storage by the executor; this endpoint streams nothing
     * back, so the engine writes to a discarding sink and the response is
     * the persisted run view (201 whether the run completed or failed —
     * creation succeeded in both cases; the outcome is in the body).
     * Controller holds no transaction: lifecycle persistence around
     * streaming stays exactly where the executor puts it.
     */
    @PostMapping
    public ResponseEntity<SanitizationRunView> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRunRequest request) {
        SanitizationRunView view = runExecutor.executeStoredCsv(
                jwt.getSubject(),
                request.datasetId(),
                request.toTransformationPlan(),
                request.policyName(),
                request.policyVersion(),
                OutputStream.nullOutputStream());
        return ResponseEntity.created(URI.create("/api/runs/" + view.id())).body(view);
    }

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

    @ExceptionHandler(ReferencedDatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    RunError unknownDataset(ReferencedDatasetNotFoundException ex) {
        return new RunError("Dataset not found.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    RunError badRequest(IllegalArgumentException ex) {
        return new RunError("Invalid run request.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    RunError badUuid(MethodArgumentTypeMismatchException ex) {
        return new RunError("Invalid run id.");
    }
}

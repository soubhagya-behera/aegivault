package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * run, list the owner's runs, download one run's sanitized output. The
 * owner always comes from the verified JWT subject; the client can never
 * supply or override it. This controller performs no ownership checks and
 * holds no business logic: reads delegate to {@link SanitizationRunService},
 * execution delegates to {@link SanitizationRunExecutor}, and artifact
 * bytes come from {@link SanitizationArtifactStore}.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class SanitizationRunController {

    /**
     * Download media type: sanitized CSV is UTF-8 text, declared as
     * {@code text/csv; charset=UTF-8}.
     */
    private static final MediaType CSV_MEDIA_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final SanitizationRunService runService;

    private final SanitizationRunExecutor runExecutor;

    private final SanitizationArtifactStore artifactStore;

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

    /**
     * Downloads the sanitized output of one owned, completed run. The owner
     * comes from the verified JWT subject, never from the request; the
     * artifact store is the only reader of the bytes, and it applies the
     * ownership check, the completed-run requirement, and the
     * missing-artifact rule as one indistinguishable "not found" — so a
     * foreign run, a missing run, an unfinished run, and a run without an
     * artifact all produce the same 404 body. Nothing is parsed,
     * re-generated, or re-sanitized here: the stored bytes are streamed
     * as-is.
     *
     * <p>Streaming: the store's stream is handed to Spring as an
     * {@link InputStreamResource}, which the message converter copies to
     * the response with a fixed buffer — no {@code byte[]} body, so no
     * second full in-memory copy of an artifact that may be tens of MiB.
     * The response is chunked (no pre-computed length) rather than reading
     * the stream twice.
     *
     * <p>The filename is derived only from the validated run id, so no
     * dataset name, original filename, policy label, or any other
     * client-controlled value can reach the {@code Content-Disposition}
     * header, and it is built through {@link ContentDisposition} so header
     * injection is impossible by construction.
     */
    @GetMapping("/{runId}/artifact")
    public ResponseEntity<InputStreamResource> downloadArtifact(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        InputStream artifact = artifactStore.openArtifact(jwt.getSubject(), runId);
        return ResponseEntity.ok()
                .contentType(CSV_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentFilename(runId))
                .body(new InputStreamResource(artifact));
    }

    private static String attachmentFilename(UUID runId) {
        return ContentDisposition.attachment()
                .filename("sanitized-" + runId + ".csv")
                .build()
                .toString();
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

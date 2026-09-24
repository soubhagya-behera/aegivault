package com.aegivault.aegivault.dataset;

import com.aegivault.aegivault.dataset.csv.CsvParseException;
import com.aegivault.aegivault.dataset.profile.DatasetProfileResponse;
import com.aegivault.aegivault.dataset.profile.DatasetProfileService;
import com.aegivault.aegivault.dataset.profile.DatasetProfilingService;
import com.aegivault.aegivault.dataset.profile.TransformationPreviewResponse;
import com.aegivault.aegivault.dataset.profile.TransformationPreviewService;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
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
 * Authenticated dataset endpoints. The owner always comes from the verified
 * JWT subject; the client can never supply or override it.
 */
@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    private final DatasetProfileService profileService;

    private final DatasetProfilingService profilingService;

    private final TransformationPreviewService previewService;

    @PostMapping
    public ResponseEntity<DatasetResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateDatasetRequest request) {
        DatasetResponse response = datasetService.create(
                jwt.getSubject(), request.name(), request.originalFilename());
        return ResponseEntity.created(URI.create("/api/datasets/" + response.id())).body(response);
    }

    @GetMapping
    public List<DatasetResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return datasetService.list(jwt.getSubject());
    }

    @GetMapping("/{id}")
    public DatasetResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return datasetService.get(jwt.getSubject(), id);
    }

    /**
     * Returns the stored schema/PII profile of an owned dataset. The owner
     * comes from the verified JWT subject; the response is the profile as
     * persisted — profiling is never re-run here. A missing dataset, a
     * foreign dataset, and a dataset with no persisted profile yet all
     * produce the same generic 404.
     */
    @GetMapping("/{datasetId}/profile")
    public DatasetProfileResponse getProfile(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID datasetId) {
        return profileService.getProfile(jwt.getSubject(), datasetId);
    }

    /**
     * Explicitly profiles the stored CSV input of an owned dataset and
     * persists the result, replacing any previous profile. The owner comes
     * from the verified JWT subject; the request carries no body — the
     * already-uploaded input is read server-side through
     * {@code DatasetInputSource} — and the stored CSV is never modified.
     * A missing dataset, a foreign dataset, and a dataset with no uploaded
     * input yet all produce the same generic 404.
     */
    @PostMapping("/{datasetId}/profile")
    public DatasetProfileResponse profile(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID datasetId) {
        return profilingService.profileStoredInput(jwt.getSubject(), datasetId);
    }

    /**
     * Previews the default transformation strategy for each PII type
     * detected in an owned dataset's persisted profile. Strictly read-only:
     * the persisted profile is read as-is (profiling is never re-run, the
     * raw CSV is never opened), and nothing is created or modified — no
     * policy, no run, no audit entry, no dataset/profile/CSV change. The
     * owner comes from the verified JWT subject; a missing dataset, a
     * foreign dataset, and a dataset with no persisted profile yet all
     * produce the same generic 404.
     */
    @GetMapping("/{datasetId}/profile/transformation-preview")
    public TransformationPreviewResponse previewTransformation(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID datasetId) {
        return previewService.preview(jwt.getSubject(), datasetId);
    }

    /**
     * Stores (or replaces) the raw CSV input of an owned dataset. The body
     * is streamed straight from the servlet request into storage — never
     * pre-buffered by a message converter — so the shared 10 MiB bound is
     * enforced before the full body ever sits in memory. No parsing,
     * detection, or execution happens here.
     */
    @PostMapping(value = "/{id}/input", consumes = "text/csv")
    public DatasetInputResponse storeInput(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
        try (InputStream input = request.getInputStream()) {
            datasetService.storeInput(jwt.getSubject(), id, input);
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
        return new DatasetInputResponse(id);
    }

    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    @ExceptionHandler(DatasetInputTooLargeException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    DatasetError tooLarge(DatasetInputTooLargeException ex) {
        return new DatasetError(ex.getMessage());
    }

    /**
     * Stored CSV that cannot be discovered safely (ragged rows, blank or
     * duplicate headers, violated CSV limits). The domain message carries
     * structural facts only — never values — so it is rendered verbatim in
     * the existing error shape.
     */
    @ExceptionHandler(CsvParseException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    DatasetError unprocessableCsv(CsvParseException ex) {
        return new DatasetError(ex.getMessage());
    }

    /**
     * A detected PII type with no default transformation strategy. Fail-closed
     * by design: the preview never invents a strategy, and the domain message
     * names the type only, so it is rendered verbatim in the existing error
     * shape. Defensive today — the default policy covers all eleven types.
     */
    @ExceptionHandler(MissingTransformationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError missingDefaultStrategy(MissingTransformationException ex) {
        return new DatasetError(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badUuid(MethodArgumentTypeMismatchException ex) {
        return new DatasetError("Invalid dataset id.");
    }
}

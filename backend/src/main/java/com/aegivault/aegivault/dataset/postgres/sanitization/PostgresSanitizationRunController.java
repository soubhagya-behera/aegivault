package com.aegivault.aegivault.dataset.postgres.sanitization;

import com.aegivault.aegivault.dataset.DatasetError;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.sanitization.policy.PolicyNotFoundException;
import com.aegivault.aegivault.sanitization.run.ReferencedDatasetNotFoundException;
import com.aegivault.aegivault.sanitization.run.SanitizationRunNotFoundException;
import com.aegivault.aegivault.sanitization.run.SanitizationRunView;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunJobLaunchException;
import com.aegivault.aegivault.sanitization.run.job.SanitizationRunNotLaunchableException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated creation of one PostgreSQL sanitization run for a dataset the
 * caller owns and has bound to a PostgreSQL table.
 *
 * <p><strong>The actor comes only from the verified token.</strong> The owner is
 * {@code jwt.getSubject()}, and it is not read from the body, a query parameter,
 * or a header — the request has no field that could carry one.
 *
 * <p><strong>The binding must already exist.</strong> This endpoint never
 * discovers a table, never selects the first table, and never creates a binding;
 * the existing owner-scoped binding is the sole source of schema and table, and
 * it is never updated, deleted, or swapped here.
 *
 * <p><strong>Nothing is read from PostgreSQL during the request.</strong> The run
 * is queued and submitted, and the response returns; the source is only read
 * later by the background worker.
 *
 * <p><strong>{@code 202 Accepted}, not {@code 201}.</strong> The run is queued
 * and still running asynchronously, so the run is not yet in its completed
 * state. The {@code Location} header points at the existing run resource,
 * {@code /api/runs/{runId}}, which is where the caller polls for status. The
 * body is the existing {@link SanitizationRunView}: operation metadata only, no
 * credentials, no JDBC detail, no schema or table, and no row data.
 *
 * <p><strong>Failures are indistinguishable.</strong> A missing dataset, a
 * foreign dataset, a dataset with no binding, and a missing or foreign policy
 * all produce the same generic 404, so no response reveals which reference
 * failed or whether it exists.
 */
@RestController
@RequestMapping("/api/datasets/{datasetId}/postgres")
@RequiredArgsConstructor
public class PostgresSanitizationRunController {

    /** Existing run resource, so the caller polls one established location. */
    private static final String RUN_LOCATION = "/api/runs/";

    private final PostgresSanitizationRunRequestService runRequests;

    /**
     * Queues and launches a PostgreSQL sanitization run.
     *
     * <p>Returns as soon as the run is persisted and handed to the existing
     * background launcher. The status is {@code QUEUED}: this method does not
     * wait for {@code COMPLETED}, and sanitization happens in the worker.
     *
     * <p>Nothing is profiled, inferred, or selected here. The policy is explicit
     * in the request, and the caller-supplied policy id is resolved
     * owner-scoped and frozen into the run snapshot at creation.
     *
     * @param jwt verified token; its subject is the only accepted owner
     * @param datasetId bound dataset to sanitize, must belong to the caller
     * @param request the one explicit input: the caller's policy id
     * @return {@code 202} with the run resource location and the persisted
     *         queued run
     */
    @PostMapping("/runs")
    public ResponseEntity<SanitizationRunView> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID datasetId,
            @Valid @RequestBody PostgresRunCreationRequest request) {
        SanitizationRunView queued =
                runRequests.queueAndLaunch(jwt.getSubject(), datasetId, request.policyId());
        return ResponseEntity.accepted()
                .location(URI.create(RUN_LOCATION + queued.id()))
                .body(queued);
    }

    /**
     * A missing dataset, a foreign dataset, a dataset with no PostgreSQL
     * binding, and a missing or foreign policy are all this one generic 404 —
     * the request names three resources the caller must own, and the response
     * must not reveal which one failed.
     */
    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    /** Same generic 404: the policy is named by the caller too, so it is not
     * reported separately. */
    @ExceptionHandler(PolicyNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError unknownPolicy(PolicyNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    /**
     * A dataset the run service refuses as not the caller's, and a run row that
     * cannot be re-read, are also this same generic 404, so none of the
     * resources the caller named can be probed for existence.
     */
    @ExceptionHandler({ReferencedDatasetNotFoundException.class,
            SanitizationRunNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError missingRunResource(RuntimeException ex) {
        return new DatasetError("Dataset not found.");
    }

    /**
     * A malformed dataset id or a request the validator rejected: a client
     * error. Neither the submitted text nor the reason is echoed.
     */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badRequest(RuntimeException ex) {
        return new DatasetError("Invalid run request.");
    }

    /**
     * The run was created but the bounded pool refused the submission. The
     * launcher has already left the run {@code QUEUED} and launchable, and this
     * controller does not mark it running, fail it, or retry it — so the caller
     * is told the launch did not happen rather than shown a run that will never
     * execute. The response names no pool, queue, thread, or executor detail;
     * the cause stays in server logs.
     */
    @ExceptionHandler(SanitizationRunJobLaunchException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    DatasetError launchUnavailable(SanitizationRunJobLaunchException ex) {
        return new DatasetError(SanitizationRunJobLaunchException.MESSAGE);
    }

    /**
     * The run was not eligible for submission. Reported with the same safe
     * status as a refused launch, since both mean "not started now", and
     * neither exposes run state beyond that.
     */
    @ExceptionHandler(SanitizationRunNotLaunchableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    DatasetError notLaunchable(SanitizationRunNotLaunchableException ex) {
        return new DatasetError(SanitizationRunNotLaunchableException.MESSAGE);
    }
}
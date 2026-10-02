package com.aegivault.aegivault.dataset.postgres.profiling;

import com.aegivault.aegivault.dataset.DatasetError;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.profile.DatasetProfileResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated profiling of the PostgreSQL table an owned dataset is bound to.
 *
 * <p><strong>The actor comes only from the verified token.</strong> The owner is
 * {@code jwt.getSubject()} and nothing else. This operation takes no request
 * body at all: there is no parameter for an owner, a schema, a table, a policy,
 * a row limit, or a credential, so none can be supplied — and a body a caller
 * happens to send is simply not read, which is the existing behaviour for an
 * endpoint that declares none.
 *
 * <p><strong>The binding is the only source of the table.</strong> A PostgreSQL
 * binding must already exist; this endpoint never falls back to the dataset's CSV
 * input and never discovers or guesses a table. Which table is profiled is
 * decided entirely by the owner-scoped binding that a previous call created.
 *
 * <p><strong>Nothing is re-implemented.</strong> Discovery, bounded row reading,
 * PII detection, and profile persistence all happen inside
 * {@link PostgresDatasetProfilingService}; this controller only selects the
 * response status and maps the service's existing safe failures. It holds no
 * repository, no sanitizer, no run executor, and no artifact store, so none of
 * those capabilities is reachable from here.
 *
 * <p><strong>The response is the existing CSV profile shape.</strong>
 * {@link DatasetProfileResponse} is reused unchanged rather than a
 * PostgreSQL-specific view being introduced, so a profile is one concept with one
 * representation: dataset and column metadata, counts, rates, and detected types —
 * never a sampled value, a row value, a credential, or a JDBC detail.
 *
 * <p><strong>Profiling is synchronous and bounded.</strong> No run is created, no
 * background work is launched, and no artifact is written; the profile covers the
 * rows the existing bounded row stream delivered, exactly as the internal service
 * documents.
 */
@RestController
@RequestMapping("/api/datasets/{datasetId}/postgres")
@RequiredArgsConstructor
public class PostgresDatasetProfilingController {

    private final PostgresDatasetProfilingService profiles;

    /**
     * Profiles the table bound to an owned dataset and returns the persisted
     * profile.
     *
     * <p>Repeatable: a second call replaces the stored profile through the
     * existing {@code saveProfile} semantics, so one profile row per dataset
     * survives and no stale column or detection rows are left behind.
     *
     * <p>A table with no rows yields a valid empty profile: the discovered
     * columns remain, their counts are zero, and no finding is invented.
     *
     * @param jwt verified token; its subject is the only accepted owner
     * @param datasetId bound dataset to profile, must belong to the caller
     * @return the persisted profile, metadata and counts only
     */
    @PostMapping("/profile")
    public DatasetProfileResponse profile(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID datasetId) {
        return profiles.profile(jwt.getSubject(), datasetId);
    }

    /**
     * A missing dataset, a foreign dataset, and a dataset with no PostgreSQL
     * binding are all the same generic 404 — so this endpoint reveals neither
     * whether the dataset exists nor whether it is bound, and never falls back to
     * another source.
     */
    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    /**
     * No source configured, a bound table that no longer exists, a source that
     * could not be read, or a failure while analyzing rows: one fixed safe answer
     * with no JDBC URL, host, port, database, username, password, SQL, driver
     * text, schema or table name, or row value. The cause stays in server logs.
     *
     * <p>A stale binding fails this way without being deleted or repaired, and
     * because the profile is only saved after profiling fully succeeds, no partial
     * profile is written.
     */
    @ExceptionHandler(PostgresDatasetProfilingException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    DatasetError profilingUnavailable(PostgresDatasetProfilingException ex) {
        return new DatasetError(PostgresDatasetProfilingException.MESSAGE);
    }

    /** A malformed dataset id is a client error; the text is not echoed. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badUuid(MethodArgumentTypeMismatchException ex) {
        return new DatasetError("Invalid dataset id.");
    }
}
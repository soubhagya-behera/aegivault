package com.aegivault.aegivault.dataset.postgres.discovery;

import com.aegivault.aegivault.dataset.DatasetError;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
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
 * Authenticated, read-only PostgreSQL table discovery for an owned dataset.
 *
 * <p><strong>The actor comes only from the verified token.</strong> The owner is
 * {@code jwt.getSubject()} and nothing else: no query parameter, header, body,
 * or path variable can influence it, so a caller cannot discover tables against
 * another actor's dataset by claiming to be them.
 *
 * <p><strong>The dataset id is context, not a scope change.</strong> It
 * establishes that the caller owns the dataset the tables would be chosen for; it
 * does not select, bind, or narrow which schema is inspected. The configured
 * schema is fixed, so this endpoint cannot be pointed at another schema.
 *
 * <p><strong>Metadata only.</strong> The response is a dedicated immutable DTO
 * with names, ordinal positions, and type names — no row values, samples, PII,
 * counts, credentials, or SQL. The controller opens no connection and builds no
 * statement; it delegates to the service, which reuses the existing discovery
 * boundary.
 */
@RestController
@RequestMapping("/api/datasets/{datasetId}/postgres")
@RequiredArgsConstructor
public class PostgresTableDiscoveryController {

    private final PostgresDatasetTableDiscoveryService discovery;

    /**
     * Lists the base tables in the configured schema.
     *
     * <p>An empty schema is a normal {@code 200} with an empty table list, not an
     * error. Discovery requires no binding: a dataset with none can still see
     * what is available, and nothing is bound as a side effect of looking.
     *
     * @param jwt verified token; its subject is the only accepted owner
     * @param datasetId dataset establishing ownership context
     * @return the configured schema and its discovered tables
     */
    @GetMapping("/tables")
    public PostgresTablesResponse listTables(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID datasetId) {
        return discovery.listTables(jwt.getSubject(), datasetId);
    }

    /** A missing and a foreign dataset are the same generic 404. */
    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    /**
     * No source configured, and a source that could not be read, are the same
     * generic 503 — so this endpoint is not a probe for which one it was.
     */
    @ExceptionHandler({
            PostgresSourceUnavailableException.class,
            PostgresSchemaDiscoveryUnavailableException.class
    })
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    DatasetError sourceUnavailable(RuntimeException ex) {
        return new DatasetError(PostgresSourceUnavailableException.MESSAGE);
    }

    /** A malformed dataset id is a client error; the offending text is not echoed. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badUuid(MethodArgumentTypeMismatchException ex) {
        return new DatasetError("Invalid dataset id.");
    }
}
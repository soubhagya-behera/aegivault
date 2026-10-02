package com.aegivault.aegivault.dataset.postgres.binding;

import com.aegivault.aegivault.dataset.DatasetError;
import com.aegivault.aegivault.dataset.DatasetNotFoundException;
import com.aegivault.aegivault.dataset.postgres.discovery.PostgresSchemaDiscoveryUnavailableException;
import com.aegivault.aegivault.dataset.postgres.discovery.PostgresSourceUnavailableException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
 * Authenticated binding of an owned dataset to one discovered PostgreSQL base
 * table.
 *
 * <p><strong>The actor comes only from the verified token.</strong> The owner is
 * {@code jwt.getSubject()} and nothing else; the request body carries only a
 * schema and a table, so a caller cannot bind against another actor's dataset by
 * claiming to be them.
 *
 * <p><strong>All the real work already exists.</strong> This controller adds no
 * validation, no discovery, and no existence check of its own: identifier
 * grammar, owner scoping, base-table verification, and the one-binding-per-dataset
 * rule all belong to {@link PostgresDatasetBindingService}, which is called
 * directly. The controller touches no repository and opens no connection, so the
 * only PostgreSQL interaction anywhere on this path is the existing metadata
 * discovery inside that service.
 *
 * <p><strong>Create and read; never modify.</strong> Creation records an
 * association and reading reports it, but there is deliberately no update,
 * rebind, or delete route: reassignment stays an explicit delete-then-bind in a
 * later milestone, so exposing an overwrite now would invite rebinding by
 * accident.
 *
 * <p><strong>The read touches no PostgreSQL.</strong> {@code GET /binding}
 * returns the stored binding row and opens no connection, does not rediscover or
 * verify the table, and reads no rows, so it cannot reflect a source that has
 * since changed.
 *
 * <p><strong>The dataset's source type is not changed.</strong> Binding records an
 * additional source association; {@code Dataset.source_type} still reads CSV,
 * and nothing here widens it. Deciding what that field means for a
 * PostgreSQL-backed dataset is a later, explicit API decision.
 */
@RestController
@RequestMapping("/api/datasets/{datasetId}/postgres")
@RequiredArgsConstructor
public class PostgresDatasetBindingController {

    private final PostgresDatasetBindingService bindings;

    /**
     * Binds an owned dataset to a discovered base table.
     *
     * <p>Returns {@code 201} with a {@code Location} header pointing at this
     * resource, matching the project's other creation endpoints. The binding
     * stores schema and table metadata only; source credentials remain
     * configuration and are never accepted, persisted, or echoed.
     *
     * @param jwt verified token; its subject is the only accepted owner
     * @param datasetId dataset to bind, must belong to the caller
     * @param request schema and table only
     * @return the persisted binding, without owner or connection detail
     */
    @PostMapping("/binding")
    public ResponseEntity<PostgresBindingResponse> bind(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID datasetId,
            @Valid @RequestBody CreatePostgresBindingRequest request) {
        PostgresDatasetBinding binding = bindings.bind(
                jwt.getSubject(), datasetId, request.schemaName(), request.tableName());
        return ResponseEntity
                .created(URI.create("/api/datasets/" + datasetId + "/postgres/binding"))
                .body(PostgresBindingResponse.from(binding));
    }

    /**
     * Returns the caller's current binding for this dataset, if it has one.
     *
     * <p><strong>Strictly read-only and metadata-only.</strong> This reads the
     * binding row and nothing else: it opens no PostgreSQL connection, does not
     * rediscover or verify the table, reads no rows, and does not profile,
     * sanitize, or create a run. The stored binding is the entire source of the
     * answer, so the response cannot reflect a table that has since changed and
     * cannot reveal anything the owner has not already bound.
     *
     * <p>A dataset the caller owns with no PostgreSQL binding is the same
     * generic 404 as a foreign or missing dataset, so this cannot be used to
     * discover whether somebody else has bound a dataset. Nothing is mutated:
     * there is no update, rebind, or delete here, and binding remains a
     * one-binding-per-dataset rule enforced by the service.
     *
     * @param jwt verified token; its subject is the only accepted owner
     * @param datasetId dataset whose binding to read, must belong to the caller
     * @return the stored binding's metadata, without owner or connection detail
     */
    @GetMapping("/binding")
    public PostgresBindingResponse getBinding(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID datasetId) {
        PostgresDatasetBinding binding = bindings.get(jwt.getSubject(), datasetId);
        return PostgresBindingResponse.from(binding);
    }

    /** A missing and a foreign dataset are the same generic 404. */
    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    /**
     * One binding per dataset is a rule, not an overwrite. A second attempt is a
     * conflict and the existing binding is left exactly as it is, so the old
     * schema and table are never disclosed and a dataset can never be silently
     * re-pointed. Reassignment stays an explicit delete-then-bind.
     */
    @ExceptionHandler(PostgresDatasetAlreadyBoundException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    DatasetError alreadyBound(PostgresDatasetAlreadyBoundException ex) {
        return new DatasetError(PostgresDatasetAlreadyBoundException.MESSAGE);
    }

    /**
     * The table is not a discovered base table, no source is configured, or the
     * source could not be read. All of these are one fixed safe answer, so the
     * endpoint cannot be used to discover which tables or schemas exist, and no
     * JDBC, SQL, or driver detail is returned.
     */
    @ExceptionHandler({
            PostgresDatasetBindingSourceException.class,
            PostgresSourceUnavailableException.class,
            PostgresSchemaDiscoveryUnavailableException.class
    })
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    DatasetError sourceUnavailable(RuntimeException ex) {
        return new DatasetError(PostgresSourceUnavailableException.MESSAGE);
    }

    /**
     * A blank or overlong identifier is a client error, and so is an identifier
     * the strict grammar refuses — a dot, quote, semicolon, space, or wildcard.
     * The offending text is never echoed and the message names no schema or table.
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            IllegalArgumentException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError invalidRequest(Exception ex) {
        return new DatasetError("Invalid PostgreSQL binding request.");
    }

    /** A malformed dataset id is a client error; the text is not echoed. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badUuid(MethodArgumentTypeMismatchException ex) {
        return new DatasetError("Invalid dataset id.");
    }
}
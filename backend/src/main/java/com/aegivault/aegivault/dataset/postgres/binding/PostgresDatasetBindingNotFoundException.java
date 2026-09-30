package com.aegivault.aegivault.dataset.postgres.binding;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;

/**
 * Thrown when no binding exists for the calling owner and dataset id — the
 * dataset is missing, belongs to another owner, or simply has no binding yet.
 * Extends {@link DatasetNotFoundException} so the existing dataset handler
 * renders the same generic 404 body for every case, revealing nothing about
 * which one failed.
 *
 * <p>The message is a fixed string with no identifier in it, so a caller cannot
 * learn from it whether a particular table name exists in the source.
 */
public class PostgresDatasetBindingNotFoundException extends DatasetNotFoundException {

    public PostgresDatasetBindingNotFoundException() {
        super();
    }
}

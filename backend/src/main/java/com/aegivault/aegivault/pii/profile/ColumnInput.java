package com.aegivault.aegivault.pii.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Input to column profiling: a column name plus already-provided sampled values.
 *
 * <p>Ingestion (CSV parsing, file uploads, database reads) is out of scope for
 * this milestone; callers supply values directly. The list may contain nulls.
 * Raw values are never retained beyond profiling: {@link PiiColumnProfiler}
 * analyses a bounded copy and never stores them in the result.
 */
public record ColumnInput(String columnName, List<String> values) {

    public ColumnInput {
        Objects.requireNonNull(columnName, "columnName must not be null");
        if (columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        Objects.requireNonNull(values, "values must not be null");
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}

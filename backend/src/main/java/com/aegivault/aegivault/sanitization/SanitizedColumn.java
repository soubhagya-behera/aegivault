package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of sanitizing one column: sanitized values plus the policy
 * metadata that produced them.
 *
 * <p>Raw input values are deliberately absent — the result holds only the
 * sanitized strings, the column name, the {@link PiiType} the column was
 * sanitized as, and the {@link TransformationStrategy} that was applied. That
 * shape makes accidental serialization of raw data impossible, and callers keep
 * owning the untouched source values (see
 * {@link com.aegivault.aegivault.pii.profile.ColumnInput}).
 *
 * <p>KEEP is the one strategy whose output is identical to its input; choosing
 * it is an explicit policy decision, not a property of this model. A null or
 * blank source value is passed through unchanged by the engine and therefore
 * appears unchanged here.
 *
 * @param columnName       name of the sanitized column, never blank
 * @param piiType          PII type the column was sanitized as, never null
 * @param strategy         strategy that was applied, never null
 * @param sanitizedValues  sanitized values in their original order, never null,
 *                         may contain nulls when the source value was null
 */
public record SanitizedColumn(
        String columnName,
        PiiType piiType,
        TransformationStrategy strategy,
        List<String> sanitizedValues) {

    public SanitizedColumn {
        Objects.requireNonNull(columnName, "columnName must not be null");
        if (columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        Objects.requireNonNull(piiType, "piiType must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(sanitizedValues, "sanitizedValues must not be null");
        sanitizedValues = Collections.unmodifiableList(new ArrayList<>(sanitizedValues));
    }

    /**
     * Number of sanitized values.
     *
     * @return the size of {@link #sanitizedValues()}
     */
    public int valueCount() {
        return sanitizedValues.size();
    }
}

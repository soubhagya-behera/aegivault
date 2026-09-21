package com.aegivault.aegivault.sanitization;

import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.pii.profile.ColumnInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Column-level sanitization: applies one resolved strategy to the sampled
 * values of a column.
 *
 * <p>Input is the existing profiling input ({@link ColumnInput}: a column name
 * plus bounded sampled values, possibly containing nulls) together with the
 * {@link PiiType} already identified for that column and the explicit
 * {@link TransformationPlan}. Detection is not performed here and no CSV,
 * repository, file, HTTP, or persistence access happens here.
 *
 * <p>Raw values are bounded and ephemeral: the source values are read once,
 * transformed in memory, and never retained by this component. The result
 * ({@link SanitizedColumn}) carries sanitized values plus policy metadata only,
 * so raw PII cannot survive in the returned object graph. The source
 * {@link ColumnInput} is never mutated.
 *
 * <p>Determinism is what preserves relationships inside a column: because each
 * strategy derives its output from the value alone, two identical source values
 * always yield the same sanitized value, so repeated identifiers stay repeated.
 */
@Component
public class ColumnSanitizer {

    private final DataSanitizationService sanitization;

    public ColumnSanitizer(DataSanitizationService sanitization) {
        this.sanitization = Objects.requireNonNull(sanitization, "sanitization must not be null");
    }

    /**
     * Sanitizes every value of one column.
     *
     * @param column  column input to sanitize, never null, never mutated
     * @param piiType PII type identified for the column, never null
     * @param plan    plan that maps the type to a strategy, never null
     * @return sanitized values plus the applied policy metadata
     * @throws MissingTransformationException when the plan does not cover the type
     */
    public SanitizedColumn sanitizeColumn(ColumnInput column, PiiType piiType, TransformationPlan plan) {
        Objects.requireNonNull(column, "column must not be null");
        TransformationStrategy strategy = sanitization.resolveStrategy(piiType, plan);
        List<String> values = column.values();
        List<String> sanitized = new ArrayList<>(values.size());
        for (String value : values) {
            sanitized.add(sanitization.sanitize(value, strategy));
        }
        return new SanitizedColumn(column.columnName(), piiType, strategy, sanitized);
    }
}

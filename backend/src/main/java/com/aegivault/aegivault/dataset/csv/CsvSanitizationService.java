package com.aegivault.aegivault.dataset.csv;

import com.aegivault.aegivault.pii.PiiDetection;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import com.aegivault.aegivault.sanitization.DataSanitizationService;
import com.aegivault.aegivault.sanitization.DefaultTransformationPolicy;
import com.aegivault.aegivault.sanitization.MissingTransformationException;
import com.aegivault.aegivault.sanitization.SanitizationException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * End-to-end CSV sanitization pipeline: CSV in, sanitized CSV out.
 *
 * <p>Per record: {@link CsvTokenizer} parses one record, blank data records
 * are skipped by policy, width is checked against the header, each cell is
 * checked with {@link PiiDetectorRegistry} and rewritten with
 * {@link DataSanitizationService} under the caller's explicit
 * {@link TransformationPlan}, then {@link CsvSanitizationWriter} streams the
 * record out. No second parser, detector, registry, strategy, plan, or
 * profiler exists here; every boundary component is reused.
 *
 * <p>CSV semantics match {@link CsvDiscoveryService} because the same
 * {@link CsvTokenizer} plus the same {@link CsvLimits} column/field limits
 * are used: quoted commas, {@code ""} escapes, line breaks in quoted fields,
 * {@code LF}/{@code CRLF}/lone-{@code CR}, empty fields, strict width checks,
 * and fail-fast malformed quoting. Headers are preserved verbatim; a blank
 * header, blank column, or duplicate name (trim/case-folded compare) fails.
 *
 * <p>Multiple-PII rule: when one cell matches several detectors, the single
 * detection whose {@link PiiType#name()} sorts first alphabetically wins
 * (the order the registry already returns). Never Spring bean order, never a
 * score. Blank cells are preserved without detection.
 *
 * <p>Memory is bounded by the limits: input is buffered once within
 * {@link CsvLimits#maxInputBytes()} (same 10 MiB default as discovery), then
 * records are parsed, transformed, and written one at a time. Sampling limits
 * never apply: every non-blank row of the accepted input is processed.
 * Output size may differ (quoting, replacements); it is streamed per record.
 *
 * <p>Plans are explicit: an unmapped detected type fails closed with
 * {@link MissingTransformationException}; a strategy without implementation
 * fails with {@link SanitizationException}. The convenience overload uses
 * {@link DefaultTransformationPolicy#plan()}; the low-level method never
 * hides a policy choice.
 *
 * <p>Stream ownership: the caller owns both streams; neither is closed here
 * and output is flushed before returning. Nothing is logged or persisted,
 * and messages name structure (rows, columns, limits, types, strategies)
 * only, never values or header names.
 */
@Component
public class CsvSanitizationService {

    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private final PiiDetectorRegistry detectors;

    private final DataSanitizationService sanitization;

    private final CsvLimits limits;

    private final CsvTokenizer tokenizer;

    /** Creates the pipeline with {@link CsvLimits#defaults()}. */
    @Autowired
    public CsvSanitizationService(PiiDetectorRegistry detectors, DataSanitizationService sanitization) {
        this(detectors, sanitization, CsvLimits.defaults());
    }

    /** Full constructor with explicit limits. */
    public CsvSanitizationService(
            PiiDetectorRegistry detectors, DataSanitizationService sanitization, CsvLimits limits) {
        this.detectors = Objects.requireNonNull(detectors, "detectors must not be null");
        this.sanitization = Objects.requireNonNull(sanitization, "sanitization must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.tokenizer = new CsvTokenizer(limits.maxColumns(), limits.maxFieldLength());
    }

    /**
     * Sanitizes CSV input under an explicit plan.
     *
     * @param input CSV input, owned and never closed by this service
     * @param output sanitized destination, flushed and never closed here
     * @param plan explicit plan, never null
     * @return structural counts only, never cell values
     */
    public CsvSanitizationResult sanitize(InputStream input, OutputStream output, TransformationPlan plan) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        String text = readText(input);
        String body = text.startsWith(BYTE_ORDER_MARK) ? text.substring(BYTE_ORDER_MARK.length()) : text;
        SanitizeCollector collector = new SanitizeCollector(plan, new CsvSanitizationWriter(output));
        tokenizer.forEachRecord(body, collector);
        return collector.toResult();
    }

    /**
     * Sanitizes CSV input under {@link DefaultTransformationPolicy#plan()}.
     *
     * @param input CSV input, owned and never closed by this service
     * @param output sanitized destination, flushed and never closed here
     * @return structural counts only, never cell values
     */
    public CsvSanitizationResult sanitizeWithDefaultPolicy(InputStream input, OutputStream output) {
        return sanitize(input, output, DefaultTransformationPolicy.plan());
    }

    private String readText(InputStream input) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > limits.maxInputBytes()) {
                    throw new CsvParseException("CSV input exceeds the maximum supported size of "
                            + limits.maxInputBytes() + " bytes.");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
    }

    private static List<String> validateHeader(int rowNumber, List<String> fields) {
        if (isBlankRecord(fields)) {
            throw new CsvParseException("CSV header row " + rowNumber
                    + " is blank: at least one column name is required.");
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            String columnName = fields.get(index);
            if (columnName.isBlank()) {
                throw new CsvParseException("CSV header column " + (index + 1) + " is blank.");
            }
            if (!seen.add(columnName.trim().toLowerCase(Locale.ROOT))) {
                throw new CsvParseException("CSV header column " + (index + 1)
                        + " duplicates an earlier column name.");
            }
        }
        return List.copyOf(fields);
    }

    private static boolean isBlankRecord(List<String> fields) {
        for (String field : fields) {
            if (!field.isBlank()) {
                return false;
            }
        }
        return true;
    }



    /** Parses, transforms, and writes one record at a time; retains no rows. */
    private final class SanitizeCollector implements CsvTokenizer.RecordHandler {

        private final TransformationPlan plan;

        private final CsvSanitizationWriter writer;

        private List<String> header;

        private long dataRowsWritten;

        private long blankRowsSkipped;

        SanitizeCollector(TransformationPlan plan, CsvSanitizationWriter writer) {
            this.plan = plan;
            this.writer = writer;
        }

        @Override
        public void onRecord(int rowNumber, List<String> fields) {
            if (header == null) {
                header = validateHeader(rowNumber, fields);
                writer.writeRecord(header);
                return;
            }
            if (isBlankRecord(fields)) {
                blankRowsSkipped++;
                return;
            }
            if (fields.size() != header.size()) {
                throw new CsvParseException("CSV row " + rowNumber + " has " + fields.size()
                        + " columns but the header has " + header.size() + ".");
            }
            List<String> sanitized = new ArrayList<>(fields.size());
            for (String value : fields) {
                sanitized.add(sanitizeCell(value));
            }
            writer.writeRecord(sanitized);
            dataRowsWritten++;
        }

        private String sanitizeCell(String value) {
            if (value == null || value.isBlank()) {
                return value;
            }
            List<PiiDetection> detections = detectors.detect(value);
            if (detections.isEmpty()) {
                return value;
            }
            PiiType selected = detections.get(0).type();
            return sanitization.sanitize(value, selected, plan);
        }

        CsvSanitizationResult toResult() {
            if (header == null) {
                throw new CsvParseException("CSV input is empty: a header row is required.");
            }
            writer.flush();
            return new CsvSanitizationResult(header.size(), dataRowsWritten, blankRowsSkipped);
        }
    }
}

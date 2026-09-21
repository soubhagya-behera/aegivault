package com.aegivault.aegivault.dataset.csv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * CSV discovery: reads CSV input into a header {@link CsvSchema} plus bounded
 * per-column samples ({@link CsvSample}) for the existing profiling layer.
 *
 * <p>Policy, deliberately strict so a discovered schema always describes the
 * input instead of a repaired version of it:
 *
 * <ul>
 *   <li>the first record is the header; it must be present, must not be blank,
 *       must contain no blank column name, and must contain no duplicate name
 *       (duplicates are compared after trimming and case folding, and are
 *       rejected rather than renamed);</li>
 *   <li>every data record must match the header width exactly — nothing is
 *       dropped, padded, merged, or invented;</li>
 *   <li>all-blank data records (empty lines, whitespace-only lines, and
 *       delimiter-only records such as {@code ,,}) are skipped and counted
 *       neither as sampled nor as encountered rows; a blank first record is a
 *       blank header and fails instead of silently shifting the schema;</li>
 *   <li>column names are preserved verbatim, and quoted fields may contain
 *       delimiters, escaped quotes, and line breaks (see {@link CsvTokenizer}).
 * </ul>
 *
 * <p>Row accounting stays explicit: {@code sampledRowCount} is what was
 * retained for profiling, {@code rowsEncountered} is what was read from the
 * accepted input, and no full dataset row count is invented. The whole
 * accepted input is traversed, so within the configured input-byte limit
 * {@code rowsEncountered} is complete for that input; a larger source is
 * rejected by the limit rather than partially reported.
 *
 * <p>Samples are bounded by {@link CsvLimits#maxSampledRows()} and the caller
 * may request fewer rows for one call. Detection is not performed here: this
 * class only discovers and extracts values, leaving
 * {@code PiiDetectorRegistry}, {@code PiiColumnProfiler}, and
 * {@code DatasetProfiler} responsible for detection and profiling.
 *
 * <p>CSV contents are treated as sensitive: nothing is logged, persisted,
 * transformed, exported, or sent to any network or AI service, and exception
 * messages never carry field values or header names. The input stream belongs
 * to the caller and is never closed here.
 */
@Component
public class CsvDiscoveryService {

    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private final CsvLimits limits;

    private final CsvTokenizer tokenizer;

    /** Creates the discovery service with {@link CsvLimits#defaults()}. */
    @Autowired
    public CsvDiscoveryService() {
        this(CsvLimits.defaults());
    }

    /**
     * @param limits safety limits for discovery, never null
     */
    public CsvDiscoveryService(CsvLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.tokenizer = new CsvTokenizer(limits.maxColumns(), limits.maxFieldLength());
    }

    /**
     * Discovers schema and samples, retaining up to
     * {@link CsvLimits#maxSampledRows()} rows per column.
     *
     * @param input UTF-8 CSV input, owned by the caller
     * @return the discovered schema plus bounded column samples
     * @throws CsvParseException when the input cannot be discovered safely
     */
    public CsvSample discover(InputStream input) {
        return discover(input, limits.maxSampledRows());
    }

    /**
     * Discovers schema and samples with an explicit sample size.
     *
     * @param input          UTF-8 CSV input, owned by the caller
     * @param maxSampledRows data rows to retain per column, between 1 and
     *                       {@link CsvLimits#maxSampledRows()}
     * @return the discovered schema plus bounded column samples
     * @throws CsvParseException        when the input cannot be discovered
     *                                  safely or the requested sample size
     *                                  exceeds the configured maximum
     * @throws IllegalArgumentException when {@code maxSampledRows} is below 1
     */
    public CsvSample discover(InputStream input, int maxSampledRows) {
        Objects.requireNonNull(input, "input must not be null");
        requireSupportedSampleSize(maxSampledRows);
        return discoverText(readText(input), maxSampledRows);
    }

    private CsvSample discoverText(String text, int maxSampledRows) {
        Objects.requireNonNull(text, "text must not be null");
        requireSupportedSampleSize(maxSampledRows);
        String body = text.startsWith(BYTE_ORDER_MARK) ? text.substring(BYTE_ORDER_MARK.length()) : text;
        SampleCollector collector = new SampleCollector(maxSampledRows);
        tokenizer.forEachRecord(body, collector);
        return collector.toSample();
    }

    private void requireSupportedSampleSize(int maxSampledRows) {
        if (maxSampledRows < 1) {
            throw new IllegalArgumentException("maxSampledRows must be at least 1");
        }
        if (maxSampledRows > limits.maxSampledRows()) {
            throw new CsvParseException("Requested CSV sample size " + maxSampledRows
                    + " exceeds the configured maximum of " + limits.maxSampledRows() + ".");
        }
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

    /**
     * Validates one header record and returns its column names.
     *
     * @param rowNumber 1-based line number of the header record
     * @param fields    raw header fields, at least one
     * @return immutable verbatim column names
     * @throws CsvParseException when the header is blank, has a blank column,
     *                           or repeats a column name
     */
    private List<String> validateHeader(int rowNumber, List<String> fields) {
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
            if (!seen.add(normalize(columnName))) {
                throw new CsvParseException("CSV header column " + (index + 1)
                        + " duplicates an earlier column name.");
            }
        }
        return List.copyOf(fields);
    }

    private static String normalize(String columnName) {
        return columnName.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlankRecord(List<String> fields) {
        for (String field : fields) {
            if (!field.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Accumulates the discovered header and bounded samples, one record at a time. */
    private final class SampleCollector implements CsvTokenizer.RecordHandler {

        private final int maxSampledRows;

        private final List<List<String>> columns = new ArrayList<>();

        private List<String> header;

        private int sampledRows;

        private long rowsEncountered;

        SampleCollector(int maxSampledRows) {
            this.maxSampledRows = maxSampledRows;
        }

        @Override
        public void onRecord(int rowNumber, List<String> fields) {
            if (header == null) {
                header = validateHeader(rowNumber, fields);
                for (int index = 0; index < header.size(); index++) {
                    columns.add(new ArrayList<>());
                }
                return;
            }
            if (isBlankRecord(fields)) {
                return;
            }
            if (fields.size() != header.size()) {
                throw new CsvParseException("CSV row " + rowNumber + " has " + fields.size()
                        + " columns but the header has " + header.size() + ".");
            }
            rowsEncountered++;
            if (sampledRows >= maxSampledRows) {
                return;
            }
            for (int index = 0; index < fields.size(); index++) {
                columns.get(index).add(fields.get(index));
            }
            sampledRows++;
        }

        CsvSample toSample() {
            if (header == null) {
                throw new CsvParseException("CSV input is empty: a header row is required.");
            }
            List<List<String>> samples = new ArrayList<>(columns.size());
            for (List<String> column : columns) {
                samples.add(List.copyOf(column));
            }
            return new CsvSample(new CsvSchema(header, true), samples, sampledRows, rowsEncountered);
        }
    }
}

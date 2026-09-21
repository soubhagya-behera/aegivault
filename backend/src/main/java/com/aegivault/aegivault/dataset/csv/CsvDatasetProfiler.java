package com.aegivault.aegivault.dataset.csv;

import com.aegivault.aegivault.pii.profile.ColumnInput;
import com.aegivault.aegivault.pii.profile.DatasetProfile;
import com.aegivault.aegivault.pii.profile.DatasetProfiler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Profiling facade from CSV input to {@link DatasetProfile}.
 *
 * <p>Flow: CSV bytes → {@link CsvDiscoveryService} (schema plus bounded column
 * samples) → {@link ColumnInput} per column → existing {@link DatasetProfiler}
 * → immutable {@link DatasetProfile}. Detection stays in the existing
 * {@code PiiDetectorRegistry} and {@code PiiColumnProfiler}; this class only
 * converts discovered values into profiling input and composes the call, so
 * there is exactly one profiling implementation.
 *
 * <p>The sample size handed to the CSV layer is the profiling layer's own
 * configured limit ({@link DatasetProfiler#maxSampleSizePerColumn()}), so the
 * CSV layer never supplies more rows than the profiler would analyse. A
 * profiling sample limit larger than the configured CSV sample ceiling is
 * rejected by the CSV layer rather than silently truncated.
 *
 * <p>This facade deliberately does none of the following: sanitize or
 * transform data, persist raw data or profiles, write to the database, call
 * Redis, call an AI service, or call any external or network service. The CSV
 * stream belongs to the caller and is never closed here. It is not exposed
 * over REST in this milestone, and it does not record counts back onto the
 * {@code Dataset} entity.
 */
@Component
public class CsvDatasetProfiler {

    private final CsvDiscoveryService discovery;

    private final DatasetProfiler datasetProfiler;

    /**
     * @param discovery       CSV discovery service, never null
     * @param datasetProfiler dataset profiler, never null
     */
    public CsvDatasetProfiler(CsvDiscoveryService discovery, DatasetProfiler datasetProfiler) {
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.datasetProfiler = Objects.requireNonNull(datasetProfiler, "datasetProfiler must not be null");
    }

    /**
     * Discovers and profiles CSV input read from a stream.
     *
     * @param datasetId identifier to correlate the profile with, may be null
     * @param input     UTF-8 CSV input, owned by the caller
     * @return the profiling result; metadata and counts only
     * @throws CsvParseException when the input cannot be discovered safely
     */
    public DatasetProfile profileCsv(UUID datasetId, InputStream input) {
        Objects.requireNonNull(input, "input must not be null");
        return profile(datasetId, discovery.discover(input, sampleSize()));
    }

    /**
     * Discovers and profiles CSV input supplied as text.
     *
     * @param datasetId identifier to correlate the profile with, may be null
     * @param csv       UTF-8 CSV text
     * @return the profiling result; metadata and counts only
     * @throws CsvParseException when the input cannot be discovered safely
     */
    public DatasetProfile profileCsv(UUID datasetId, String csv) {
        Objects.requireNonNull(csv, "csv must not be null");
        InputStream input = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        return profile(datasetId, discovery.discover(input, sampleSize()));
    }

    private int sampleSize() {
        return datasetProfiler.maxSampleSizePerColumn();
    }

    private DatasetProfile profile(UUID datasetId, CsvSample sample) {
        List<String> columnNames = sample.schema().columnNames();
        List<ColumnInput> columns = new ArrayList<>(columnNames.size());
        for (int index = 0; index < columnNames.size(); index++) {
            columns.add(new ColumnInput(columnNames.get(index), sample.columnSamples().get(index)));
        }
        return datasetProfiler.profile(datasetId, columns);
    }
}

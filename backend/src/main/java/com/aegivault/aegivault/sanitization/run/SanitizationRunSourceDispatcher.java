package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.sanitization.SanitizationSourceException;
import com.aegivault.aegivault.sanitization.TransformationPlan;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Selects the {@link SanitizationSourceProvider} that serves a run's recorded
 * {@link SanitizationSourceType}.
 *
 * <p><strong>Selection only, by the recorded value.</strong> Dispatch reads the
 * source kind written on the run row at creation and never infers one from
 * current data — not from whether a dataset happens to have stored input, not
 * from whether a PostgreSQL binding exists. A run therefore cannot be routed to
 * a different source by data that changed after it was queued.
 *
 * <p><strong>Absent providers fail loudly and safely.</strong> With no provider
 * registered for the recorded kind — a PostgreSQL run in a checkout with no
 * PostgreSQL source — this raises the shared safe
 * {@link SanitizationSourceException}, which
 * {@link SanitizationRunExecutor} maps to a {@code FAILED} run with
 * metadata-only detail. The alternative, falling back to another source or
 * leaving the run queued forever, is worse than a recorded failure.
 *
 * <p><strong>Nothing is trusted but the run row.</strong> The owner, dataset, and
 * plan passed to the provider come from the persisted run; no request body,
 * current policy contents, or caller-supplied source location enters here.
 *
 * <p>The provider list is injected as a list so this package never imports a
 * source-specific package, and an empty list is a normal state rather than an
 * error.
 */
@Component
public class SanitizationRunSourceDispatcher {

    private final List<SanitizationSourceProvider> providers;

    public SanitizationRunSourceDispatcher(List<SanitizationSourceProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
    }

    /**
     * Builds the content source for one run of the recorded source kind.
     *
     * @param sourceType kind recorded on the run, never null
     * @param ownerSubject owner recorded on the run row, never blank
     * @param datasetId dataset recorded on the run row, never null
     * @param plan rebuilt from the run's frozen policy snapshot, never null
     * @return the content source, never null; it performs no I/O yet
     * @throws SanitizationSourceException when no provider serves that kind
     */
    public SanitizationContentSource contentFor(
            SanitizationSourceType sourceType,
            String ownerSubject,
            UUID datasetId,
            TransformationPlan plan) {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        SanitizationSourceProvider provider = providers.stream()
                .filter(candidate -> candidate.sourceType() == sourceType)
                .findFirst()
                .orElseThrow(() -> new SanitizationSourceException(
                        new IllegalStateException("no provider for the recorded source type")));
        return provider.contentFor(ownerSubject, datasetId, plan);
    }
}
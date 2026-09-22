package com.aegivault.aegivault.sanitization.run;

import com.aegivault.aegivault.dataset.Dataset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One sanitization operation against one {@link Dataset}: an operation
 * record, not the sanitized file itself.
 *
 * <p>The row holds identity (dataset FK, denormalized {@code ownerSubject}
 * for owner-scoped queries without a join), lifecycle state
 * ({@link RunStatus} plus {@code startedAt}/{@code completedAt}), the
 * immutable policy snapshot taken at creation, structural result counts
 * recorded at completion, and safe failure metadata recorded on failure.
 * It never holds raw CSV content, PII samples, cell values, stack traces,
 * secrets, or tokens.
 *
 * <p>Lifecycle enforcement lives here: {@link #markRunning()},
 * {@link #markCompleted(RunResult)}, and {@link #markFailed(RunFailure)}
 * are the only state changes, and each rejects illegal transitions via the
 * {@link RunStatus} state machine. Policy fields have no setters, so a
 * persisted run's policy record cannot drift when the application's current
 * policy changes later.
 *
 * <p>Mapped 1:1 to the Flyway-managed {@code sanitization_runs} table (V3);
 * Hibernate never modifies the schema ({@code ddl-auto=validate}).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "sanitization_runs")
public class SanitizationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "dataset_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sanitization_runs_dataset"))
    private Dataset dataset;

    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RunStatus status;

    @Column(name = "policy_name", nullable = false)
    private String policyName;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "policy_snapshot", nullable = false)
    private String policySnapshot;

    @Column(name = "input_row_count")
    private Long inputRowCount;

    @Column(name = "output_row_count")
    private Long outputRowCount;

    @Column(name = "blank_rows_skipped")
    private Long blankRowsSkipped;

    @Column(name = "column_count")
    private Integer columnCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_stage")
    private String errorStage;

    @Column(name = "error_message")
    private String errorMessage;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Creates a run in {@link RunStatus#QUEUED} state with the policy frozen
     * from the snapshot. Counts, timestamps (except audit stamps), and
     * failure fields all start null.
     *
     * @param dataset owning dataset, never null; must belong to
     *        {@code ownerSubject} (checked by the service before calling)
     * @param ownerSubject owner copied from the dataset for owner-scoped
     *        queries, never blank
     * @param snapshot frozen policy, never null
     */
    public SanitizationRun(Dataset dataset, String ownerSubject, PolicySnapshot snapshot) {
        this.dataset = Objects.requireNonNull(dataset, "dataset must not be null");
        this.ownerSubject = requireText(ownerSubject, "ownerSubject");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.policyName = snapshot.policyName();
        this.policyVersion = snapshot.policyVersion();
        this.policySnapshot = snapshot.toJson();
        this.status = RunStatus.QUEUED;
    }

    /**
     * Moves {@code QUEUED -> RUNNING} and records the start time.
     *
     * @throws InvalidRunTransitionException when the run is not QUEUED
     */
    public void markRunning() {
        transitionTo(RunStatus.RUNNING);
        this.startedAt = Instant.now();
    }

    /**
     * Moves {@code RUNNING -> COMPLETED} and records structural counts plus
     * the completion time.
     *
     * @param result structural counts, never null
     * @throws InvalidRunTransitionException when the run is not RUNNING
     */
    public void markCompleted(RunResult result) {
        Objects.requireNonNull(result, "result must not be null");
        transitionTo(RunStatus.COMPLETED);
        this.inputRowCount = result.inputRows();
        this.outputRowCount = result.outputRows();
        this.blankRowsSkipped = result.blankRowsSkipped();
        this.columnCount = result.columnCount();
        this.completedAt = Instant.now();
    }

    /**
     * Moves {@code RUNNING -> FAILED} and records safe failure metadata plus
     * the completion time.
     *
     * @param failure structured metadata-only failure, never null
     * @throws InvalidRunTransitionException when the run is not RUNNING
     */
    public void markFailed(RunFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        transitionTo(RunStatus.FAILED);
        this.errorCode = failure.errorCode();
        this.errorStage = failure.errorStage();
        this.errorMessage = failure.errorMessage();
        this.completedAt = Instant.now();
    }

    private void transitionTo(RunStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!status.canTransitionTo(target)) {
            throw new InvalidRunTransitionException(status, target);
        }
        this.status = target;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

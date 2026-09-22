-- V3: sanitization run persistence — one operation record per sanitization run.
--
-- sanitization_runs records a sanitization *operation* against one dataset,
-- not the sanitized file itself. No CSV content, PII samples, cell values,
-- stack traces, secrets, or tokens are stored here: only lifecycle state,
-- an immutable policy snapshot, structural result counts, and safe failure
-- metadata. Artifact storage, background workers, and REST endpoints arrive
-- in later milestones; this table is the seam they will build on.
--
-- Dataset deletion: the FK to datasets is ON DELETE RESTRICT. Runs are
-- operation records that stay explainable after the fact, so a dataset with
-- runs cannot be deleted silently (and no dataset delete path exists yet,
-- which makes RESTRICT inert today and protective tomorrow). Orphaning runs
-- via SET NULL or CASCADE is rejected deliberately; removing a dataset with
-- history will need an explicit, audited decision later.
--
-- Locking: the version column backs JPA optimistic locking (@Version), so
-- two concurrent lifecycle updates (e.g. two workers racing QUEUED ->
-- RUNNING, or COMPLETED racing FAILED) cannot silently overwrite each
-- other — the loser fails instead of corrupting the state machine. No
-- Redis/distributed locks: there is a single database and no worker yet.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid(), plural
-- snake_case tables, TEXT + CHECK instead of enums, explicitly named
-- constraints (pk_ / fk_ / chk_ / uq_) and indexes (idx_).
--
-- Indexes: per-dataset run listing (dataset_id) and owner-scoped single-run
-- lookups (owner_subject). No status index: nothing queries by status yet
-- (no workers, no polling endpoints); adding one is a later decision with a
-- real query behind it.

CREATE TABLE sanitization_runs (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    dataset_id        UUID        NOT NULL,
    owner_subject     TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'QUEUED',
    policy_name       TEXT        NOT NULL,
    policy_version    TEXT        NOT NULL,
    policy_snapshot   TEXT        NOT NULL,
    input_row_count   BIGINT      NULL,
    output_row_count  BIGINT      NULL,
    blank_rows_skipped BIGINT     NULL,
    column_count      INTEGER     NULL,
    started_at        TIMESTAMPTZ NULL,
    completed_at      TIMESTAMPTZ NULL,
    error_code        TEXT        NULL,
    error_stage       TEXT        NULL,
    error_message     TEXT        NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_sanitization_runs PRIMARY KEY (id),
    CONSTRAINT fk_sanitization_runs_dataset
        FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE RESTRICT,
    CONSTRAINT chk_runs_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_runs_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_runs_policy_name_not_blank CHECK (char_length(btrim(policy_name)) > 0),
    CONSTRAINT chk_runs_policy_version_not_blank CHECK (char_length(btrim(policy_version)) > 0),
    CONSTRAINT chk_runs_policy_snapshot_not_blank CHECK (char_length(btrim(policy_snapshot)) > 0),
    CONSTRAINT chk_runs_input_rows_non_negative CHECK (input_row_count IS NULL OR input_row_count >= 0),
    CONSTRAINT chk_runs_output_rows_non_negative CHECK (output_row_count IS NULL OR output_row_count >= 0),
    CONSTRAINT chk_runs_blank_rows_non_negative CHECK (blank_rows_skipped IS NULL OR blank_rows_skipped >= 0),
    CONSTRAINT chk_runs_column_count_non_negative CHECK (column_count IS NULL OR column_count >= 0),
    CONSTRAINT chk_runs_error_on_failed_only CHECK (
        (status = 'FAILED'
            AND error_code IS NOT NULL AND error_stage IS NOT NULL AND error_message IS NOT NULL)
        OR (status <> 'FAILED'
            AND error_code IS NULL AND error_stage IS NULL AND error_message IS NULL)),
    CONSTRAINT chk_runs_completed_at_on_terminal CHECK (
        (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
        OR (status IN ('QUEUED', 'RUNNING')))
);

COMMENT ON TABLE sanitization_runs IS 'One sanitization operation against one dataset: lifecycle state, immutable policy snapshot, structural result counts, and safe failure metadata. No raw data, no artifacts.';
COMMENT ON COLUMN sanitization_runs.dataset_id IS 'Dataset sanitized by this run. ON DELETE RESTRICT: history is never silently orphaned or cascade-deleted.';
COMMENT ON COLUMN sanitization_runs.owner_subject IS 'Owner copied from the dataset at creation; every read is owner-scoped. USER and ADMIN behave identically.';
COMMENT ON COLUMN sanitization_runs.policy_snapshot IS 'Canonical JSON of the frozen type-to-strategy mapping plus policy labels. Immutable after creation; never raw data.';
COMMENT ON COLUMN sanitization_runs.error_message IS 'Metadata-only failure sentence (structure, never values, never stack traces, never secrets).';
COMMENT ON COLUMN sanitization_runs.version IS 'Optimistic-locking version: concurrent lifecycle updates fail loudly instead of overwriting each other.';

-- Per-dataset run listing ("runs of dataset X for owner Y").
CREATE INDEX idx_sanitization_runs_dataset_id ON sanitization_runs (dataset_id);

-- Owner-scoped single-run lookups ("my run by id").
CREATE INDEX idx_sanitization_runs_owner_subject ON sanitization_runs (owner_subject);

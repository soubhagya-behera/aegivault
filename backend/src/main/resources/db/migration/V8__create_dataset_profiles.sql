-- V8: dataset profile persistence — one stored profile per dataset.
--
-- dataset_profiles holds one row per profiled dataset: whose dataset it
-- describes (dataset_id, also the primary key, so at most one stored
-- profile exists per dataset), who owns it (owner_subject, copied from the
-- dataset at save time for join-free owner-scoped reads), and the
-- dataset-level metadata copied verbatim from the profiler result
-- (total_columns, max_sample_size_per_column).
-- dataset_profile_columns holds one row per profiled column: its position
-- in the profiler's deterministic column-name order (column_ordinal),
-- its name, and the supplied/analyzed/analyzable counts. No cell values,
-- no samples, no raw PII — there is deliberately no value column here.
-- dataset_profile_detections holds one row per detected PII type per
-- column: the enum name plus the detection count and the observed
-- detection rate the profiler reported. Columns with no detections simply
-- have no rows here. The composite primary keys make "one row per column
-- position" and "one row per (column, PII type)" database guarantees, and
-- replacing a stored profile deletes the old child rows (orphan removal
-- in the aggregate), so no stale column or detection rows survive a
-- re-save.
--
-- Ownership: owner_subject follows the same convention as datasets, runs,
-- inputs, artifacts, and policies (opaque TEXT, owner_subject = users.id
-- / JWT sub, USER and ADMIN behave identically). No users FK (same
-- deliberate choice as V1/V3/V4/V5/V6/V7).
--
-- Delete behavior: dataset_profiles references datasets ON DELETE CASCADE.
-- A stored profile is derived, recomputable metadata of the dataset's own
-- content — like inputs (V4) and artifacts (V5), not operation history
-- like runs (V3, RESTRICT). Column and detection rows cascade from their
-- parent rows for the same reason. No dataset delete path exists yet, so
-- this is inert today and protective tomorrow.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid() (unused as a
-- default here because profile ids are the dataset ids they describe),
-- plural snake_case tables, TEXT + CHECK instead of enums, explicitly
-- named constraints (pk_ / fk_ / chk_ / uq_) and indexes (idx_),
-- TIMESTAMPTZ in UTC.
--
-- Indexes: owner-scoped single-profile lookups (owner_subject). The child
-- tables need no extra index: their composite primary keys already lead
-- with dataset_id, the only access path.

CREATE TABLE dataset_profiles (
    dataset_id               UUID        NOT NULL,
    owner_subject            TEXT        NOT NULL,
    total_columns            INTEGER     NOT NULL,
    max_sample_size_per_column INTEGER   NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_dataset_profiles PRIMARY KEY (dataset_id),
    CONSTRAINT fk_dataset_profiles_dataset
        FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE CASCADE,
    CONSTRAINT chk_dataset_profiles_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_dataset_profiles_total_columns_non_negative CHECK (total_columns >= 0),
    CONSTRAINT chk_dataset_profiles_max_sample_positive CHECK (max_sample_size_per_column >= 1)
);

COMMENT ON TABLE dataset_profiles IS 'One stored schema/PII profile per dataset: profiler metadata only, never raw CSV values, samples, PII values, or sanitized values.';
COMMENT ON COLUMN dataset_profiles.dataset_id IS 'Profiled dataset; also the primary key, so at most one stored profile exists per dataset. ON DELETE CASCADE: a profile is derived dataset metadata, not history, and vanishes with its dataset.';
COMMENT ON COLUMN dataset_profiles.owner_subject IS 'Owner copied from the dataset at save time; every read is owner-scoped. USER and ADMIN behave identically.';
COMMENT ON COLUMN dataset_profiles.total_columns IS 'Column count reported by the profiler, stored verbatim.';
COMMENT ON COLUMN dataset_profiles.max_sample_size_per_column IS 'Per-column sample limit reported by the profiler, stored verbatim.';

-- Owner-scoped single-profile lookups ("my profile for dataset X").
CREATE INDEX idx_dataset_profiles_owner_subject ON dataset_profiles (owner_subject);

CREATE TABLE dataset_profile_columns (
    dataset_id          UUID    NOT NULL,
    column_ordinal      INTEGER NOT NULL,
    column_name         TEXT    NOT NULL,
    supplied_value_count   INTEGER NOT NULL,
    analyzed_value_count   INTEGER NOT NULL,
    analyzable_value_count INTEGER NOT NULL,
    CONSTRAINT pk_dataset_profile_columns PRIMARY KEY (dataset_id, column_ordinal),
    CONSTRAINT fk_dataset_profile_columns_profile
        FOREIGN KEY (dataset_id) REFERENCES dataset_profiles (dataset_id) ON DELETE CASCADE,
    CONSTRAINT chk_profile_columns_ordinal_non_negative CHECK (column_ordinal >= 0),
    CONSTRAINT chk_profile_columns_name_not_blank CHECK (char_length(btrim(column_name)) > 0),
    CONSTRAINT chk_profile_columns_supplied_non_negative CHECK (supplied_value_count >= 0),
    CONSTRAINT chk_profile_columns_analyzed_non_negative CHECK (analyzed_value_count >= 0),
    CONSTRAINT chk_profile_columns_analyzable_non_negative CHECK (analyzable_value_count >= 0)
);

COMMENT ON TABLE dataset_profile_columns IS 'One row per profiled column: ordinal in deterministic column-name order, name, and supplied/analyzed/analyzable counts. No value column exists by design.';
COMMENT ON COLUMN dataset_profile_columns.column_ordinal IS 'Zero-based position in the profiler deterministic column-name order; part of the primary key, so one row exists per column position.';
COMMENT ON COLUMN dataset_profile_columns.column_name IS 'Column name as profiled, preserved verbatim. Carries no values.';

CREATE TABLE dataset_profile_detections (
    dataset_id      UUID            NOT NULL,
    column_ordinal  INTEGER         NOT NULL,
    pii_type        TEXT            NOT NULL,
    detection_count INTEGER         NOT NULL,
    detection_rate  DOUBLE PRECISION NOT NULL,
    CONSTRAINT pk_dataset_profile_detections PRIMARY KEY (dataset_id, column_ordinal, pii_type),
    CONSTRAINT fk_dataset_profile_detections_column
        FOREIGN KEY (dataset_id, column_ordinal)
        REFERENCES dataset_profile_columns (dataset_id, column_ordinal) ON DELETE CASCADE,
    CONSTRAINT chk_profile_detections_pii_type CHECK (pii_type IN (
        'EMAIL', 'PHONE', 'PERSON_NAME', 'ADDRESS', 'CREDIT_CARD', 'IP_ADDRESS',
        'UUID', 'API_KEY', 'PASSWORD', 'JWT', 'CUSTOM_IDENTIFIER')),
    CONSTRAINT chk_profile_detections_pii_type_not_blank CHECK (char_length(btrim(pii_type)) > 0),
    CONSTRAINT chk_profile_detections_count_non_negative CHECK (detection_count >= 0),
    CONSTRAINT chk_profile_detections_rate_range CHECK (detection_rate >= 0.0 AND detection_rate <= 1.0)
);

COMMENT ON TABLE dataset_profile_detections IS 'One row per detected PII type per column: enum name plus the profiler-reported count and observed rate. Columns with no detections have no rows here.';
COMMENT ON COLUMN dataset_profile_detections.pii_type IS 'PII type name from the closed PiiType set; part of the primary key, so it never repeats within one column.';
COMMENT ON COLUMN dataset_profile_detections.detection_count IS 'How many analyzed values contained this PII type, stored verbatim from the profiler.';
COMMENT ON COLUMN dataset_profile_detections.detection_rate IS 'Observed fraction of analyzed non-blank values containing this type, stored verbatim from the profiler. Observed rate only, not confidence or accuracy.';

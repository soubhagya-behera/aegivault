-- V4: dataset input storage — one stored CSV payload per dataset.
--
-- dataset_inputs holds the raw uploaded bytes of one dataset: the content
-- the future POST /api/runs endpoint will feed to the sanitization engine
-- through DatasetInputSource. Datasets remain metadata-only; this table
-- exists so byte storage stays a separate boundary that metadata queries
-- never touch.
--
-- Backend choice: PostgreSQL BYTEA. The application already depends on
-- PostgreSQL, the CSV input model is already bounded (10 MiB via
-- CsvLimits.DEFAULT_MAX_INPUT_BYTES), and the project is a modular
-- monolith — so no filesystem, S3, or other external infrastructure is
-- introduced for the MVP. BYTEA keeps one transactional store and makes
-- the 10 MiB bound enforceable in both the application and the schema.
-- This is NOT a claim that BYTEA scales to large-dataset production use;
-- chunked/object storage remains a later decision with its own ADR.
--
-- Delete behavior: ON DELETE CASCADE. Stored bytes are the dataset's own
-- content, not operation history: removing a dataset removes its bytes, so
-- no orphaned payloads accumulate. (Contrast sanitization_runs, which uses
-- RESTRICT because run history must never vanish silently.) No dataset
-- delete path exists yet, so this is inert today and protective tomorrow.
--
-- Size bound: the CHECK mirrors CsvLimits.DEFAULT_MAX_INPUT_BYTES
-- (10 MiB = 10485760 bytes) so the schema rejects what the application
-- must already reject while streaming. One bound, two enforcement points.
--
-- Conventions from V1 apply: UUID keys, plural snake_case tables,
-- TIMESTAMPTZ in UTC, explicitly named constraints (pk_ / fk_ / chk_) and
-- indexes (idx_).
--
-- Indexes: owner-scoped single-input lookups (owner_subject). No other
-- index: the only access path is dataset_id (primary key) plus owner.

CREATE TABLE dataset_inputs (
    dataset_id    UUID        NOT NULL,
    owner_subject TEXT        NOT NULL,
    content       BYTEA       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_dataset_inputs PRIMARY KEY (dataset_id),
    CONSTRAINT fk_dataset_inputs_dataset
        FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE CASCADE,
    CONSTRAINT chk_inputs_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_inputs_content_size CHECK (octet_length(content) <= 10485760)
);

COMMENT ON TABLE dataset_inputs IS 'Stored CSV input bytes, one row per dataset. Read only through DatasetInputSource; metadata queries never touch this table.';
COMMENT ON COLUMN dataset_inputs.dataset_id IS 'Owning dataset. ON DELETE CASCADE: stored bytes are dataset content, not history, and vanish with their dataset.';
COMMENT ON COLUMN dataset_inputs.owner_subject IS 'Owner copied from the dataset at store time; every read is owner-scoped. USER and ADMIN behave identically.';
COMMENT ON COLUMN dataset_inputs.content IS 'Raw uploaded bytes up to 10485760 bytes (10 MiB, mirroring CsvLimits). Never logged, never placed in API responses or exceptions.';

-- Owner-scoped single-input lookups ("my input for dataset X").
CREATE INDEX idx_dataset_inputs_owner_subject ON dataset_inputs (owner_subject);

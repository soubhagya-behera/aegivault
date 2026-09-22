-- V5: sanitization output artifacts — one sanitized payload per run.
--
-- sanitization_artifacts holds the sanitized CSV bytes produced for one
-- run: the output the future download path will serve through the artifact
-- store. SanitizationRun rows stay lightweight operation records; this
-- table exists so result metadata queries never touch payload bytes.
--
-- Backend choice: PostgreSQL BYTEA, consistent with dataset input storage
-- (V4/ADR-013). One transactional store, zero new infrastructure for the
-- MVP. This is NOT a claim of large-scale object-storage scalability.
--
-- Output bound: 41943040 bytes (40 MiB = 4x the 10 MiB input bound).
-- Output size is NOT assumed to stay within the input bound: fixed-size
-- substitutions (33-char synthetic emails, 64-char hashes) expand short
-- detected values several-fold, so reusing the input limit would wrongly
-- reject legitimate hash-heavy output. 4x covers realistic substitution
-- expansion of typical rows with headroom; denser hash-saturated extremes
-- fail closed (rejected, nothing stored) instead of truncating. The bound
-- is enforced in the application while capturing and mirrored here, so one
-- bound has two enforcement points.
--
-- Delete behavior: ON DELETE CASCADE. Artifacts are a run's output
-- content, not history: removing a run removes its payload, so no orphaned
-- blobs accumulate. (Contrast sanitization_runs, which uses RESTRICT
-- against dataset deletion because run history must never vanish
-- silently.) No run delete path exists yet, so this is inert today and
-- protective tomorrow.
--
-- Conventions from V1 apply: UUID keys, plural snake_case tables,
-- TIMESTAMPTZ in UTC, explicitly named constraints (pk_ / fk_ / chk_) and
-- indexes (idx_).
--
-- Indexes: owner-scoped single-artifact lookups (owner_subject). No other
-- index: the only access path is run_id (primary key) plus owner.

CREATE TABLE sanitization_artifacts (
    run_id        UUID        NOT NULL,
    owner_subject TEXT        NOT NULL,
    content       BYTEA       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_sanitization_artifacts PRIMARY KEY (run_id),
    CONSTRAINT fk_sanitization_artifacts_run
        FOREIGN KEY (run_id) REFERENCES sanitization_runs (id) ON DELETE CASCADE,
    CONSTRAINT chk_artifacts_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_artifacts_content_size CHECK (octet_length(content) <= 41943040)
);

COMMENT ON TABLE sanitization_artifacts IS 'Sanitized CSV output bytes, one row per run. Read only through the artifact store; run metadata queries never touch this table.';
COMMENT ON COLUMN sanitization_artifacts.run_id IS 'Producing run. ON DELETE CASCADE: artifacts are run output content, not history, and vanish with their run.';
COMMENT ON COLUMN sanitization_artifacts.owner_subject IS 'Owner copied from the run at store time; every read is owner-scoped. USER and ADMIN behave identically.';
COMMENT ON COLUMN sanitization_artifacts.content IS 'Sanitized output bytes up to 41943040 bytes (40 MiB). Sensitive production-derived data: never logged, never placed in API responses or exceptions.';

-- Owner-scoped single-artifact lookups ("my artifact for run X").
CREATE INDEX idx_sanitization_artifacts_owner_subject ON sanitization_artifacts (owner_subject);

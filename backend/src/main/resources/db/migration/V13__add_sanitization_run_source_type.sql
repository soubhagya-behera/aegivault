-- V13: sanitization_runs.source_type — which source a run reads its rows from.
--
-- A run must say whether it executes against the dataset's stored CSV input or
-- against the PostgreSQL base table the dataset is bound to, because the two
-- read entirely different places and must not be guessed at execution time: a
-- queued run could be read against either path depending on data that changes
-- between queueing and execution. The value is written once by the creation path
-- that knows what was asked for, and read once by the dispatch that picks an
-- execution path.
--
-- Source *kind* only. This column deliberately has no room for a host, port,
-- database, username, password, JDBC URL, schema, or table name, and none may be
-- added to it: PostgreSQL credentials stay in application configuration
-- (aegivault.dataset.postgres.*) and the table is located at execution time
-- through the dataset's own binding. There is no SQL here either.
--
-- Backfill and compatibility: DEFAULT 'CSV' makes every pre-existing run a CSV
-- run without a data rewrite, so the existing CSV execution path, the existing
-- POST /api/runs contract, and every already-queued run behave exactly as before.
-- The column is NOT NULL, so no run can exist without an explicit source kind.
--
-- Immutability: application rows are written once at creation and never updated
-- (updatable = false on the entity), so a run cannot be re-pointed at a different
-- source after it was queued. Changing a run's source is a new run.
--
-- Conventions from V3 apply: TEXT + CHECK instead of an enum type, explicitly
-- named constraints (chk_), and no new index — nothing queries by source type yet,
-- and adding one without a real query behind it would be speculative.

ALTER TABLE sanitization_runs
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'CSV';

ALTER TABLE sanitization_runs
    ADD CONSTRAINT chk_runs_source_type CHECK (source_type IN ('CSV', 'POSTGRESQL'));

COMMENT ON COLUMN sanitization_runs.source_type IS
    'Which source this run reads: CSV from stored input, or POSTGRESQL from the dataset''s bound base table. Kind only, never a location or credential.';
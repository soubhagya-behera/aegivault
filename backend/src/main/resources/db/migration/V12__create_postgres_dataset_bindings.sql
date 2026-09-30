-- V12: PostgreSQL dataset binding — one dataset to one source base table.
--
-- postgres_dataset_bindings records that an Aegivault dataset's data lives in
-- one discovered base table of the configured PostgreSQL source: which dataset
-- (dataset_id), who owns it (owner_subject), and which table
-- (schema_name, table_name), plus created_at / updated_at.
--
-- It is metadata only, and the columns are the reason. There is deliberately
-- no host, port, database, username, password, or JDBC URL column here: the
-- source's connection details are application configuration
-- (aegivault.dataset.postgres.*) and stay out of the database, so this table
-- cannot become a second, less-safe place for a credential to live. There is
-- likewise no value, sample, row-count, or content column — nothing here
-- identifies a row or carries a cell, so no row data is ever persisted.
--
-- One binding per dataset: dataset_id is the primary key, so a second binding
-- for the same dataset is a database-level violation rather than an ambiguity
-- a caller has to reason about. A dataset therefore has at most one source
-- table, and reassignment is an explicit future decision (delete then bind)
-- rather than an implicit overwrite.
--
-- Ownership: owner_subject follows the same convention as datasets, runs,
-- inputs, artifacts, and profiles (opaque TEXT, owner_subject = users.id /
-- JWT sub, USER and ADMIN behave identically). No users FK, the same
-- deliberate choice as V1/V3/V4/V5/V6/V7. Every lookup path is owner-scoped,
-- so one owner's binding is never reachable through another's dataset id.
--
-- Delete behavior: references datasets ON DELETE CASCADE. A binding is derived
-- metadata about a dataset's own source, not operation history, so it must not
-- outlive the dataset it describes — the same reasoning as inputs (V4) and
-- artifacts (V5), unlike runs (V3, RESTRICT).
--
-- Identifier checks: schema_name and table_name are constrained to the same
-- conservative plain-identifier grammar the source layer already enforces
-- (letters, digits, underscore, dollar; leading letter or underscore; at most
-- 63 characters). The constraint is defence in depth — the service validates
-- with PostgresIdentifier before this point — so a value that somehow reached
-- the database directly still cannot carry a dot, quote, semicolon, space, or
-- wildcard.
--
-- Conventions from V1 apply: plural snake_case table name, TEXT + CHECK
-- instead of enums, explicitly named constraints (pk_ / fk_ / chk_) and
-- indexes (idx_), TIMESTAMPTZ in UTC, no arbitrary length limits.

CREATE TABLE postgres_dataset_bindings (
    dataset_id    UUID        NOT NULL,
    owner_subject TEXT        NOT NULL,
    schema_name   TEXT        NOT NULL,
    table_name    TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_postgres_dataset_bindings PRIMARY KEY (dataset_id),
    CONSTRAINT fk_postgres_dataset_bindings_dataset
        FOREIGN KEY (dataset_id) REFERENCES datasets (id) ON DELETE CASCADE,
    CONSTRAINT chk_pg_bindings_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_pg_bindings_schema_not_blank CHECK (char_length(btrim(schema_name)) > 0),
    CONSTRAINT chk_pg_bindings_table_not_blank CHECK (char_length(btrim(table_name)) > 0),
    -- Plain-identifier grammar, matching PostgresIdentifier: no dot, quote,
    -- semicolon, space, or wildcard can ever be stored.
    CONSTRAINT chk_pg_bindings_schema_identifier CHECK (
        schema_name ~ '^[A-Za-z_][A-Za-z0-9_$]{0,62}$'),
    CONSTRAINT chk_pg_bindings_table_identifier CHECK (
        table_name ~ '^[A-Za-z_][A-Za-z0-9_$]{0,62}$')
);

COMMENT ON TABLE postgres_dataset_bindings IS 'One PostgreSQL base table bound to one Aegivault dataset: source metadata only, never credentials and never row data.';
COMMENT ON COLUMN postgres_dataset_bindings.dataset_id IS 'Bound dataset; also the primary key, so at most one source table exists per dataset. ON DELETE CASCADE: a binding must not outlive its dataset.';
COMMENT ON COLUMN postgres_dataset_bindings.owner_subject IS 'Opaque owner identifier copied from the dataset at bind time, so owner-scoped reads need no join. USER and ADMIN behave identically.';
COMMENT ON COLUMN postgres_dataset_bindings.schema_name IS 'Source schema holding the bound table. Always the configured source schema; there is no cross-schema browsing.';
COMMENT ON COLUMN postgres_dataset_bindings.table_name IS 'Discovered base table name. A base table only: views, materialized views, functions, and procedures are not bindable.';

-- Owner-scoped binding lookups ("my binding for dataset X").
CREATE INDEX idx_pg_bindings_owner_subject ON postgres_dataset_bindings (owner_subject);

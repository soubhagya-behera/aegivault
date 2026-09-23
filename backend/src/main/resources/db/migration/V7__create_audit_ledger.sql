-- V7: tamper-evident audit ledger foundation — hash-chained operation records.
--
-- audit_ledger_entries holds one row per audited event: who acted
-- (actor_subject), what happened (event_type), what it concerned
-- (resource_type plus an optional resource_id), safe metadata only
-- (event_data), and the hash-chain links (previous_hash, entry_hash).
-- This is a hash-chained database ledger, not a blockchain: it provides
-- tamper-evidence (detection of modification on verification), not absolute
-- tamper-proofing, and there is no distributed consensus of any kind.
--
-- Integrity by construction:
--   * sequence_number is UNIQUE and starts at 1 with no gaps: verification
--     replays the chain in sequence order and rejects any break.
--   * entry_hash is UNIQUE: one canonical content maps to one stored hash.
--   * entry_hash is derived (application-side, SHA-256 over the canonical
--     fields plus previous_hash), never client-supplied; the first entry
--     uses the deterministic previous_hash 'GENESIS'.
--   * There is deliberately no update or delete path for ledger rows — from
--     the application's perspective the ledger is append-only.
--
-- Safety: event_data carries safe metadata only — never raw CSV, never
-- sanitized CSV, never raw PII, never passwords, JWTs, API keys, secrets,
-- and never blind request bodies. The schema cannot enforce "metadata
-- only" (a TEXT column cannot see meaning), so this boundary is enforced
-- by the application: only explicit, reviewed call sites may append, and
-- each passes a fixed metadata document. Octet-length CHECKs bound every
-- TEXT column so one bound has two enforcement points, as elsewhere.
--
-- Concurrency: single-instance architecture. Two concurrent appends read
-- the same tail and one of them loses loudly on the UNIQUE constraint —
-- no silent overwrite, no torn chain — but there is no distributed locking
-- (no Redis, no workers); multi-instance deployment would need an explicit
-- sequencing decision first.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid(), plural
-- snake_case tables, TEXT + CHECK instead of enums, explicitly named
-- constraints (pk_ / fk_ / chk_ / uq_) and indexes (idx_), TIMESTAMPTZ
-- in UTC. No users FK (same deliberate choice as V1/V3/V4/V5/V6).
--
-- Indexes: verification replay (sequence_number ordering; the UNIQUE
-- constraint already indexes it), resource lookups (resource_type,
-- resource_id), and actor lookups (actor_subject).

CREATE TABLE audit_ledger_entries (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    sequence_number BIGINT      NOT NULL,
    event_type      TEXT        NOT NULL,
    actor_subject   TEXT        NOT NULL,
    resource_type   TEXT        NOT NULL,
    resource_id     UUID        NULL,
    event_data      TEXT        NOT NULL,
    previous_hash   TEXT        NOT NULL,
    entry_hash      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_ledger_entries PRIMARY KEY (id),
    CONSTRAINT uq_audit_ledger_entries_sequence_number UNIQUE (sequence_number),
    CONSTRAINT uq_audit_ledger_entries_entry_hash UNIQUE (entry_hash),
    CONSTRAINT chk_audit_entries_sequence_positive CHECK (sequence_number >= 1),
    CONSTRAINT chk_audit_entries_event_type_not_blank CHECK (char_length(btrim(event_type)) > 0),
    CONSTRAINT chk_audit_entries_event_type_length CHECK (char_length(event_type) <= 128),
    CONSTRAINT chk_audit_entries_actor_not_blank CHECK (char_length(btrim(actor_subject)) > 0),
    CONSTRAINT chk_audit_entries_actor_length CHECK (char_length(actor_subject) <= 255),
    CONSTRAINT chk_audit_entries_resource_type_not_blank CHECK (char_length(btrim(resource_type)) > 0),
    CONSTRAINT chk_audit_entries_resource_type_length CHECK (char_length(resource_type) <= 128),
    CONSTRAINT chk_audit_entries_event_data_size CHECK (octet_length(event_data) <= 8192),
    CONSTRAINT chk_audit_entries_previous_hash_not_blank CHECK (char_length(btrim(previous_hash)) > 0),
    CONSTRAINT chk_audit_entries_previous_hash_length CHECK (char_length(previous_hash) <= 64),
    CONSTRAINT chk_audit_entries_entry_hash_format CHECK (char_length(entry_hash) = 64)
);

COMMENT ON TABLE audit_ledger_entries IS 'Tamper-evident, hash-chained audit ledger: append-only operation records with safe metadata only. Verification replays sequence order and recomputes every hash; it is tamper-evidence, not a blockchain.';
COMMENT ON COLUMN audit_ledger_entries.sequence_number IS '1-based, gapless position in the chain. UNIQUE: concurrent appends fail loudly instead of forking the chain.';
COMMENT ON COLUMN audit_ledger_entries.event_type IS 'What happened, application vocabulary (<= 128 characters). No CHECK on values: new event types must not require a migration.';
COMMENT ON COLUMN audit_ledger_entries.actor_subject IS 'Who acted, opaque TEXT in the owner_subject convention (JWT sub). Indexed for actor lookups.';
COMMENT ON COLUMN audit_ledger_entries.resource_type IS 'What the event concerned (<= 128 characters), e.g. DATASET, POLICY, RUN.';
COMMENT ON COLUMN audit_ledger_entries.resource_id IS 'Which resource, when the resource has a UUID identity; NULL when the event concerns no single row.';
COMMENT ON COLUMN audit_ledger_entries.event_data IS 'Safe metadata only (<= 8192 bytes): counts, names, enum values. Never raw CSV, PII, secrets, or request bodies — enforced by the application, which this column cannot see.';
COMMENT ON COLUMN audit_ledger_entries.previous_hash IS 'entry_hash of the previous sequence, or GENESIS for sequence 1. Part of the hashed canonical form.';
COMMENT ON COLUMN audit_ledger_entries.entry_hash IS 'SHA-256 (lowercase hex) over the canonical fields plus previous_hash. UNIQUE, derived server-side, never client-supplied.';

-- Resource lookups ("events for resource X").
CREATE INDEX idx_audit_ledger_entries_resource ON audit_ledger_entries (resource_type, resource_id);

-- Actor lookups ("events by actor Y").
CREATE INDEX idx_audit_ledger_entries_actor ON audit_ledger_entries (actor_subject);

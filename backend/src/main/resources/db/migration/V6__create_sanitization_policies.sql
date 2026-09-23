-- V6: persistent, owner-scoped sanitization policies — one reusable policy
-- per row plus a normalized child table for its transformation rules.
--
-- SanitizationPolicy (aggregate root) + PolicyRule (child):
--   * sanitization_policies holds identity (owner_subject, name, version) and
--     an optional description. It stores no raw PII values and no CSV data —
--     a policy is metadata about transformations, not about any dataset.
--   * sanitization_policy_rules holds one row per (policy, PII type). The
--     natural primary key IS (policy_id, pii_type), so the aggregate
--     invariant "at most one transformation strategy per PII type per
--     policy" is enforced by the database itself: a duplicate insert cannot
--     be made to succeed, no matter which code path attempts it. The enum
--     CHECKs keep the stored vocabulary identical to the supported
--     PiiType / TransformationStrategy values (TEXT + CHECK, as in V1).
--   * The at-least-one-rule rule cannot be expressed as a row CHECK (a
--     parent table cannot see its children), so it is enforced by the
--     application aggregate instead; this comment records that boundary.
--
-- Ownership: owner_subject is part of the boundary, exactly like datasets
-- and runs — an opaque TEXT column in the convention owner_subject =
-- users.id (JWT sub), indexed for owner-scoped listing. No users FK (same
-- deliberate choice as V1/V3/V4/V5). USER and ADMIN behave identically.
--
-- Bounded lengths: name/version <= 255 and description <= 1024 mirror the
-- request-validation bounds (CreateDatasetRequest / CreateRunRequest), so
-- one bound has two enforcement points — application and schema — the same
-- pattern already used for the 10 MiB and 40 MiB byte bounds.
--
-- Delete behavior: the rules FK is ON DELETE CASCADE. Rules are the
-- policy's own content, not history (contrast sanitization_runs, which uses
-- RESTRICT because run history must never vanish silently). No policy
-- delete path exists yet, so this is inert today and protective tomorrow.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid(), plural
-- snake_case tables, TEXT + CHECK instead of enums, explicitly named
-- constraints (pk_/fk_/chk_/uq_) and indexes (idx_), TIMESTAMPTZ in UTC.
--
-- Indexes: owner-scoped listing ("my policies"). The child table needs no
-- extra index — its composite primary key already leads with policy_id.
-- No uniqueness on (owner_subject, name, version): several policies may
-- share labels today because no update/conflict contract exists yet.

CREATE TABLE sanitization_policies (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    owner_subject  TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    version        TEXT        NOT NULL,
    description    TEXT        NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_sanitization_policies PRIMARY KEY (id),
    CONSTRAINT chk_policies_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_policies_name_not_blank CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT chk_policies_name_length CHECK (char_length(name) <= 255),
    CONSTRAINT chk_policies_version_not_blank CHECK (char_length(btrim(version)) > 0),
    CONSTRAINT chk_policies_version_length CHECK (char_length(version) <= 255),
    CONSTRAINT chk_policies_description_length CHECK (
        description IS NULL OR char_length(description) <= 1024)
);

COMMENT ON TABLE sanitization_policies IS 'Reusable, owner-scoped transformation policies: metadata only, never raw PII values and never CSV data.';
COMMENT ON COLUMN sanitization_policies.owner_subject IS 'Owner copied from the JWT subject at creation; every read is owner-scoped. USER and ADMIN behave identically.';
COMMENT ON COLUMN sanitization_policies.name IS 'Human label, <= 255 characters (mirrors request validation). Not unique: no conflict contract exists yet.';
COMMENT ON COLUMN sanitization_policies.version IS 'Caller-assigned version label, <= 255 characters (mirrors request validation).';
COMMENT ON COLUMN sanitization_policies.description IS 'Optional free text, <= 1024 characters (mirrors request validation).';

-- Owner-scoped listing ("my policies"), newest first at the service layer.
CREATE INDEX idx_sanitization_policies_owner_subject ON sanitization_policies (owner_subject);

CREATE TABLE sanitization_policy_rules (
    policy_id               UUID    NOT NULL,
    pii_type                TEXT    NOT NULL,
    transformation_strategy TEXT    NOT NULL,
    CONSTRAINT pk_sanitization_policy_rules PRIMARY KEY (policy_id, pii_type),
    CONSTRAINT fk_sanitization_policy_rules_policy
        FOREIGN KEY (policy_id) REFERENCES sanitization_policies (id) ON DELETE CASCADE,
    CONSTRAINT chk_policy_rules_pii_type CHECK (pii_type IN (
        'EMAIL', 'PHONE', 'PERSON_NAME', 'ADDRESS', 'CREDIT_CARD', 'IP_ADDRESS',
        'UUID', 'API_KEY', 'PASSWORD', 'JWT', 'CUSTOM_IDENTIFIER')),
    CONSTRAINT chk_policy_rules_strategy CHECK (transformation_strategy IN (
        'KEEP', 'REDACT', 'MASK', 'SYNTHETIC_EMAIL', 'SYNTHETIC_PHONE', 'HASH_SHA256')),
    CONSTRAINT chk_policy_rules_pii_type_not_blank CHECK (char_length(btrim(pii_type)) > 0)
);

COMMENT ON TABLE sanitization_policy_rules IS 'One row per (policy, PII type): the natural primary key enforces at most one strategy per PII type per policy.';
COMMENT ON COLUMN sanitization_policy_rules.policy_id IS 'Owning policy. ON DELETE CASCADE: rules are the policy content, not history, and vanish with their policy.';
COMMENT ON COLUMN sanitization_policy_rules.pii_type IS 'PII type name from the closed PiiType set; part of the primary key, so it never repeats within one policy.';
COMMENT ON COLUMN sanitization_policy_rules.transformation_strategy IS 'Strategy name from the closed TransformationStrategy set. Carries no data values.';

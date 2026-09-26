-- V10: persistent, owner-scoped gateway usage policies — usage limit
-- definitions, stored but NOT yet enforced anywhere.
--
-- gateway_usage_policies holds one reusable limit definition per row: who
-- owns it (owner_subject, opaque TEXT in the owner_subject convention — the
-- JWT sub, exactly like gateway_usage_records.actor_subject), how it is
-- labelled (name, optional description), the limit concepts themselves
-- (requests_per_minute, requests_per_day, tokens_per_day — each NULL when
-- the policy does not constrain it), whether it is enabled, and its
-- timestamps.
--
-- ENFORCEMENT STATUS: these rows are inert. No gateway code path reads this
-- table: GatewayCompletionService, the rate limiter, provider selection,
-- usage recording, and the audit ledger are all untouched. Nothing here
-- counts, blocks, or meters traffic, and no counter is derived from it. It
-- exists so a limit can be declared, reviewed, and revised before anything
-- enforces it.
--
-- There is deliberately no pricing, no currency, and no cost column: a
-- policy expresses request and token *quantities* only. Monetary limits
-- would require a price list that does not exist.
--
-- Limits: every supplied limit must be strictly positive (a zero or
-- negative limit is not a limit but a contradiction, and NULL already
-- means "unconstrained"). The at-least-one-limit rule cannot be expressed
-- as a row CHECK, so the aggregate enforces it; this comment records that
-- boundary, mirroring the same pattern in V6 for at-least-one-rule.
--
-- Ownership: owner_subject is part of the boundary, exactly like datasets,
-- runs, and sanitization policies — indexed for owner-scoped listing. No
-- users FK (same deliberate choice as V1/V3/V4/V5/V6/V7/V8/V9): owner
-- subject stays opaque TEXT. USER and ADMIN behave identically; there is no
-- ADMIN bypass and no global (cross-owner) read path.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid(), plural
-- snake_case tables, TEXT + CHECK instead of enums, explicitly named
-- constraints (pk_/chk_) and indexes (idx_), TIMESTAMPTZ in UTC.
--
-- Indexes: owner-scoped listing ("my policies", newest first at the service
-- layer). No uniqueness on (owner_subject, name): several policies may
-- share a label because no conflict contract exists yet.

CREATE TABLE gateway_usage_policies (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    owner_subject       TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    description         TEXT        NULL,
    requests_per_minute BIGINT      NULL,
    requests_per_day    BIGINT      NULL,
    tokens_per_day      BIGINT      NULL,
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_gateway_usage_policies PRIMARY KEY (id),
    CONSTRAINT chk_usage_policy_owner_not_blank CHECK (char_length(btrim(owner_subject)) > 0),
    CONSTRAINT chk_usage_policy_owner_length CHECK (char_length(owner_subject) <= 255),
    CONSTRAINT chk_usage_policy_name_not_blank CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT chk_usage_policy_name_length CHECK (char_length(name) <= 255),
    CONSTRAINT chk_usage_policy_description_length CHECK (
        description IS NULL OR char_length(description) <= 1024),
    CONSTRAINT chk_usage_policy_requests_per_minute_positive CHECK (
        requests_per_minute IS NULL OR requests_per_minute > 0),
    CONSTRAINT chk_usage_policy_requests_per_day_positive CHECK (
        requests_per_day IS NULL OR requests_per_day > 0),
    CONSTRAINT chk_usage_policy_tokens_per_day_positive CHECK (
        tokens_per_day IS NULL OR tokens_per_day > 0)
);

COMMENT ON TABLE gateway_usage_policies IS 'Owner-scoped gateway usage limit definitions. NOT ENFORCED: no gateway code path reads this table and no counter is derived from it. Request and token quantity limits only; no pricing, currency, or cost.';

COMMENT ON COLUMN gateway_usage_policies.owner_subject IS 'Owner copied from the JWT subject at creation; every read is owner-scoped. USER and ADMIN behave identically, with no ADMIN bypass.';

COMMENT ON COLUMN gateway_usage_policies.name IS 'Human label, <= 255 characters (mirrors request validation). Not unique: no conflict contract exists yet.';

COMMENT ON COLUMN gateway_usage_policies.description IS 'Optional free text, <= 1024 characters (mirrors request validation).';

COMMENT ON COLUMN gateway_usage_policies.requests_per_minute IS 'Declared request count per minute, strictly positive when set. NULL means the policy does not constrain this limit. Not enforced.';

COMMENT ON COLUMN gateway_usage_policies.requests_per_day IS 'Declared request count per day, strictly positive when set. NULL means the policy does not constrain this limit. Not enforced.';

COMMENT ON COLUMN gateway_usage_policies.tokens_per_day IS 'Declared token count per day, strictly positive when set. NULL means the policy does not constrain this limit. Not enforced, and never derived from persisted usage yet.';

COMMENT ON COLUMN gateway_usage_policies.enabled IS 'Whether the policy is switched on as a definition. Defaults to TRUE. Not consulted by any gateway code path yet.';

-- Owner-scoped listing ("my policies"), newest first at the service layer.
CREATE INDEX idx_gateway_usage_policies_owner_subject ON gateway_usage_policies (owner_subject);
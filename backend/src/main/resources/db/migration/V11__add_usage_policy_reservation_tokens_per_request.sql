-- V11: add a policy-controlled per-request token reservation amount to
-- gateway usage policies. Configuration only — still NOT enforced.
--
-- A daily token limit alone cannot be enforced honestly, because actual
-- provider token usage is known only AFTER the provider responds while
-- admission happens BEFORE it. A future tokensPerDay enforcement step
-- therefore has to hold some amount of capacity per request up front. This
-- migration gives the policy a trusted source for that number, so the amount
-- is configuration chosen by the policy owner rather than a value the client
-- picks per request — otherwise a caller could always reserve 1 token and
-- spend a day without ever being refused.
--
-- reservation_tokens_per_request is the maximum number of tokens reserved for
-- ONE request before provider invocation. It is deliberately NOT actual usage,
-- NOT an estimate or tokenizer result, NOT a response-size guess, and NOT
-- client-supplied. Actual usage is reconciled against this reservation after
-- the provider responds; nothing here estimates or predicts it.
--
-- ENFORCEMENT STATUS: unchanged and still inert. No gateway code path reads
-- this column. GatewayCompletionService, the rate limiter, provider selection,
-- usage recording, the token budget, and the audit ledger are all untouched,
-- and tokensPerDay remains unenforced.
--
-- No pricing, no currency, no cost, and no billing column: a policy still
-- expresses token QUANTITIES only. This migration adds no table.
--
-- Cross-field rules, mirroring GatewayUsagePolicy's aggregate validation:
--   * a present reservation is always strictly positive (a zero or negative
--     reservation is a contradiction, not a reservation);
--   * a present reservation never exceeds tokens_per_day (holding more for one
--     request than the whole day allows is unsatisfiable);
--   * a declared tokens_per_day REQUIRES a reservation amount, so a daily token
--     policy can never exist without a deterministic pre-request amount.
-- NULL tokens_per_day still means the policy does not constrain tokens at all,
-- and a NULL reservation alongside it is correct, not incomplete.
--
-- EXISTING ROWS: the column is added nullable with no DEFAULT, so every current
-- row is preserved and none is backfilled — inventing a reservation amount for
-- an existing policy would be a fabricated number the owner never chose. A
-- policy with tokens_per_day IS NULL satisfies every new constraint as-is.
--
-- The one constraint that could conflict with pre-existing data,
-- chk_usage_policy_reservation_requires_tokens_per_day, is therefore added
-- NOT VALID: any row that already declared tokens_per_day before this migration
-- has no reservation amount and there is no honest value to give it. NOT VALID
-- still enforces the rule on every insert and update from now on, while
-- grandfathering history rather than failing the migration or inventing data.
-- This mirrors the "cannot be expressed as a row CHECK, so the aggregate
-- enforces it" boundary pattern recorded in V6 and V10.
--
-- Conventions from V1 apply (and from V10 for this table): explicitly named
-- chk_ constraints, TIMESTAMPTZ in UTC, UUID keys, plural snake_case naming.

ALTER TABLE gateway_usage_policies
    ADD COLUMN reservation_tokens_per_request BIGINT NULL;

-- A present reservation is always strictly positive.
ALTER TABLE gateway_usage_policies
    ADD CONSTRAINT chk_usage_policy_reservation_tokens_per_request_positive CHECK (
        reservation_tokens_per_request IS NULL
        OR reservation_tokens_per_request > 0);

-- A present reservation never exceeds the day's token limit. NULL tokens_per_day
-- yields NULL here, which a CHECK accepts, so a request-only policy is unaffected.
ALTER TABLE gateway_usage_policies
    ADD CONSTRAINT chk_usage_policy_reservation_within_tokens_per_day CHECK (
        reservation_tokens_per_request IS NULL
        OR reservation_tokens_per_request <= tokens_per_day);

-- A declared daily token limit requires a pre-request reservation amount.
-- NOT VALID: enforced on all new writes, grandfathering any pre-existing
-- tokens_per_day row that has no reservation rather than inventing one.
ALTER TABLE gateway_usage_policies
    ADD CONSTRAINT chk_usage_policy_reservation_requires_tokens_per_day CHECK (
        tokens_per_day IS NULL
        OR reservation_tokens_per_request IS NOT NULL) NOT VALID;

COMMENT ON COLUMN gateway_usage_policies.reservation_tokens_per_request IS 'Maximum tokens reserved for ONE request before provider invocation when tokensPerDay is set. Strictly positive when present, never greater than tokens_per_day, and required whenever tokens_per_day is present. Configuration, NOT client input: it is not actual usage, not an estimate or tokenizer result, and not a response-size guess. Actual provider usage is reconciled against this reservation after the response. Still NOT enforced: no gateway code path reads this column.';
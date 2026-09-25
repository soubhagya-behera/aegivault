-- V9: gateway usage persistence — one metadata row per provider invocation
-- that returned a provider response.
--
-- gateway_usage_records holds who asked (actor_subject, opaque TEXT in the
-- owner_subject convention — the JWT sub), which request (request_id, the
-- server-generated gateway request id), which model, the exact
-- provider-reported token counts (prompt_tokens, completion_tokens,
-- total_tokens — each NULL when the provider supplied no count, never
-- estimated, never a character count), and what happened to the response
-- (outcome: DELIVERED for a clean response that reached the client,
-- SECURITY_BLOCKED for a response stopped by response inspection — the
-- blocked call may still have consumed provider tokens, so it is recorded
-- too). No request BLOCK, rate-limit rejection, or provider failure ever
-- writes here: no provider response — and therefore no provider usage —
-- exists in those cases.
--
-- Safety: metadata only — never request content, provider content, PII,
-- secrets, JWTs, prompts, or raw payloads. The schema enforces this
-- structurally as far as it can: there is simply no content column. The
-- rest is a caller contract — only GatewayUsageRecorder may insert, and it
-- passes gateway metadata plus provider-reported counts only.
--
-- Uniqueness: one gateway completion request currently makes at most one
-- provider invocation, so request_id is UNIQUE — a repeated request id
-- fails loudly instead of double-counting.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid(), plural
-- snake_case tables, TEXT + CHECK instead of enums, explicitly named
-- constraints (pk_ / fk_ / chk_ / uq_) and indexes (idx_), TIMESTAMPTZ
-- in UTC. No users FK (same deliberate choice as V1/V3/V4/V5/V6/V7/V8):
-- actor_subject stays opaque TEXT.
--
-- Indexes: actor usage history (actor_subject, created_at DESC). The
-- request_id UNIQUE constraint already indexes request lookups.

CREATE TABLE gateway_usage_records (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    request_id        UUID        NOT NULL,
    actor_subject     TEXT        NOT NULL,
    model             TEXT        NOT NULL,
    prompt_tokens     BIGINT      NULL,
    completion_tokens BIGINT      NULL,
    total_tokens      BIGINT      NULL,
    outcome           TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_gateway_usage_records PRIMARY KEY (id),
    CONSTRAINT uq_gateway_usage_records_request_id UNIQUE (request_id),
    CONSTRAINT chk_usage_actor_not_blank CHECK (char_length(btrim(actor_subject)) > 0),
    CONSTRAINT chk_usage_actor_length CHECK (char_length(actor_subject) <= 255),
    CONSTRAINT chk_usage_model_not_blank CHECK (char_length(btrim(model)) > 0),
    CONSTRAINT chk_usage_model_length CHECK (char_length(model) <= 255),
    CONSTRAINT chk_usage_outcome CHECK (outcome IN ('DELIVERED', 'SECURITY_BLOCKED')),
    CONSTRAINT chk_usage_prompt_non_negative CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CONSTRAINT chk_usage_completion_non_negative CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CONSTRAINT chk_usage_total_non_negative CHECK (total_tokens IS NULL OR total_tokens >= 0)
);

COMMENT ON TABLE gateway_usage_records IS 'Gateway provider usage metadata: one row per provider invocation that returned a response — request id, actor, model, exact provider-reported token counts (NULL when unknown), and delivery outcome. Metadata only; no prompt, response, PII, or secret content exists here.';
COMMENT ON COLUMN gateway_usage_records.request_id IS 'Server-generated gateway request id. UNIQUE: one request makes at most one provider invocation, so a repeat fails loudly instead of double-counting.';
COMMENT ON COLUMN gateway_usage_records.actor_subject IS 'Who asked, opaque TEXT in the owner_subject convention (JWT sub). Indexed for actor usage history.';
COMMENT ON COLUMN gateway_usage_records.prompt_tokens IS 'Provider-reported prompt tokens, NULL when the provider supplied none. Never estimated, never a character count.';
COMMENT ON COLUMN gateway_usage_records.completion_tokens IS 'Provider-reported completion tokens, NULL when the provider supplied none.';
COMMENT ON COLUMN gateway_usage_records.total_tokens IS 'Provider-reported total tokens, NULL when the provider supplied none.';
COMMENT ON COLUMN gateway_usage_records.outcome IS 'What happened to the provider response: DELIVERED (reached the client) or SECURITY_BLOCKED (stopped by response inspection).';

-- Actor usage history ("usage rows for actor Y, newest first").
CREATE INDEX idx_gateway_usage_records_actor_created
    ON gateway_usage_records (actor_subject, created_at DESC);

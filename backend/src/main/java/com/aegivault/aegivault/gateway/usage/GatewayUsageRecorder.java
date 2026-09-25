package com.aegivault.aegivault.gateway.usage;

import com.aegivault.aegivault.gateway.GatewayInspectionRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application-layer seam between gateway completions and usage
 * persistence: records one {@link GatewayUsageRecord} per provider
 * invocation that returned a provider response.
 *
 * <p>The row carries gateway metadata plus the exact provider-reported
 * token counts — the server-generated request id, the JWT-derived actor,
 * the model, the {@link LlmResponse} usage values verbatim (null stays
 * null, never estimated), and whether the response was delivered or
 * blocked by response inspection. Request content, provider content, PII,
 * secrets, JWTs, prompts, and raw payloads never enter this call because
 * there is nowhere to put them: the record has no such columns.
 *
 * <p>A persistence failure surfaces as {@link GatewayUsageException} with
 * a generic message (cause retained for server logs): it is never
 * swallowed and never reported as success.
 */
@Service
@RequiredArgsConstructor
public class GatewayUsageRecorder {

    private final GatewayUsageRepository repository;

    /**
     * Persists one usage row for a provider response that already exists.
     * Exactly one row per call; callers invoke this once per provider
     * invocation, after the response-inspection verdict is known.
     *
     * @param inspection gateway request metadata with the server-generated
     *        id and JWT-derived actor, never null
     * @param response provider response whose usage is recorded, never null
     * @param outcome what happened to the provider response, never null
     */
    public void record(
            GatewayInspectionRequest inspection, LlmResponse response, GatewayUsageOutcome outcome) {
        GatewayUsageRecord record = new GatewayUsageRecord(
                inspection.requestId(),
                inspection.actorSubject(),
                inspection.model(),
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.usage().totalTokens(),
                outcome);
        try {
            repository.save(record);
        } catch (RuntimeException ex) {
            throw new GatewayUsageException("Unable to record gateway usage.", ex);
        }
    }
}

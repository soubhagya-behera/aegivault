package com.aegivault.aegivault.gateway.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of one gateway usage record: usage metadata only. Exposes
 * the request id, model, exact provider-reported token counts (null
 * when unknown — never zero-filled), the outcome, and the creation
 * timestamp. Never carries the actor subject, prompt or response
 * content, secrets, PII, internal database identifiers unrelated to
 * usage, or Redis information.
 */
public record GatewayUsageHistoryItemResponse(
        UUID requestId,
        String model,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        GatewayUsageOutcome outcome,
        Instant createdAt) {

    static GatewayUsageHistoryItemResponse from(GatewayUsageRecord record) {
        return new GatewayUsageHistoryItemResponse(
                record.getRequestId(),
                record.getModel(),
                record.getPromptTokens(),
                record.getCompletionTokens(),
                record.getTotalTokens(),
                record.getOutcome(),
                record.getCreatedAt());
    }
}

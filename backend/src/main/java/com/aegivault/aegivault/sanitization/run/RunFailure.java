package com.aegivault.aegivault.sanitization.run;

/**
 * Safe structured failure recorded when a run fails. Metadata only:
 *
 * <ul>
 *   <li>{@code errorCode} — stable machine label, e.g.
 *       {@code CSV_PARSE_ERROR}, {@code POLICY_GAP}, {@code IO_ERROR}.</li>
 *   <li>{@code errorStage} — pipeline stage that failed, e.g.
 *       {@code TOKENIZE}, {@code DETECT}, {@code TRANSFORM}, {@code WRITE}.</li>
 *   <li>{@code errorMessage} — short human sentence naming structure
 *       (rows, columns, limits, types, strategies) only.</li>
 * </ul>
 *
 * <p>Callers must pass metadata only. Never pass raw CSV values, PII,
 * exception stack traces, secrets, JWTs, API keys, or passwords: length caps
 * bound the damage a careless caller can do, but the discipline of passing
 * already-sanitized text belongs to the caller. The service accepts these
 * three fields and never a {@link Throwable}, so a stack trace can never
 * flow into the run row by accident.
 *
 * @param errorCode short machine label, never blank, at most 64 characters
 * @param errorStage pipeline stage label, never blank, at most 64 characters
 * @param errorMessage metadata-only sentence, never blank, at most 1000 characters
 */
public record RunFailure(String errorCode, String errorStage, String errorMessage) {

    private static final int CODE_MAX = 64;

    private static final int STAGE_MAX = 64;

    private static final int MESSAGE_MAX = 1000;

    public RunFailure {
        errorCode = requireText(errorCode, "errorCode", CODE_MAX);
        errorStage = requireText(errorStage, "errorStage", STAGE_MAX);
        errorMessage = requireText(errorMessage, "errorMessage", MESSAGE_MAX);
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }
}

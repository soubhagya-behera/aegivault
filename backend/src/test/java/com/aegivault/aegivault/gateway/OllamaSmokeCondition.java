package com.aegivault.aegivault.gateway;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Opt-in gate for the live Ollama smoke test. The test runs only when
 * {@code AEGIVAULT_OLLAMA_TEST=true} (environment) or
 * {@code -Daegivault.ollama.test=true} (Maven/system property) is set.
 * Everything else — unset, blank, or any non-{@code true} value — disables
 * the test before any Spring context starts, so no Ollama connection is
 * ever attempted by default.
 */
class OllamaSmokeCondition implements ExecutionCondition {

    static final String ENV_FLAG = "AEGIVAULT_OLLAMA_TEST";

    static final String SYSTEM_PROPERTY_FLAG = "aegivault.ollama.test";

    static boolean enabled() {
        return isTrue(System.getenv(ENV_FLAG)) || isTrue(System.getProperty(SYSTEM_PROPERTY_FLAG));
    }

    private static boolean isTrue(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (enabled()) {
            return ConditionEvaluationResult.enabled(
                    "Live Ollama smoke test explicitly enabled via " + ENV_FLAG + " or -D" + SYSTEM_PROPERTY_FLAG);
        }
        return ConditionEvaluationResult.disabled(
                "Live Ollama smoke test is opt-in only: set " + ENV_FLAG + "=true or -D" + SYSTEM_PROPERTY_FLAG
                        + "=true to run it. No Ollama connection attempted.");
    }
}

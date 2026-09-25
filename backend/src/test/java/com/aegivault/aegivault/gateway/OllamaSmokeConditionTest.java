package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Fast unit checks for the opt-in smoke-test gate (no Spring, no Ollama). */
class OllamaSmokeConditionTest {

    @Test
    void flagDefaultsToDisabled() {
        // System properties set by other suites must not leak in, but an
        // absent flag is the contract: disabled unless explicitly enabled.
        String previous = System.getProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG);
        System.clearProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG);
        try {
            if (System.getenv(OllamaSmokeCondition.ENV_FLAG) == null) {
                assertThat(OllamaSmokeCondition.enabled()).isFalse();
            }
        } finally {
            if (previous != null) {
                System.setProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG, previous);
            }
        }
    }

    @Test
    void systemPropertyEnablesAndDisables() {
        String previous = System.getProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG);
        try {
            System.setProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG, "true");
            assertThat(OllamaSmokeCondition.enabled()).isTrue();
            System.setProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG, "false");
            if (System.getenv(OllamaSmokeCondition.ENV_FLAG) == null) {
                assertThat(OllamaSmokeCondition.enabled()).isFalse();
            }
        } finally {
            if (previous != null) {
                System.setProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG, previous);
            } else {
                System.clearProperty(OllamaSmokeCondition.SYSTEM_PROPERTY_FLAG);
            }
        }
    }

    @Test
    void smokeModelUsesDocumentedDefaultUnlessOverridden() {
        String previous = System.getProperty("aegivault.ollama.model");
        try {
            if (System.getenv("AEGIVAULT_OLLAMA_MODEL") == null && previous == null) {
                assertThat(OllamaGatewaySmokeTest.smokeModel()).isEqualTo("llama3.2");
            }
            System.setProperty("aegivault.ollama.model", "custom-model-1");
            if (System.getenv("AEGIVAULT_OLLAMA_MODEL") == null) {
                assertThat(OllamaGatewaySmokeTest.smokeModel()).isEqualTo("custom-model-1");
            }
        } finally {
            if (previous != null) {
                System.setProperty("aegivault.ollama.model", previous);
            } else {
                System.clearProperty("aegivault.ollama.model");
            }
        }
    }
}

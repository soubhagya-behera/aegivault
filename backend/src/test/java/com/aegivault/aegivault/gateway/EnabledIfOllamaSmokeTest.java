package com.aegivault.aegivault.gateway;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Enables a test only when the opt-in live Ollama smoke flag is set, either
 * as the {@code AEGIVAULT_OLLAMA_TEST} environment variable or as the
 * {@code aegivault.ollama.test} system property (Maven
 * {@code -Daegivault.ollama.test=true}). Any other value — including an
 * unset flag — disables the test without attempting any Ollama connection,
 * so the normal suite never requires Ollama.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(OllamaSmokeCondition.class)
public @interface EnabledIfOllamaSmokeTest {}

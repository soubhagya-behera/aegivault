package com.aegivault.aegivault.gateway;

/**
 * Contract for obvious-secret detection over AI request text.
 *
 * <p>Answers whether a secret is present — never which one. Implementations
 * must never return, log, or store a matched secret, and must perform no
 * network I/O: recognition is structural only and is not proof that a
 * secret is real or active. Null or blank input contains no secret.
 */
public interface SecretDetector {

    /**
     * @param text request text to inspect, may be null
     * @return true when an obvious secret category is present, false
     *         otherwise (including null or blank input)
     */
    boolean containsSecret(String text);
}

package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.pii.ApiKeyDetector;
import com.aegivault.aegivault.pii.JwtDetector;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Deterministic {@link SecretDetector} for obvious secret categories
 * already relevant to the project: provider-style API keys and JWT-shaped
 * bearer tokens.
 *
 * <p>Introduces no new patterns: each token is classified by the existing
 * {@link ApiKeyDetector} and {@link JwtDetector} beans, so there is exactly
 * one regex vocabulary for these shapes. Only presence is reported — the
 * matched secret is never returned, logged, or stored — and no external
 * secret-scanning service is consulted. This is obvious-secret recognition,
 * not a comprehensive secret-detection claim.
 */
@Component
public class DefaultSecretDetector implements SecretDetector {

    private final ApiKeyDetector apiKeys;

    private final JwtDetector jwt;

    public DefaultSecretDetector(ApiKeyDetector apiKeys, JwtDetector jwt) {
        this.apiKeys = Objects.requireNonNull(apiKeys, "apiKeys must not be null");
        this.jwt = Objects.requireNonNull(jwt, "jwt must not be null");
    }

    @Override
    public boolean containsSecret(String text) {
        for (String token : GatewayTokens.split(text)) {
            if (apiKeys.detect(token).isPresent() || jwt.detect(token).isPresent()) {
                return true;
            }
        }
        return false;
    }
}

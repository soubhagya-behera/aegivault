package com.aegivault.aegivault.gateway.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Deterministic local {@link LlmProvider} for tests and local use. This
 * is a mock, not a production LLM: it makes no network calls, requires
 * no API key and no configuration, and its "completion" is an explicitly
 * labelled mock string derived from the model plus a SHA-256 digest of
 * the input — never an AI-generated answer.
 *
 * <p>Same input always yields the same response; different inputs yield
 * different digests. The response carries the model, the input length,
 * and the digest only — never the request content itself. Stateless:
 * no fields, no cache, nothing to configure.
 */
@Component
public class MockLlmProvider implements LlmProvider {

    public MockLlmProvider() {
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String digest = sha256Hex(request.model() + "\n" + request.content());
        String text = "MOCK completion (not an AI answer) for model \"" + request.model()
                + "\": " + request.content().length() + " chars, digest " + digest.substring(0, 16) + ".";
        return new LlmResponse(request.model(), text);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte singleByte : hash) {
                hex.append(String.format("%02x", singleByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available.", ex);
        }
    }
}

package com.aegivault.aegivault.sanitization.strategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared SHA-256 digest helper for the hash and synthetic-value strategies.
 *
 * <p>UTF-8 input bytes, no salt, no randomness, no counter — the digest depends
 * on the value alone, which is what makes the transformations deterministic and
 * relationship preserving. Output is lowercase hexadecimal.
 *
 * <p>The input value is never logged, stored, or included in a message. SHA-256
 * is required of every Java platform, so a missing implementation is reported
 * as an {@link IllegalStateException} about the algorithm only.
 */
final class Sha256Digest {

    private static final String ALGORITHM = "SHA-256";

    private Sha256Digest() {
    }

    /**
     * @param value non-null value to digest
     * @return the 32 digest bytes
     */
    static byte[] bytes(String value) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ALGORITHM + " is not available in this Java runtime.");
        }
    }

    /**
     * @param value non-null value to digest
     * @return the digest as 64 lowercase hexadecimal characters
     */
    static String hex(String value) {
        return HexFormat.of().formatHex(bytes(value));
    }
}

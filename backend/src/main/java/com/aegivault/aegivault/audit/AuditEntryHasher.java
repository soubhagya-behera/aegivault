package com.aegivault.aegivault.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic hash for one audit ledger entry.
 *
 * <p>The canonical form is explicit and unambiguous: a fixed version
 * prefix followed by one length-prefixed segment per field, in fixed
 * order. Lengths are UTF-8 byte counts, so no delimiter can ever be
 * confused with field content and two different field tuples can never
 * share one canonical text. A null {@code resourceId} canonicalizes as
 * the literal {@code null}, which can never collide with a real UUID.
 * Nothing here depends on {@link Object#toString()}, map iteration
 * order, locale, or time — identical inputs always hash identically.
 *
 * <p>The digest is SHA-256 over the canonical UTF-8 bytes, rendered as
 * 64 lowercase hexadecimal characters.
 */
final class AuditEntryHasher {

    private static final String ALGORITHM = "SHA-256";

    private static final String VERSION_PREFIX = "audit-ledger-v1";

    private AuditEntryHasher() {
    }

    /**
     * @return the entry hash for the given canonical fields, 64 lowercase
     *         hex characters, never blank
     * @throws NullPointerException when any field is null (a null
     *         {@code resourceId} is legal and handled separately)
     */
    static String hash(
            long sequenceNumber,
            String eventType,
            String actorSubject,
            String resourceType,
            UUID resourceId,
            String eventData,
            String previousHash) {
        return hex(canonicalEntry(
                sequenceNumber, eventType, actorSubject, resourceType,
                resourceId, eventData, previousHash));
    }

    /**
     * @return the exact canonical text that is hashed, never blank. The
     *         format is part of the ledger's integrity contract: it must
     *         never change for entries already stored.
     */
    static String canonicalEntry(
            long sequenceNumber,
            String eventType,
            String actorSubject,
            String resourceType,
            UUID resourceId,
            String eventData,
            String previousHash) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(actorSubject, "actorSubject must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(eventData, "eventData must not be null");
        Objects.requireNonNull(previousHash, "previousHash must not be null");
        StringBuilder canonical = new StringBuilder(VERSION_PREFIX);
        segment(canonical, "seq", Long.toString(sequenceNumber));
        segment(canonical, "type", eventType);
        segment(canonical, "actor", actorSubject);
        segment(canonical, "resource", resourceType);
        segment(canonical, "resourceId", resourceId == null ? "null" : resourceId.toString());
        segment(canonical, "data", eventData);
        segment(canonical, "prev", previousHash);
        return canonical.toString();
    }

    private static void segment(StringBuilder canonical, String name, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        canonical.append('|').append(name).append(':').append(bytes.length).append(':').append(value);
    }

    private static String hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ALGORITHM + " is not available in this Java runtime.");
        }
    }
}

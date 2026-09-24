package com.aegivault.aegivault.gateway;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic tokenization shared by the gateway inspection layer.
 *
 * <p>The existing PII detectors classify whole values only and never scan
 * text, so request prose is split into candidate tokens first: whitespace
 * separates, and edge punctuation (quotes, brackets, sentence periods) is
 * stripped so a token like {@code "alice@example.com."} still classifies as
 * the value it contains. Interior characters are never altered, and
 * over-long tokens are skipped to bound the work.
 */
final class GatewayTokens {

    /** Longest token offered to a detector; mirrors the JWT detector ceiling. */
    private static final int MAX_TOKEN_LENGTH = 8192;

    private GatewayTokens() {
    }

    static List<String> split(String content) {
        List<String> tokens = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return tokens;
        }
        for (String raw : content.split("\\s+")) {
            String token = stripEdges(raw);
            if (!token.isEmpty() && token.length() <= MAX_TOKEN_LENGTH) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String stripEdges(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && isEdge(raw.charAt(start))) {
            start++;
        }
        while (end > start && isEdge(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(start, end);
    }

    private static boolean isEdge(char c) {
        return c == ','
                || c == '.'
                || c == ';'
                || c == ':'
                || c == '"'
                || c == '\''
                || c == '`'
                || c == '('
                || c == ')'
                || c == '['
                || c == ']'
                || c == '{'
                || c == '}'
                || c == '<'
                || c == '>'
                || c == '!'
                || c == '?';
    }
}

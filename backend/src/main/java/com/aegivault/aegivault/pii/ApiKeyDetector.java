package com.aegivault.aegivault.pii;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative detector for recognizable API-key formats.
 *
 * <p>Matches the whole input value only; never scans text. API keys have no
 * universal syntax, so this detector accepts only values with strong
 * structural evidence: known provider prefixes ({@code sk-},
 * {@code sk-proj-}, {@code ghp_/gho_/ghu_/ghs_/ghr_}) or an explicit
 * {@code api_key}/{@code apikey}/{@code api-key} label. A bare long
 * alphanumeric string is never enough on its own. Length thresholds only
 * establish key-like structure; they do not prove a key is real or active.
 * Input is classified exactly as supplied; nothing is trimmed, lowercased,
 * logged, or returned.
 */
@Component
public class ApiKeyDetector implements PiiDetector {

    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9]{20,}[A-Za-z0-9_\\-]*");

    private static final Pattern OPENAI_PROJECT_KEY =
            Pattern.compile("sk-proj-[A-Za-z0-9]{20,}[A-Za-z0-9_\\-]*");

    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}[A-Za-z0-9_\\-]*");

    private static final Pattern LABELLED_KEY = Pattern.compile(
            "(?i)(?:api_key|apikey|api-key)\\s*[:=]\\s*[A-Za-z0-9_\\-]{16,}[A-Za-z0-9_\\-]*");

    private static final int MAX_LENGTH = 500;

    @Override
    public Optional<PiiDetection> detect(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (OPENAI_PROJECT_KEY.matcher(value).matches()
                || OPENAI_KEY.matcher(value).matches()
                || GITHUB_TOKEN.matcher(value).matches()
                || LABELLED_KEY.matcher(value).matches()) {
            return Optional.of(new PiiDetection(PiiType.API_KEY));
        }
        return Optional.empty();
    }
}

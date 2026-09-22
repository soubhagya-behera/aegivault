package com.aegivault.aegivault.sanitization.artifact;

/**
 * Thrown when sanitized output exceeds the artifact size bound. The message
 * names the bound only — never any output content.
 */
public class ArtifactTooLargeException extends RuntimeException {

    public ArtifactTooLargeException(long maxBytes) {
        super("Sanitized output exceeds the maximum supported size of " + maxBytes + " bytes.");
    }
}

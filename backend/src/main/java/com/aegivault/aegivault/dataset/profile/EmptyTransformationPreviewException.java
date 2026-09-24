package com.aegivault.aegivault.dataset.profile;

/**
 * Thrown when a persisted profile contains no detected PII types to build a
 * policy from. Creating an empty policy is rejected the same way the policy
 * aggregate rejects an empty rule list — an empty policy could never
 * sanitize anything yet would look like protection — so the request fails
 * before anything is persisted. The message carries no values of any kind.
 */
public class EmptyTransformationPreviewException extends IllegalArgumentException {

    public EmptyTransformationPreviewException() {
        super("No detected PII types to build a policy from.");
    }
}

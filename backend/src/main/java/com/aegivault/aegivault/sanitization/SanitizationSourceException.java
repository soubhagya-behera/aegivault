package com.aegivault.aegivault.sanitization;

/**
 * Thrown when a sanitization source cannot supply its content: the source was
 * unreachable, its metadata could not be read, or the configured table no longer
 * exists.
 *
 * <p><strong>Distinct from a transformation failure.</strong> {@link SanitizationException}
 * means a value could not be transformed; this means the content never arrived.
 * An operator diagnosing an incident needs those to stay separate, so
 * {@code SanitizationRunExecutor} maps this to its own run-failure stage rather
 * than folding it into the transform stage.
 *
 * <p><strong>The message is fixed and safe.</strong> It carries no JDBC URL,
 * host, port, database, username, password, SQL text, SQL state, driver text,
 * table name, or row value. A raw driver exception routinely contains all of
 * those, so it is retained only as the cause for server-side logs and is never
 * used to build this message.
 *
 * <p>Source-specific subclasses may narrow the wording, but no subclass may add
 * a detail that identifies the source's contents.
 */
public class SanitizationSourceException extends RuntimeException {

    /** The only safe source-failure message, shared by every throw site. */
    public static final String MESSAGE = "The sanitization source could not be read.";

    public SanitizationSourceException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public SanitizationSourceException() {
        super(MESSAGE);
    }
}
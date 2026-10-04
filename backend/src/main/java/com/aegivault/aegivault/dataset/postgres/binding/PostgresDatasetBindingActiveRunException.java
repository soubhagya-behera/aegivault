package com.aegivault.aegivault.dataset.postgres.binding;

/**
 * Thrown when a binding cannot be deleted because the dataset still has a
 * PostgreSQL sanitization run that is {@code QUEUED} or {@code RUNNING}.
 *
 * <p>PostgreSQL execution re-resolves the binding when a queued worker starts,
 * so a {@code QUEUED} run must not lose its binding and a {@code RUNNING} run
 * must not lose its binding either. Deletion is therefore refused while such
 * a run exists; the runs themselves are never cancelled, failed, or altered.
 *
 * <p>The message is fixed and names no run, count, policy, table, or actor.
 */
public class PostgresDatasetBindingActiveRunException extends RuntimeException {

    /** The only safe active-run message, shared by every throw site. */
    public static final String MESSAGE =
            "PostgreSQL dataset binding cannot be deleted while a sanitization run is active.";

    public PostgresDatasetBindingActiveRunException() {
        super(MESSAGE);
    }
}

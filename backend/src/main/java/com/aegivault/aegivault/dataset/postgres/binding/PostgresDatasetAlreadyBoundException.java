package com.aegivault.aegivault.dataset.postgres.binding;

/**
 * Thrown when a dataset already has a PostgreSQL binding, so a second one was
 * refused.
 *
 * <p>This is the service-level expression of the "at most one source table per
 * dataset" rule; the same rule is also a database guarantee, since
 * {@code dataset_id} is the binding's primary key. Reassignment is an explicit
 * delete-then-bind rather than an implicit overwrite, so a caller can never
 * silently change which source table a dataset points at.
 *
 * <p>The message is fixed and names no dataset, owner, schema, or table.
 */
public class PostgresDatasetAlreadyBoundException extends RuntimeException {

    /** The only safe already-bound message, shared by every throw site. */
    public static final String MESSAGE = "The dataset is already bound to a PostgreSQL table.";

    public PostgresDatasetAlreadyBoundException() {
        super(MESSAGE);
    }
}

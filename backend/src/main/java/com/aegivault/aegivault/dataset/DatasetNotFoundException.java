package com.aegivault.aegivault.dataset;

/**
 * Thrown when no dataset with the id exists for the calling owner.
 * Public so neighboring modules (e.g. run execution reading through
 * {@link DatasetInputSource}) can translate it into their own
 * owner-scoped not-found semantics instead of inventing new ones.
 */
public class DatasetNotFoundException extends RuntimeException {

    public DatasetNotFoundException() {
        super("Dataset not found.");
    }
}

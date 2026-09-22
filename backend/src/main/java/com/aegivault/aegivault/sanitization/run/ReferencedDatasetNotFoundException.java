package com.aegivault.aegivault.sanitization.run;

/**
 * Thrown when the dataset referenced by a run operation does not exist for
 * the calling owner. Missing datasets and other owners' datasets produce
 * this same exception, mirroring the dataset API's generic 404 wording so
 * callers cannot probe for another owner's datasets.
 */
public class ReferencedDatasetNotFoundException extends RuntimeException {

    public ReferencedDatasetNotFoundException() {
        super("Dataset not found.");
    }
}

package com.aegivault.aegivault.dataset;

/** Thrown when no dataset with the id exists for the calling owner. */
class DatasetNotFoundException extends RuntimeException {

    DatasetNotFoundException() {
        super("Dataset not found.");
    }
}

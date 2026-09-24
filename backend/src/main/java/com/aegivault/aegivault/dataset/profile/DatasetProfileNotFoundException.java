package com.aegivault.aegivault.dataset.profile;

import com.aegivault.aegivault.dataset.DatasetNotFoundException;

/**
 * Thrown when no stored profile exists for the calling owner and dataset
 * id — either because the dataset is missing, belongs to another owner, or
 * simply has no persisted profile yet. Extends
 * {@link DatasetNotFoundException} so the existing dataset handler renders
 * the same generic 404 body for all three cases, revealing nothing about
 * which one failed.
 */
public class DatasetProfileNotFoundException extends DatasetNotFoundException {

    public DatasetProfileNotFoundException() {
        super();
    }
}

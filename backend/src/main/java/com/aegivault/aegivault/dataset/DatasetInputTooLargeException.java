package com.aegivault.aegivault.dataset;

/**
 * Thrown when CSV input exceeds the stored-input size bound. The message
 * names the bound only — never any input content.
 */
public class DatasetInputTooLargeException extends RuntimeException {

    public DatasetInputTooLargeException(long maxBytes) {
        super("CSV input exceeds the maximum supported size of " + maxBytes + " bytes.");
    }
}

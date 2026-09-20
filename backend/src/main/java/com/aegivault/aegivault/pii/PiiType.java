package com.aegivault.aegivault.pii;

/**
 * Closed set of detectable sensitive-data categories.
 * Foundation only; no detection logic lives here.
 */
public enum PiiType {
    EMAIL,
    PHONE,
    PERSON_NAME,
    ADDRESS,
    CREDIT_CARD,
    IP_ADDRESS,
    UUID,
    API_KEY,
    PASSWORD,
    JWT,
    CUSTOM_IDENTIFIER
}

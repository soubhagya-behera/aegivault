package com.aegivault.aegivault.auth;

/** Small error body for authentication failures. Messages stay generic so
 * login responses never reveal whether an email is registered. */
public record ApiError(String message) {}

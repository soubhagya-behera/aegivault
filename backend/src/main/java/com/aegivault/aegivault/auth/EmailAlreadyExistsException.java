package com.aegivault.aegivault.auth;

/** Thrown when registration uses an email that is already taken. Mapped to
 * 409 by {@link AuthController}; the message names no other account detail. */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Email is already registered: " + email);
    }
}

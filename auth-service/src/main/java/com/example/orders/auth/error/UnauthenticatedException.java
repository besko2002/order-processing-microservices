package com.example.orders.auth.error;

/** The presented token was technically valid but no longer maps to an existing account. */
public class UnauthenticatedException extends RuntimeException {

    /** Same wording as the security entry point, so callers cannot probe for account existence. */
    public static final String MESSAGE = "Authentication is required to access this resource";

    public UnauthenticatedException() {
        super(MESSAGE);
    }
}

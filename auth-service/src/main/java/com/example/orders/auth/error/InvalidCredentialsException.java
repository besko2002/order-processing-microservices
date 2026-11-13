package com.example.orders.auth.error;

/**
 * Login failed. Deliberately carries no detail about <em>why</em>: an unknown email and a wrong
 * password must be indistinguishable to the caller.
 */
public class InvalidCredentialsException extends RuntimeException {

    /** The single message returned for every failed login. */
    public static final String MESSAGE = "Invalid email or password";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}

package com.example.orders.auth.error;

/** Registration hit an email that is already taken (comparison is case-insensitive). */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Email is already registered: " + email);
    }
}

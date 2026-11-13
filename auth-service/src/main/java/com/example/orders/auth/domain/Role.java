package com.example.orders.auth.domain;

/** Roles carried in the {@code roles} claim. Self-registration always yields {@link #USER}. */
public enum Role {
    USER,
    ADMIN
}

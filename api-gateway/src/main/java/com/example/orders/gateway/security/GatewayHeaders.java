package com.example.orders.gateway.security;

/** Header names the gateway owns end to end. */
public final class GatewayHeaders {

    /** Request/response correlation id, generated here when the client did not supply a sane one. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** Authenticated user id ({@code sub} claim), set by the gateway only. */
    public static final String USER_ID = "X-User-Id";

    /** Authenticated user's roles, comma separated and without the {@code ROLE_} prefix. */
    public static final String USER_ROLES = "X-User-Roles";

    private GatewayHeaders() {
    }
}

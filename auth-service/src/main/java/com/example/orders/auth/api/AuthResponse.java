package com.example.orders.auth.api;

/**
 * Identical shape for register and login.
 *
 * @param accessToken signed RS256 JWT
 * @param tokenType   always {@code Bearer}
 * @param expiresIn   token lifetime in seconds
 * @param user        the authenticated account
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    /** The only token type this service issues. */
    public static final String BEARER = "Bearer";

    /**
     * @param accessToken signed token
     * @param expiresIn   lifetime in seconds
     * @param user        the authenticated account
     * @return a bearer-token response
     */
    public static AuthResponse bearer(String accessToken, long expiresIn, UserResponse user) {
        return new AuthResponse(accessToken, BEARER, expiresIn, user);
    }
}

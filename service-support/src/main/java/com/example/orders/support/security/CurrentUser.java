package com.example.orders.support.security;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the authenticated caller out of the security context.
 *
 * <p>The {@code sub} claim of every token auth-service issues is the user's UUID, so business code
 * never has to parse a JWT itself. Nothing here trusts a request header: {@code X-User-Id} from the
 * gateway is informational only, the token is the source of truth.
 */
public final class CurrentUser {

    /** Role prefix Spring Security uses; the {@code roles} claim is stored without it. */
    public static final String ROLE_PREFIX = "ROLE_";

    private CurrentUser() {
    }

    /**
     * @return the caller's id, or empty when the request is anonymous or {@code sub} is not a UUID
     */
    public static Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        String subject = authentication.getPrincipal() instanceof Jwt jwt
                ? jwt.getSubject()
                : authentication.getName();
        return parseUuid(subject);
    }

    /**
     * @return the caller's id
     * @throws IllegalStateException when there is no authenticated caller; call it only from
     *                               endpoints the security rules already require a token for
     */
    public static UUID requireId() {
        return id().orElseThrow(() -> new IllegalStateException("No authenticated user in the security context"));
    }

    /**
     * @return role names without the {@code ROLE_} prefix, e.g. {@code ["ADMIN"]}; empty when
     *         anonymous
     */
    public static Set<String> roles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith(ROLE_PREFIX)) {
                roles.add(name.substring(ROLE_PREFIX.length()));
            }
        }
        return roles;
    }

    /**
     * @param role role name without the {@code ROLE_} prefix
     * @return whether the caller holds it
     */
    public static boolean hasRole(String role) {
        return roles().contains(role);
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

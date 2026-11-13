package com.example.orders.auth.api;

import com.example.orders.auth.domain.Role;
import com.example.orders.auth.domain.UserAccount;
import java.util.UUID;

/**
 * Public view of an account. The password hash is never part of any response.
 *
 * @param id    account id, also the token's {@code sub}
 * @param name  display name
 * @param email normalised email
 * @param role  assigned role
 */
public record UserResponse(UUID id, String name, String email, Role role) {

    /**
     * @param user persisted account
     * @return its public projection
     */
    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}

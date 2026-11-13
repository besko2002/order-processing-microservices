package com.example.orders.auth.api;

import com.example.orders.auth.domain.Emails;
import jakarta.validation.constraints.NotBlank;

/**
 * Login payload. The email is normalised the same way as during registration so that the case
 * used at sign-up is irrelevant. No format/length constraints beyond "present": a malformed
 * email must fail as a plain 401, not leak a different status than a wrong password.
 *
 * @param email    login email, trimmed and lowercased
 * @param password plain password
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank") String email,
        @NotBlank(message = "must not be blank") String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}

package com.example.orders.support.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Missing, malformed, tampered or expired token — all of them become the same 401
 * {@link ApiError}, with no hint about which check failed.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Same wording auth-service uses, so the edge and the services stay indistinguishable. */
    public static final String MESSAGE = "Authentication is required to access this resource";

    private final ApiErrorHttpWriter writer;

    /**
     * @param writer renders the shared error body onto the raw response
     */
    public RestAuthenticationEntryPoint(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        writer.write(request, response, HttpStatus.UNAUTHORIZED, MESSAGE);
    }
}

package com.example.orders.auth.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Missing, malformed, tampered or expired token — all of them become the same 401
 * {@link ApiError}, with no hint about which check failed.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorHttpWriter writer;

    RestAuthenticationEntryPoint(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        writer.write(request, response, HttpStatus.UNAUTHORIZED, UnauthenticatedException.MESSAGE);
    }
}

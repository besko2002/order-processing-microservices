package com.example.orders.auth.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Authenticated but not allowed: same body shape as every other error, status 403. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorHttpWriter writer;

    RestAccessDeniedHandler(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(request, response, HttpStatus.FORBIDDEN, "Access is denied");
    }
}

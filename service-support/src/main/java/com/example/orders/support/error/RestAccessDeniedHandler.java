package com.example.orders.support.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Authenticated but not allowed: same body shape as every other error, status 403. */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    /** The single message returned for every authorisation failure. */
    public static final String MESSAGE = "Access is denied";

    private final ApiErrorHttpWriter writer;

    /**
     * @param writer renders the shared error body onto the raw response
     */
    public RestAccessDeniedHandler(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(request, response, HttpStatus.FORBIDDEN, MESSAGE);
    }
}

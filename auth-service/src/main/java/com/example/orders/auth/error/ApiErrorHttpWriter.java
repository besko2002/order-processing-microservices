package com.example.orders.auth.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes an {@link ApiError} straight onto the response. Needed because Spring Security's
 * 401/403 handlers run in the filter chain, outside {@code @RestControllerAdvice} reach.
 */
@Component
class ApiErrorHttpWriter {

    private final ObjectMapper objectMapper;

    ApiErrorHttpWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrors.of(status, message, request.getRequestURI()));
    }
}

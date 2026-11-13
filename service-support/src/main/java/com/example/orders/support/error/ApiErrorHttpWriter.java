package com.example.orders.support.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes an {@link ApiError} straight onto the response. Needed because Spring Security's
 * 401/403 handlers run in the filter chain, outside {@code @RestControllerAdvice} reach.
 */
public class ApiErrorHttpWriter {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the application's mapper, so the JSON looks like every other response
     */
    public ApiErrorHttpWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param request  request being answered; only its URI is exposed in the body
     * @param response response to write the body onto
     * @param status   status to send
     * @param message  safe-to-expose detail
     * @throws IOException when the response can no longer be written
     */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrors.of(status, message, request.getRequestURI()));
    }
}

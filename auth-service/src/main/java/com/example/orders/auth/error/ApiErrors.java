package com.example.orders.auth.error;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Factory so every producer of {@link ApiError} fills the same fields the same way. */
public final class ApiErrors {

    private ApiErrors() {
    }

    /**
     * @param status  HTTP status to report
     * @param message safe-to-expose detail
     * @param path    request URI
     * @return an error body without field errors
     */
    public static ApiError of(HttpStatus status, String message, String path) {
        return of(status, message, path, null);
    }

    /**
     * @param status      HTTP status to report
     * @param message     safe-to-expose detail
     * @param path        request URI
     * @param fieldErrors validation failures; {@code null} or empty leaves the key out of the JSON
     * @return the error body
     */
    public static ApiError of(HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}

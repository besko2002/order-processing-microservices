package com.example.orders.auth.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * The one error body shape auth-service ever returns — from controllers, from the validation
 * layer and from Spring Security's 401/403 handlers alike.
 *
 * @param timestamp   when the failure was rendered
 * @param status      HTTP status code
 * @param error       HTTP reason phrase
 * @param message     human readable, safe-to-expose detail
 * @param path        request URI
 * @param fieldErrors field name to message, omitted entirely unless there are validation errors
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {
}

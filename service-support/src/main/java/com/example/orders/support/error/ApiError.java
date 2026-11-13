package com.example.orders.support.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * The one error body shape every service built on this module returns — from controllers, from
 * the validation layer and from Spring Security's 401/403 handlers alike. Deliberately identical
 * to the bodies auth-service and api-gateway already produce, so a client sees one error format
 * across the whole system.
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

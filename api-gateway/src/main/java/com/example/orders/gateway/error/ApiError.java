package com.example.orders.gateway.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The error body the gateway returns for the failures it handles itself (401, 403, 429).
 * Deliberately the same shape auth-service — and every later service — returns, so a client
 * parses one error format no matter which hop rejected the call.
 *
 * @param timestamp when the failure was rendered
 * @param status    HTTP status code
 * @param error     HTTP reason phrase
 * @param message   human readable, safe-to-expose detail
 * @param path      request path
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}

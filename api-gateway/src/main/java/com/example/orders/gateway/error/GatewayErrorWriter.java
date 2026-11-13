package com.example.orders.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders an {@link ApiError} straight onto the reactive response. The gateway rejects
 * requests inside WebFilters and GlobalFilters, far away from any {@code @RestControllerAdvice},
 * so the body has to be serialised by hand.
 */
@Component
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    GatewayErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param exchange current exchange; its response must not be committed yet
     * @param status   status to report
     * @param message  safe-to-expose detail
     * @return completion of the response write
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        return write(exchange.getResponse(), exchange.getRequest().getPath().value(), status, message);
    }

    /**
     * @param response response to write on, possibly a decorator rather than the exchange's own
     * @param path     request path to report in the body
     * @param status   status to report
     * @param message  safe-to-expose detail
     * @return completion of the response write
     */
    public Mono<Void> write(ServerHttpResponse response, String path, HttpStatus status, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(serialise(status, message, path));
        return response.writeWith(Mono.just(buffer))
                .doOnError(error -> DataBufferUtils.release(buffer));
    }

    private byte[] serialise(HttpStatus status, String message, String path) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path);
        try {
            return objectMapper.writeValueAsBytes(body);
        }
        catch (JsonProcessingException e) {
            // Cannot realistically happen for a record of scalars; never fail the rejection itself.
            return ("{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}

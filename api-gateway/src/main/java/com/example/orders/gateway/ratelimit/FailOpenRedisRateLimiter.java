package com.example.orders.gateway.ratelimit;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Token-bucket rate limiter backed by the stock Spring Cloud Gateway Lua script, with one
 * deliberate difference: <strong>it fails open</strong>.
 *
 * <p>If Redis is unreachable or the script errors, the request is allowed through and a WARN is
 * logged. Rate limiting protects the platform from abuse; it is not an authorisation decision,
 * and taking the whole API down because a cache is down trades a small problem for a total
 * outage. The WARN (rather than the DEBUG/ERROR the stock limiter emits) is the signal to alert
 * on — if it appears continuously, the limiter is effectively disabled.
 *
 * <p>The window in which a request can hang waiting for a dead Redis is bounded by
 * {@code spring.data.redis.connect-timeout} / {@code spring.data.redis.timeout}, kept short on
 * purpose.
 */
public class FailOpenRedisRateLimiter extends RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailOpenRedisRateLimiter.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> script;

    /**
     * @param redisTemplate        reactive template against the rate-limit Redis
     * @param script               the gateway's {@code request_rate_limiter.lua}
     * @param configurationService binds the per-route {@code redis-rate-limiter.*} filter args
     */
    public FailOpenRedisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<List<Long>> script,
            ConfigurationService configurationService) {
        super(redisTemplate, script, configurationService);
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = resolveConfig(routeId);
        List<String> keys = keys(routeId, id);
        List<String> args = List.of(
                String.valueOf(config.getReplenishRate()),
                String.valueOf(config.getBurstCapacity()),
                "",
                String.valueOf(config.getRequestedTokens()));

        return redisTemplate.execute(script, keys, args)
                .reduce(new ArrayList<Long>(), (all, chunk) -> {
                    all.addAll(chunk);
                    return all;
                })
                .map(results -> new Response(results.get(0) == 1L, getHeaders(config, results.get(1))))
                .onErrorResume(error -> failOpen(routeId, config, error));
    }

    private Mono<Response> failOpen(String routeId, Config config, Throwable error) {
        log.warn("Rate limiting unavailable for route '{}', allowing the request through: {}",
                routeId, error.toString());
        return Mono.just(new Response(true, getHeaders(config, -1L)));
    }

    /**
     * Same resolution order as the stock limiter: per-route {@code redis-rate-limiter.*} args
     * first, then the ones declared on {@code default-filters}.
     */
    private Config resolveConfig(String routeId) {
        Config config = getConfig().get(routeId);
        if (config == null) {
            config = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
        }
        if (config == null) {
            throw new IllegalArgumentException(
                    "No redis-rate-limiter configuration found for route " + routeId + " or defaultFilters");
        }
        return config;
    }

    /** Redis key layout of the stock limiter: one bucket per route and per caller. */
    private static List<String> keys(String routeId, String id) {
        // The {} hash tag keeps both keys on the same slot in a Redis cluster.
        String prefix = "request_rate_limiter.{" + routeId + "." + id + "}.";
        return List.of(prefix + "tokens", prefix + "timestamp");
    }

    @Override
    public String toString() {
        return "FailOpenRedisRateLimiter" + getConfig();
    }
}

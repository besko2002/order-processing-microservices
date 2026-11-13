package com.example.orders.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

/** The fail-open contract: a broken Redis must never turn into a broken API. */
class FailOpenRedisRateLimiterTest {

    @SuppressWarnings("unchecked")
    private final RedisScript<List<Long>> script = mock(RedisScript.class);

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);

    private final FailOpenRedisRateLimiter limiter =
            new FailOpenRedisRateLimiter(redisTemplate, script, null);

    FailOpenRedisRateLimiterTest() {
        limiter.getConfig().put(RouteDefinitionRouteLocator.DEFAULT_FILTERS,
                new RedisRateLimiter.Config().setReplenishRate(10).setBurstCapacity(20).setRequestedTokens(1));
    }

    @Test
    void allowsTheRequestWhenRedisIsUnreachable() {
        when(redisTemplate.execute(anyScript(), anyList(), anyList()))
                .thenReturn(Flux.error(new RedisConnectionFailureException("redis is down")));

        RateLimiter.Response response = limiter.isAllowed("order-service", "user:42").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(RedisRateLimiter.REMAINING_HEADER, "-1");
    }

    @Test
    void honoursTheScriptVerdictWhenRedisAnswers() {
        when(redisTemplate.execute(anyScript(), anyList(), anyList()))
                .thenReturn(Flux.just(List.of(0L, 0L)));

        RateLimiter.Response response = limiter.isAllowed("order-service", "user:42").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getHeaders())
                .containsEntry(RedisRateLimiter.BURST_CAPACITY_HEADER, "20")
                .containsEntry(RedisRateLimiter.REPLENISH_RATE_HEADER, "10");
    }

    @Test
    void usesTheDefaultFiltersConfigurationWhenTheRouteHasNoneOfItsOwn() {
        when(redisTemplate.execute(anyScript(), anyList(), anyList()))
                .thenReturn(Flux.just(List.of(1L, 7L)));

        RateLimiter.Response response = limiter.isAllowed("some-unconfigured-route", "ip:127.0.0.1").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(RedisRateLimiter.REMAINING_HEADER, "7");
    }

    private static RedisScript<List<Long>> anyScript() {
        return any();
    }
}

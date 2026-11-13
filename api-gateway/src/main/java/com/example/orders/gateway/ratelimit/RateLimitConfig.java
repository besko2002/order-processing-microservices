package com.example.orders.gateway.ratelimit;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Wires the Redis-backed {@code RequestRateLimiter}: the fail-open limiter replaces the one
 * Spring Cloud Gateway would auto-configure (its {@code @ConditionalOnMissingBean} backs off as
 * soon as a {@link RedisRateLimiter} bean exists here), and the key resolver replaces the
 * default principal-name one.
 *
 * <p>Limits themselves live in {@code application.yml} under {@code default-filters}, so they
 * apply to every {@code /api/**} route and stay environment-overridable.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    @Bean
    KeyResolver userOrIpKeyResolver() {
        return new UserOrIpKeyResolver();
    }

    /**
     * @param redisTemplate        reactive template against the rate-limit Redis
     * @param script               the gateway's bundled token-bucket Lua script
     * @param configurationService binds the {@code redis-rate-limiter.*} filter arguments
     * @return the limiter used by the {@code RequestRateLimiter} filter
     */
    @Bean
    @ConfigurationProperties("spring.cloud.gateway.server.webflux.redis-rate-limiter")
    RedisRateLimiter redisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
            ConfigurationService configurationService) {
        return new FailOpenRedisRateLimiter(redisTemplate, script, configurationService);
    }
}

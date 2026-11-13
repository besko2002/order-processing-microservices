package com.example.orders.support.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Gives every servlet service the shared error body: the {@code @RestControllerAdvice} for
 * controller failures and the two Spring Security handlers for 401/403, which are raised inside
 * the filter chain where the advice cannot reach.
 *
 * <p>Every bean backs off when the service declares its own, so a service can still specialise
 * one piece without losing the rest.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServiceErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiErrorHttpWriter apiErrorHttpWriter(ObjectMapper objectMapper) {
        return new ApiErrorHttpWriter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticationEntryPoint.class)
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ApiErrorHttpWriter writer) {
        return new RestAuthenticationEntryPoint(writer);
    }

    @Bean
    @ConditionalOnMissingBean(AccessDeniedHandler.class)
    public RestAccessDeniedHandler restAccessDeniedHandler(ApiErrorHttpWriter writer) {
        return new RestAccessDeniedHandler(writer);
    }
}

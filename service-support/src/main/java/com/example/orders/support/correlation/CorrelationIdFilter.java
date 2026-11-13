package com.example.orders.support.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the correlation id of every request into the SLF4J MDC and echoes it back on the response.
 *
 * <p>Registered at the very front of the servlet filter chain (before Spring Security), so a 401
 * or 403 is logged and answered with the same id as a successful call. The MDC is always cleared
 * again: servlet containers reuse threads, and a leaked id would mislabel the next request.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = CorrelationId.sanitise(request.getHeader(CorrelationId.HEADER));

        // set(), not add(): the gateway already put the id on its own response, and a duplicate
        // header value downstream would leave the client with two ids.
        response.setHeader(CorrelationId.HEADER, correlationId);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}

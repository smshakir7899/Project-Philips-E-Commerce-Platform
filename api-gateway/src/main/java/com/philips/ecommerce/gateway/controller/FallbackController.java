package com.philips.ecommerce.gateway.controller;

import com.philips.ecommerce.gateway.dto.GatewayErrorResponse;
import com.philips.ecommerce.gateway.filter.CorrelationIdGlobalFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Target of the CircuitBreaker filters' {@code fallbackUri: forward:/fallback/<service>}.
 *
 * <p>The gateway forwards here when the downstream call failed with an exception (connection refused, no instance
 * registered in Eureka, response timeout, TimeLimiter timeout) or when the circuit is OPEN. It answers with a 503 in
 * the platform's standard error shape instead of a raw Netty error page or a hanging request.</p>
 *
 * <p>{@code @RequestMapping} without a method on purpose: a forward keeps the original HTTP method, so the fallback
 * must accept POST/PUT/DELETE as well as GET.</p>
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/{service:[a-z][a-z0-9-]{0,62}}")
    public Mono<ResponseEntity<GatewayErrorResponse>> fallback(@PathVariable("service") String service,
                                                               ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        String path = originalPath(exchange);
        String correlationId = correlationId(exchange);

        log.warn("Fallback for {} on {} [correlationId={}]: {}", service, path, correlationId,
                cause != null ? cause.toString() : "no exception recorded");

        GatewayErrorResponse body = GatewayErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .error(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
                // product-service -> PRODUCT_SERVICE_UNAVAILABLE (same convention the services use for Feign errors)
                .code(service.toUpperCase(Locale.ROOT).replace('-', '_') + "_UNAVAILABLE")
                .message(describe(service, cause))
                .path(path)
                .correlationId(correlationId)
                .build();

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    private String describe(String service, Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            return service + " is temporarily unavailable (circuit breaker open), please retry later";
        }
        // TimeLimiter timeout -> java.util.concurrent.TimeoutException;
        // httpclient response-timeout -> ResponseStatusException(504) wrapping a TimeoutException.
        boolean gatewayTimeout = cause instanceof ResponseStatusException
                && ((ResponseStatusException) cause).getStatus() == HttpStatus.GATEWAY_TIMEOUT;
        if (cause instanceof TimeoutException || gatewayTimeout) {
            return service + " did not respond in time, please retry later";
        }
        return service + " is currently unavailable, please retry later";
    }

    /**
     * After the forward the request path is /fallback/..., but the client called e.g. /api/products/42.
     * CorrelationIdGlobalFilter stored the client's path before routing; fall back to the current path when the
     * endpoint is called directly.
     */
    private String originalPath(ServerWebExchange exchange) {
        String original = exchange.getAttribute(CorrelationIdGlobalFilter.ORIGINAL_PATH_ATTRIBUTE);
        return original != null ? original : exchange.getRequest().getURI().getRawPath();
    }

    private String correlationId(ServerWebExchange exchange) {
        String fromAttribute = exchange.getAttribute(CorrelationIdGlobalFilter.ATTRIBUTE);
        if (fromAttribute != null) {
            return fromAttribute;
        }
        return exchange.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
    }
}

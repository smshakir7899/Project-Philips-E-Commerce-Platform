package com.philips.ecommerce.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Makes sure every request that passes through the gateway carries an {@code X-Correlation-Id}:
 * <ol>
 *     <li>reuses the client's id if it is present and well-formed, otherwise generates a UUID;</li>
 *     <li>adds it to the request forwarded downstream (services put it into their MDC, Feign and Kafka headers);</li>
 *     <li>echoes it in the response so a client/support engineer can quote it when reporting a problem;</li>
 *     <li>writes one access-log line with method, path, status and latency.</li>
 * </ol>
 * It also remembers the path the client called ({@link #ORIGINAL_PATH_ATTRIBUTE}): a CircuitBreaker fallback
 * ({@code forward:/fallback/...}) replaces the request path, but the error body should report what the client sent.
 *
 * <p>Why not MDC here? MDC is thread-local; in a reactive pipeline one request hops across event-loop threads, so a
 * thread-local value would leak to other requests or vanish. The id is therefore carried in the exchange
 * (request header + attribute) and logged explicitly.</p>
 *
 * <p>Ordered with {@link Ordered#HIGHEST_PRECEDENCE} so it runs before every other global filter, including the
 * routing filters, and the measured latency covers the whole gateway processing.</p>
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    /** Exchange attribute, readable by other filters / the fallback controller. */
    public static final String ATTRIBUTE = CorrelationIdGlobalFilter.class.getName() + ".correlationId";
    /** Exchange attribute holding the raw path exactly as received from the client. */
    public static final String ORIGINAL_PATH_ATTRIBUTE = CorrelationIdGlobalFilter.class.getName() + ".originalPath";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    /**
     * Incoming ids are client-controlled and end up in every log line of every service. Accept only a bounded,
     * harmless character set to prevent log forging (e.g. injected newlines) and unbounded header sizes.
     */
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        final long startNanos = System.nanoTime();
        final String correlationId = resolveCorrelationId(exchange.getRequest());

        // Request headers are read-only in WebFlux: build a mutated copy of the request for downstream.
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        // Attributes are shared by the original and every mutated exchange (decorators delegate to one map)
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        mutated.getAttributes().put(ORIGINAL_PATH_ATTRIBUTE, exchange.getRequest().getURI().getRawPath());

        final ServerHttpResponse response = mutated.getResponse();
        // beforeCommit runs right before the status line/headers are written, i.e. AFTER the downstream response
        // headers were copied into the gateway response, so our value can never be overwritten or duplicated.
        response.beforeCommit(() -> {
            response.getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(mutated)
                .doFinally(signal -> logAccess(mutated, correlationId, startNanos));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolveCorrelationId(ServerHttpRequest request) {
        String incoming = request.getHeaders().getFirst(HEADER);
        if (incoming != null && VALID_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private void logAccess(ServerWebExchange exchange, String correlationId, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        HttpStatus status = exchange.getResponse().getStatusCode();
        log.info("{} {} -> {} in {} ms [correlationId={}]",
                exchange.getRequest().getMethodValue(),
                exchange.getAttributeOrDefault(ORIGINAL_PATH_ATTRIBUTE, exchange.getRequest().getURI().getRawPath()),
                status != null ? Integer.toString(status.value()) : "-",
                latencyMs,
                correlationId);
    }
}

package com.philips.ecommerce.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesIdWhenMissingAndPropagatesItDownstreamAndInResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products/1"));
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();
        // beforeCommit callbacks run when the response is committed (normally done by the routing filter)
        exchange.getResponse().setComplete().block();

        String downstream = chain.captured.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
        assertThat(downstream).isNotNull();
        assertThat(UUID.fromString(downstream)).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER)).isEqualTo(downstream);
        assertThat((String) chain.captured.get().getAttribute(CorrelationIdGlobalFilter.ATTRIBUTE)).isEqualTo(downstream);
        assertThat((String) chain.captured.get().getAttribute(CorrelationIdGlobalFilter.ORIGINAL_PATH_ATTRIBUTE))
                .isEqualTo("/api/products/1");
    }

    @Test
    void reusesWellFormedIdSentByClient() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/carts/CUST-1")
                .header(CorrelationIdGlobalFilter.HEADER, "client-id-123"));
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();
        exchange.getResponse().setComplete().block();

        assertThat(chain.captured.get().getRequest().getHeaders().get(CorrelationIdGlobalFilter.HEADER))
                .containsExactly("client-id-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo("client-id-123");
    }

    @Test
    void replacesMalformedIdToPreventLogForging() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders")
                .header(CorrelationIdGlobalFilter.HEADER, "abc\nFAKE LOG LINE"));
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        String downstream = chain.captured.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
        assertThat(downstream).isNotEqualTo("abc\nFAKE LOG LINE");
        assertThat(UUID.fromString(downstream)).isNotNull();
    }

    @Test
    void responseHeaderSurvivesDownstreamHeaderCopy() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders")
                .header(CorrelationIdGlobalFilter.HEADER, "abc-1"));
        // Simulates the routing filter copying downstream response headers (possibly a different value)
        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            ex.getResponse().getHeaders().add(CorrelationIdGlobalFilter.HEADER, "something-else");
            return ex.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdGlobalFilter.HEADER)).containsExactly("abc-1");
    }

    @Test
    void runsBeforeAllOtherFilters() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private static final class CapturingChain implements GatewayFilterChain {
        private final AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            captured.set(exchange);
            return Mono.empty();
        }
    }
}

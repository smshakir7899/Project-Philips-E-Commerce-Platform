package com.philips.ecommerce.gateway.controller;

import com.philips.ecommerce.gateway.filter.CorrelationIdGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Runs the real gateway (Netty on a random port) with a static route to an unreachable port, so the full
 * CircuitBreaker -> forward:/fallback path is exercised without any downstream service or Eureka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FallbackControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void unreachableServiceReturns503InStandardErrorShape() {
        webTestClient.get().uri("/api/products/42")
                .header(CorrelationIdGlobalFilter.HEADER, "test-corr-1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(CorrelationIdGlobalFilter.HEADER, "test-corr-1")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.code").isEqualTo("PRODUCT_SERVICE_UNAVAILABLE")
                .jsonPath("$.path").isEqualTo("/api/products/42")
                .jsonPath("$.correlationId").isEqualTo("test-corr-1")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void fallbackAcceptsNonGetMethodsBecauseForwardKeepsTheMethod() {
        webTestClient.post().uri("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Shavers\",\"slug\":\"shavers\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists(CorrelationIdGlobalFilter.HEADER)
                .expectBody()
                .jsonPath("$.code").isEqualTo("PRODUCT_SERVICE_UNAVAILABLE")
                .jsonPath("$.path").isEqualTo("/api/categories");
    }

    @Test
    void fallbackEndpointDerivesCodeFromServiceName() {
        webTestClient.get().uri("/fallback/inventory-service")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVENTORY_SERVICE_UNAVAILABLE")
                .jsonPath("$.path").isEqualTo("/fallback/inventory-service")
                .jsonPath("$.message").isEqualTo("inventory-service is currently unavailable, please retry later");
    }

    @Test
    void unknownPathIsNotRouted() {
        webTestClient.get().uri("/api/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }
}

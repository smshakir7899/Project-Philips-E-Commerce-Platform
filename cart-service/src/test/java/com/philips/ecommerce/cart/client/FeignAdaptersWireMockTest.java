package com.philips.ecommerce.cart.client;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.philips.ecommerce.cart.client.dto.AvailabilityDto;
import com.philips.ecommerce.cart.client.dto.ProductDto;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.web.CorrelationId;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Feign clients (load-balanced by service id) against a WireMock HTTP server, through the Resilience4j
 * aspects and common-lib's RemoteServiceErrorDecoder. Eureka is disabled; the simple discovery client maps the
 * service ids to the WireMock port.
 */
@SpringBootTest(properties = {
        "spring.cloud.discovery.client.simple.instances.product-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.inventory-service[0].uri=http://localhost:${wiremock.server.port}"
})
@AutoConfigureWireMock(port = 0)
class FeignAdaptersWireMockTest {

    private static final String JSON = "application/json";

    @Autowired
    private ProductServiceAdapter productAdapter;
    @Autowired
    private InventoryServiceAdapter inventoryAdapter;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetStubsAndBreakers() {
        WireMock.reset();
        circuitBreakerRegistry.circuitBreaker(ProductServiceAdapter.RESILIENCE_INSTANCE).reset();
        circuitBreakerRegistry.circuitBreaker(InventoryServiceAdapter.RESILIENCE_INSTANCE).reset();
    }

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationId.MDC_KEY);
    }

    @Test
    void productIsDecodedIgnoringUnknownFieldsAndCorrelationIdIsPropagated() {
        stubFor(get(urlEqualTo("/api/products/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", JSON)
                .withBody("{\"id\":1,\"sku\":\"PH-SONIC-9900\",\"name\":\"Sonicare 9900\",\"price\":329.99,"
                        + "\"currency\":\"EUR\",\"active\":true,\"brand\":\"Philips Sonicare\","
                        + "\"images\":[],\"category\":{\"id\":1,\"name\":\"Oral care\"}}")));
        MDC.put(CorrelationId.MDC_KEY, "corr-123");

        ProductDto product = productAdapter.getProduct(1L);

        assertThat(product.getSku()).isEqualTo("PH-SONIC-9900");
        assertThat(product.getPrice()).isEqualByComparingTo("329.99");
        assertThat(product.isActive()).isTrue();
        verify(getRequestedFor(urlEqualTo("/api/products/1")).withHeader(CorrelationId.HEADER, equalTo("corr-123")));
    }

    @Test
    void remote404IsTranslatedNotRetriedAndNotCountedAsBreakerFailure() {
        stubFor(get(urlEqualTo("/api/products/404")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", JSON)
                .withBody("{\"status\":404,\"code\":\"PRODUCT_NOT_FOUND\",\"message\":\"Product 404 not found\"}")));

        assertThatThrownBy(() -> productAdapter.getProduct(404L))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));

        verify(1, getRequestedFor(urlEqualTo("/api/products/404")));
        CircuitBreaker.Metrics metrics = breaker(ProductServiceAdapter.RESILIENCE_INSTANCE).getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
    }

    @Test
    void remote500IsRetriedThenReportedAsServiceUnavailable() {
        stubFor(get(urlEqualTo("/api/products/2")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", JSON)
                .withBody("{\"status\":500,\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}")));

        assertThatThrownBy(() -> productAdapter.getProduct(2L))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));

        verify(3, getRequestedFor(urlEqualTo("/api/products/2")));
        assertThat(breaker(ProductServiceAdapter.RESILIENCE_INSTANCE).getMetrics().getNumberOfFailedCalls())
                .isEqualTo(3);
    }

    @Test
    void readTimeoutIsReportedAsServiceUnavailable() {
        stubFor(get(urlEqualTo("/api/products/3")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", JSON).withFixedDelay(1500)
                .withBody("{\"id\":3,\"sku\":\"S\",\"name\":\"N\",\"price\":1.00,\"currency\":\"EUR\",\"active\":true}")));

        assertThatThrownBy(() -> productAdapter.getProduct(3L))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void openCircuitFailsFastWithoutCallingTheService() {
        breaker(ProductServiceAdapter.RESILIENCE_INSTANCE).transitionToOpenState();

        assertThatThrownBy(() -> productAdapter.getProduct(1L))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));

        verify(0, getRequestedFor(urlEqualTo("/api/products/1")));
    }

    @Test
    void availabilityIsDecodedAndQuantityIsSentAsQueryParameter() {
        stubFor(get(urlPathEqualTo("/api/inventory/5/availability")).withQueryParam("quantity", equalTo("3"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", JSON)
                        .withBody("{\"productId\":5,\"requestedQuantity\":3,\"availableQuantity\":2,"
                                + "\"available\":false}")));

        AvailabilityDto availability = inventoryAdapter.checkAvailability(5L, 3);

        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.getAvailableQuantity()).isEqualTo(2);
    }

    @Test
    void inventoryOutageIsReportedAsInventoryServiceUnavailable() {
        stubFor(get(urlPathEqualTo("/api/inventory/6/availability")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> inventoryAdapter.checkAvailability(6L, 1))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE"));
        verify(3, getRequestedFor(urlPathEqualTo("/api/inventory/6/availability")));
    }

    private CircuitBreaker breaker(String name) {
        return circuitBreakerRegistry.circuitBreaker(name);
    }
}

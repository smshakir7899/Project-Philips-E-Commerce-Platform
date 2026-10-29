package com.philips.ecommerce.cart.client;

import com.philips.ecommerce.cart.client.dto.AvailabilityDto;
import com.philips.ecommerce.cart.client.dto.ProductDto;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.feign.RemoteServiceException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Exception translation in the adapter bodies, without Spring (so without the Resilience4j aspects; those are
 * covered end-to-end by FeignAdaptersWireMockTest).
 */
@ExtendWith(MockitoExtension.class)
class ServiceAdaptersTest {

    @Mock
    private ProductClient productClient;
    @Mock
    private InventoryClient inventoryClient;
    @InjectMocks
    private ProductServiceAdapter productAdapter;
    @InjectMocks
    private InventoryServiceAdapter inventoryAdapter;

    @Test
    void productIsReturnedUnchanged() {
        ProductDto product = new ProductDto(1L, "SKU-1", "Brush", null, "EUR", true);
        when(productClient.getProduct(1L)).thenReturn(product);

        assertThat(productAdapter.getProduct(1L)).isSameAs(product);
    }

    @Test
    void remote404BecomesProductNotFound() {
        when(productClient.getProduct(9L)).thenThrow(remote(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", 404));

        assertThatThrownBy(() -> productAdapter.getProduct(9L))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PRODUCT_NOT_FOUND");
                    assertThat(e.getDetails()).containsEntry("productId", 9L);
                });
    }

    @Test
    void otherRemote4xxIsPassedThroughUnchanged() {
        RemoteServiceException badRequest = remote(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", 400);
        when(productClient.getProduct(1L)).thenThrow(badRequest);

        assertThatThrownBy(() -> productAdapter.getProduct(1L)).isSameAs(badRequest);
    }

    @Test
    void remote5xxBecomesProductServiceUnavailable() {
        when(productClient.getProduct(1L)).thenThrow(remote(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_ERROR", 500));

        assertThatThrownBy(() -> productAdapter.getProduct(1L))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void timeoutBecomesProductServiceUnavailable() {
        when(productClient.getProduct(1L)).thenThrow(retryable());

        assertThatThrownBy(() -> productAdapter.getProduct(1L))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void missingInventoryRecordMeansNotAvailable() {
        when(inventoryClient.checkAvailability(3L, 2))
                .thenThrow(remote(HttpStatus.NOT_FOUND, "INVENTORY_NOT_FOUND", 404));

        AvailabilityDto availability = inventoryAdapter.checkAvailability(3L, 2);

        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.getAvailableQuantity()).isZero();
    }

    @Test
    void inventoryOutageBecomesInventoryServiceUnavailable() {
        when(inventoryClient.checkAvailability(3L, 2))
                .thenThrow(remote(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", 503));

        assertThatThrownBy(() -> inventoryAdapter.checkAvailability(3L, 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE"));
    }

    @Test
    void inventoryTimeoutBecomesInventoryServiceUnavailable() {
        when(inventoryClient.checkAvailability(3L, 2)).thenThrow(retryable());

        assertThatThrownBy(() -> inventoryAdapter.checkAvailability(3L, 2))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE"));
    }

    private static RemoteServiceException remote(HttpStatus status, String code, int remoteStatus) {
        return new RemoteServiceException(status, code, "remote said " + code, remoteStatus, "Client#call()");
    }

    private static RetryableException retryable() {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/api/products/1",
                Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, null, request);
    }
}

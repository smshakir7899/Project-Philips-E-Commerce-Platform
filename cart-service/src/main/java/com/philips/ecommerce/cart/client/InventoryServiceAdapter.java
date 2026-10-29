package com.philips.ecommerce.cart.client;

import com.philips.ecommerce.cart.client.dto.AvailabilityDto;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.feign.RemoteServiceException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resilient wrapper around {@link InventoryClient}; same layering and reasoning as {@link ProductServiceAdapter}.
 *
 * <p>A 404 INVENTORY_NOT_FOUND means "the product exists but nobody ever stocked it". For a shopper that is simply
 * "not available", so it is mapped to an availability of 0 (-> 409 INSUFFICIENT_STOCK in CartService) instead of
 * an error the customer could not act on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryServiceAdapter {

    static final String RESILIENCE_INSTANCE = "inventoryService";
    static final String UNAVAILABLE_CODE = "INVENTORY_SERVICE_UNAVAILABLE";

    private final InventoryClient inventoryClient;

    @Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "circuitOpen")
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public AvailabilityDto checkAvailability(Long productId, int quantity) {
        try {
            return inventoryClient.checkAvailability(productId, quantity);
        } catch (RemoteServiceException e) {
            if (e.isNotFound()) {
                log.info("No inventory record for product {}, treating it as out of stock", productId);
                return new AvailabilityDto(productId, quantity, 0, false);
            }
            if (e.isClientError()) {
                throw e;
            }
            log.warn("inventory-service failed for product {}: HTTP {} {}", productId, e.getRemoteStatus(),
                    e.getCode());
            throw new ServiceUnavailableException(UNAVAILABLE_CODE,
                    "inventory-service is currently unavailable, please retry later", e);
        } catch (RetryableException e) {
            log.warn("inventory-service unreachable for product {}: {}", productId, e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE_CODE,
                    "inventory-service is currently unavailable, please retry later", e);
        }
    }

    @SuppressWarnings("unused")
    private AvailabilityDto circuitOpen(Long productId, int quantity, CallNotPermittedException e) {
        log.warn("Circuit breaker {} is open, not calling inventory-service for product {}", RESILIENCE_INSTANCE,
                productId);
        throw new ServiceUnavailableException(UNAVAILABLE_CODE,
                "inventory-service is currently unavailable (circuit open), please retry later", e);
    }
}

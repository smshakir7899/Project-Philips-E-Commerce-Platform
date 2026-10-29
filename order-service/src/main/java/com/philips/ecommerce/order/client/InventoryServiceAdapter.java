package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.ReservationDto;
import com.philips.ecommerce.order.client.dto.ReservationRequestDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

/**
 * Resilience wrapper around {@link InventoryClient}.
 * <p>
 * Retrying a POST is normally dangerous (a timed-out request may have been executed). It is safe HERE because
 * inventory-service treats the orderNumber as an idempotency key: a second reservation request for the same order
 * returns the existing reservation (200) instead of reserving the stock twice.
 */
@Component
public class InventoryServiceAdapter {

    static final String INSTANCE = "inventory-service";

    private final InventoryClient inventoryClient;

    public InventoryServiceAdapter(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Retry(name = INSTANCE, fallbackMethod = "reserveFallback")
    @CircuitBreaker(name = INSTANCE)
    public ReservationDto reserve(ReservationRequestDto request) {
        return inventoryClient.reserve(request);
    }

    @SuppressWarnings("unused")
    private ReservationDto reserveFallback(ReservationRequestDto request, Throwable failure) {
        throw RemoteFailures.translate(failure, "INVENTORY_SERVICE_UNAVAILABLE", INSTANCE);
    }
}

package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;

/** Factory for the error codes of docs/CONTRACTS.md, so codes and detail keys are spelled in one place. */
final class InventoryErrors {

    private InventoryErrors() {
    }

    static BusinessException inventoryNotFound(Long productId) {
        return new ResourceNotFoundException("INVENTORY_NOT_FOUND", "No inventory record for product " + productId)
                .withDetail("productId", productId);
    }

    static BusinessException reservationNotFound(String orderNumber) {
        return new ResourceNotFoundException("RESERVATION_NOT_FOUND", "No reservation for order " + orderNumber)
                .withDetail("orderNumber", orderNumber);
    }

    static BusinessException insufficientStock(Long productId, int requested, int available) {
        return new ConflictException("INSUFFICIENT_STOCK", "Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available)
                .withDetail("productId", productId)
                .withDetail("requested", requested)
                .withDetail("available", available);
    }

    static BusinessException invalidState(String orderNumber, String action, Object status) {
        return new ConflictException("INVALID_RESERVATION_STATE", "Cannot " + action + " reservation of order "
                + orderNumber + " in status " + status)
                .withDetail("orderNumber", orderNumber)
                .withDetail("status", String.valueOf(status));
    }
}

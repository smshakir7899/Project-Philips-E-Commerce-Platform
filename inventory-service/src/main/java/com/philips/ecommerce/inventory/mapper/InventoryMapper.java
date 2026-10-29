package com.philips.ecommerce.inventory.mapper;

import com.philips.ecommerce.inventory.dto.InventoryResponse;
import com.philips.ecommerce.inventory.dto.ReservationItemResponse;
import com.philips.ecommerce.inventory.dto.ReservationResponse;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.StockReservation;
import com.philips.ecommerce.inventory.entity.StockReservationItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity -> DTO mapping. Entities never leave the service layer: exposing them would leak lazy associations
 * (LazyInitializationException with open-in-view disabled) and couple the API to the table layout.
 */
public final class InventoryMapper {

    private InventoryMapper() {
    }

    public static InventoryResponse toResponse(Inventory inventory) {
        return InventoryResponse.builder()
                .productId(inventory.getProductId())
                .sku(inventory.getSku())
                .availableQuantity(inventory.getAvailableQuantity())
                .reservedQuantity(inventory.getReservedQuantity())
                .lowStockThreshold(inventory.getLowStockThreshold())
                .inStock(inventory.isInStock())
                .updatedAt(inventory.getUpdatedAt())
                .build();
    }

    public static ReservationResponse toResponse(StockReservation reservation) {
        List<ReservationItemResponse> items = new ArrayList<>();
        for (StockReservationItem item : reservation.getItems()) {
            items.add(new ReservationItemResponse(item.getProductId(), item.getQuantity()));
        }
        return ReservationResponse.builder()
                .orderNumber(reservation.getOrderNumber())
                .status(reservation.getStatus())
                .items(items)
                .createdAt(reservation.getCreatedAt())
                .expiresAt(reservation.getExpiresAt())
                .build();
    }
}

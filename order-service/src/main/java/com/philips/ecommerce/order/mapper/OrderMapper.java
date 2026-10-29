package com.philips.ecommerce.order.mapper;

import com.philips.ecommerce.order.dto.AddressDto;
import com.philips.ecommerce.order.dto.OrderItemResponse;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.dto.OrderTrackingResponse;
import com.philips.ecommerce.order.dto.StatusHistoryResponse;
import com.philips.ecommerce.order.entity.Order;
import com.philips.ecommerce.order.entity.OrderItem;
import com.philips.ecommerce.order.entity.OrderStatusHistory;
import com.philips.ecommerce.order.entity.ShippingAddress;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Entity -> DTO mapping (hand-written: small, explicit, no reflection). Must be called while the persistence context
 * is open (inside a transaction) if lazy collections are accessed - open-in-view is disabled on purpose.
 */
@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomerId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .items(order.getItems().stream().map(this::toItemResponse).collect(Collectors.toList()))
                .paymentReference(order.getPaymentReference())
                .failureReason(order.getFailureReason())
                .shippingAddress(toAddressDto(order.getShippingAddress()))
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    public OrderItemResponse toItemResponse(OrderItem item) {
        return OrderItemResponse.builder()
                .productId(item.getProductId())
                .sku(item.getSku())
                .productName(item.getProductName())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .build();
    }

    public OrderTrackingResponse toTracking(Order order) {
        return OrderTrackingResponse.builder()
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomerId())
                .currentStatus(order.getStatus())
                .history(order.getStatusHistory().stream().map(this::toHistory).collect(Collectors.toList()))
                .build();
    }

    public StatusHistoryResponse toHistory(OrderStatusHistory h) {
        return StatusHistoryResponse.builder()
                .fromStatus(h.getFromStatus())
                .toStatus(h.getToStatus())
                .reason(h.getReason())
                .changedAt(h.getChangedAt())
                .build();
    }

    public AddressDto toAddressDto(ShippingAddress address) {
        if (address == null) {
            return null;
        }
        return AddressDto.builder()
                .fullName(address.getFullName())
                .line1(address.getLine1())
                .city(address.getCity())
                .postalCode(address.getPostalCode())
                .country(address.getCountry())
                .build();
    }

    public ShippingAddress toShippingAddress(AddressDto dto) {
        return new ShippingAddress(dto.getFullName(), dto.getLine1(), dto.getCity(), dto.getPostalCode(),
                dto.getCountry());
    }
}

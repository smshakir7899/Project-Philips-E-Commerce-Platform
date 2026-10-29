package com.philips.ecommerce.order.dto;

import com.philips.ecommerce.order.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private String orderNumber;
    private String customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private String currency;
    private List<OrderItemResponse> items;
    private String paymentReference;
    private String failureReason;
    private AddressDto shippingAddress;
    private Instant createdAt;
    private Instant updatedAt;
}

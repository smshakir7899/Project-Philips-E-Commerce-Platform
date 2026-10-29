package com.philips.ecommerce.order.dto;

import com.philips.ecommerce.order.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

/** Body of PATCH /api/orders/{orderNumber}/status (fulfilment updates: only SHIPPED and DELIVERED). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusUpdateRequest {
    @NotNull
    private OrderStatus status;
}

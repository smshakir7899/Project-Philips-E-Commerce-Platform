package com.philips.ecommerce.order.dto;

import com.philips.ecommerce.order.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusHistoryResponse {
    private OrderStatus fromStatus;
    private OrderStatus toStatus;
    private String reason;
    private Instant changedAt;
}

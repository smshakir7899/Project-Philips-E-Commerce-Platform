package com.philips.ecommerce.order.dto;

import com.philips.ecommerce.order.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTrackingResponse {
    private String orderNumber;
    private String customerId;
    private OrderStatus currentStatus;
    private List<StatusHistoryResponse> history;
}

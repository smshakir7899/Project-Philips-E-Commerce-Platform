package com.philips.ecommerce.order.dto;

import com.philips.ecommerce.order.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Row of the order-history page. Built directly by a JPQL constructor expression
 * ({@code select new ...OrderSummaryResponse(...)}) so the page is ONE query: no entity loading, no lazy
 * collection access per row (which would be the N+1 problem).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummaryResponse {
    private String orderNumber;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private String currency;
    /** Number of order lines (computed in SQL with a correlated sub-select). */
    private Integer itemCount;
    private Instant createdAt;
}

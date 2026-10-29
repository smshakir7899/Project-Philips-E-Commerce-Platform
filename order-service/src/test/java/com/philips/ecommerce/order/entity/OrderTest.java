package com.philips.ecommerce.order.entity;

import com.philips.ecommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private Order newOrder() {
        return Order.create("ORD-20260101-0000000A", "CUST-1001", "key-12345678", "EUR",
                new ShippingAddress("Jane Doe", "Main St 1", "Amsterdam", "1011AA", "NL"),
                Arrays.asList(new OrderItem(1L, "SKU-1", "Shaver", new BigDecimal("89.99"), 2),
                        new OrderItem(2L, "SKU-2", "Toothbrush", new BigDecimal("10"), 1)));
    }

    @Test
    void createComputesTotalAndStartsHistory() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("189.98");
        assertThat(order.getItems().get(1).getUnitPrice()).isEqualTo(new BigDecimal("10.00"));
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getFromStatus()).isNull();
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void transitionRecordsHistory() {
        Order order = newOrder();

        order.transitionTo(OrderStatus.INVENTORY_RESERVED, "reserved");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(order.getStatusHistory()).hasSize(2);
        OrderStatusHistory last = order.getStatusHistory().get(1);
        assertThat(last.getFromStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(last.getToStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(last.getReason()).isEqualTo("reserved");
    }

    @Test
    void invalidTransitionThrowsConflict() {
        Order order = newOrder();

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SHIPPED, "too early"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(be.getStatus().value()).isEqualTo(409);
                    assertThat(be.getDetails()).containsEntry("currentStatus", "CREATED");
                });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getStatusHistory()).hasSize(1);
    }
}

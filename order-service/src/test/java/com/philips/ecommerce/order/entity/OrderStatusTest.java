package com.philips.ecommerce.order.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The complete transition table of docs/CONTRACTS.md, checked pair by pair (7 allowed, 42 forbidden). */
class OrderStatusTest {

    /** The allowed transitions, written down independently of the production code. */
    private static final String[] ALLOWED = {
            "CREATED,INVENTORY_RESERVED",
            "CREATED,CANCELLED",
            "INVENTORY_RESERVED,CONFIRMED",
            "INVENTORY_RESERVED,PAYMENT_FAILED",
            "INVENTORY_RESERVED,CANCELLED",
            "CONFIRMED,SHIPPED",
            "CONFIRMED,CANCELLED",
            "SHIPPED,DELIVERED"
    };

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "CREATED, INVENTORY_RESERVED",
            "CREATED, CANCELLED",
            "INVENTORY_RESERVED, CONFIRMED",
            "INVENTORY_RESERVED, PAYMENT_FAILED",
            "INVENTORY_RESERVED, CANCELLED",
            "CONFIRMED, SHIPPED",
            "CONFIRMED, CANCELLED",
            "SHIPPED, DELIVERED"
    })
    void allowedTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is forbidden")
    @MethodSource("forbiddenTransitions")
    void forbiddenTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_FAILED", "CANCELLED", "DELIVERED"})
    void terminalStatesHaveNoExit(OrderStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedTargets()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void nullTargetIsNeverAllowed(OrderStatus status) {
        assertThat(status.canTransitionTo(null)).isFalse();
    }

    static Stream<Arguments> forbiddenTransitions() {
        Set<String> allowed = new HashSet<>();
        for (String pair : ALLOWED) {
            allowed.add(pair);
        }
        List<Arguments> forbidden = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                if (!allowed.contains(from + "," + to)) {
                    forbidden.add(Arguments.of(from, to));
                }
            }
        }
        assertThat(forbidden).hasSize(49 - ALLOWED.length);
        return forbidden.stream();
    }
}

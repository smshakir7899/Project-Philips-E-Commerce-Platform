package com.philips.ecommerce.order.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle (a small state machine). The allowed transitions are the single source of truth for what the
 * saga and the admin/customer endpoints may do:
 * <pre>
 * CREATED            -> INVENTORY_RESERVED | CANCELLED
 * INVENTORY_RESERVED -> CONFIRMED | PAYMENT_FAILED | CANCELLED
 * CONFIRMED          -> SHIPPED | CANCELLED
 * SHIPPED            -> DELIVERED
 * PAYMENT_FAILED, CANCELLED, DELIVERED: terminal
 * </pre>
 */
public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVED,
    CONFIRMED,
    PAYMENT_FAILED,
    CANCELLED,
    SHIPPED,
    DELIVERED;

    /*
     * The map is filled in a static block because an enum constant cannot reference constants declared after it
     * in its own constructor (they are not initialized yet).
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(INVENTORY_RESERVED, CANCELLED));
        ALLOWED.put(INVENTORY_RESERVED, EnumSet.of(CONFIRMED, PAYMENT_FAILED, CANCELLED));
        ALLOWED.put(CONFIRMED, EnumSet.of(SHIPPED, CANCELLED));
        ALLOWED.put(SHIPPED, EnumSet.of(DELIVERED));
        ALLOWED.put(PAYMENT_FAILED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
    }

    public boolean canTransitionTo(OrderStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    public Set<OrderStatus> allowedTargets() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}

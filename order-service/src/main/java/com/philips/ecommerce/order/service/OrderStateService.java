package com.philips.ecommerce.order.service;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderCreatedEvent;
import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.entity.Order;
import com.philips.ecommerce.order.entity.OrderItem;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.mapper.OrderEventMapper;
import com.philips.ecommerce.order.mapper.OrderMapper;
import com.philips.ecommerce.order.outbox.OutboxService;
import com.philips.ecommerce.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The LOCAL transactions of the checkout saga (and of the other order state changes). Every public method is one
 * short {@code @Transactional} unit: change the order AND write the matching outbox event atomically.
 * <p>
 * Why a separate bean instead of private methods in CheckoutService?
 * <ul>
 *   <li>{@code @Transactional} is implemented by a Spring proxy. A call from one method of a bean to another method
 *       of the SAME bean ("self-invocation") bypasses the proxy, so the annotation would silently do nothing.</li>
 *   <li>CheckoutService performs remote HTTP calls between these steps. Keeping those calls outside any transaction
 *       means no DB connection / row lock is held while waiting up to seconds for another service (pool exhaustion
 *       under load), and no transaction pretends to span several services - there is no distributed transaction
 *       (2PC) in a microservice saga; consistency is restored by compensating events instead.</li>
 * </ul>
 * Methods return DTOs mapped inside the transaction because open-in-view is disabled (lazy collections are not
 * reachable after the transaction ends).
 */
@Service
public class OrderStateService {

    private static final Logger log = LoggerFactory.getLogger(OrderStateService.class);

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private final OrderMapper orderMapper;
    private final OrderEventMapper eventMapper;
    private final OrderNumberGenerator orderNumberGenerator;

    public OrderStateService(OrderRepository orderRepository, OutboxService outboxService, OrderMapper orderMapper,
                             OrderEventMapper eventMapper, OrderNumberGenerator orderNumberGenerator) {
        this.orderRepository = orderRepository;
        this.outboxService = outboxService;
        this.orderMapper = orderMapper;
        this.eventMapper = eventMapper;
        this.orderNumberGenerator = orderNumberGenerator;
    }

    /**
     * Saga step "TX1": insert the order (CREATED) with items + first history row + outbox order-created.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the idempotency key (or, astronomically
     *         unlikely, the order number) already exists - the caller turns that into a replay
     */
    @Transactional
    public OrderResponse createOrder(NewOrderCommand command) {
        List<OrderItem> items = command.getLines().stream()
                .map(l -> new OrderItem(l.getProductId(), l.getSku(), l.getProductName(), l.getUnitPrice(),
                        l.getQuantity()))
                .collect(Collectors.toList());
        Order order = Order.create(orderNumberGenerator.next(), command.getCustomerId(), command.getIdempotencyKey(),
                command.getCurrency(), orderMapper.toShippingAddress(command.getShippingAddress()), items);
        // saveAndFlush: surface a unique-constraint violation HERE (as DataIntegrityViolationException), not later.
        orderRepository.saveAndFlush(order);
        outboxService.enqueue(KafkaTopics.ORDER_CREATED, order.getOrderNumber(), OrderCreatedEvent.TYPE,
                eventMapper.toCreated(order));
        log.info("Order {} created for customer {} (total {} {})", order.getOrderNumber(), order.getCustomerId(),
                order.getTotalAmount(), order.getCurrency());
        return orderMapper.toResponse(order);
    }

    /** Saga step "TX2": stock is reserved. No event: nobody outside needs this intermediate state. */
    @Transactional
    public OrderResponse markInventoryReserved(String orderNumber) {
        Order order = load(orderNumber);
        order.transitionTo(OrderStatus.INVENTORY_RESERVED, "Inventory reserved");
        return orderMapper.toResponse(order);
    }

    /** Saga step "TX3" (happy path): payment captured -> CONFIRMED + outbox order-confirmed. */
    @Transactional
    public OrderResponse confirm(String orderNumber, String paymentReference) {
        Order order = load(orderNumber);
        order.transitionTo(OrderStatus.CONFIRMED, "Payment " + paymentReference + " succeeded");
        order.assignPaymentReference(paymentReference);
        outboxService.enqueue(KafkaTopics.ORDER_CONFIRMED, orderNumber, OrderConfirmedEvent.TYPE,
                eventMapper.toConfirmed(order));
        log.info("Order {} confirmed (payment {})", orderNumber, paymentReference);
        return orderMapper.toResponse(order);
    }

    /**
     * Saga step "TX3" (payment declined or outcome unknown): PAYMENT_FAILED + outbox order-cancelled.
     * The cancelled event is the compensation trigger: inventory releases the reservation and payment-service
     * voids/refunds anything that might have been captured (PAYMENT_ERROR = "we do not know").
     */
    @Transactional
    public OrderResponse failPayment(String orderNumber, CancellationReason reason, String detail,
                                     String paymentReference) {
        Order order = load(orderNumber);
        OrderStatus previous = order.getStatus();
        order.transitionTo(OrderStatus.PAYMENT_FAILED, reason + (detail != null ? ": " + detail : ""));
        order.recordFailure(reason + (detail != null ? ": " + detail : ""));
        if (paymentReference != null) {
            order.assignPaymentReference(paymentReference);
        }
        outboxService.enqueue(KafkaTopics.ORDER_CANCELLED, orderNumber, OrderCancelledEvent.TYPE,
                eventMapper.toCancelled(order, previous, reason, detail));
        log.info("Order {} payment failed ({}): {}", orderNumber, reason, detail);
        return orderMapper.toResponse(order);
    }

    /** Cancels an order (saga failure before payment, or customer request) + outbox order-cancelled. */
    @Transactional
    public OrderResponse cancel(String orderNumber, CancellationReason reason, String detail) {
        Order order = load(orderNumber);
        return doCancel(order, reason, detail);
    }

    /**
     * Customer cancellation: only a CONFIRMED (paid, not yet shipped) order. Orders still inside the saga are not
     * cancellable by the customer - the saga itself decides their outcome within seconds.
     */
    @Transactional
    public OrderResponse cancelByCustomer(String orderNumber) {
        Order order = load(orderNumber);
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ConflictException("INVALID_STATUS_TRANSITION",
                    "Only CONFIRMED orders can be cancelled by the customer; order " + orderNumber + " is "
                            + order.getStatus())
                    .withDetail("orderNumber", orderNumber)
                    .withDetail("currentStatus", order.getStatus().name());
        }
        return doCancel(order, CancellationReason.CUSTOMER_REQUESTED, "Cancelled by customer");
    }

    /** Fulfilment updates (warehouse / carrier): only SHIPPED and DELIVERED may be set through the API. */
    @Transactional
    public OrderResponse updateFulfilmentStatus(String orderNumber, OrderStatus target) {
        if (target != OrderStatus.SHIPPED && target != OrderStatus.DELIVERED) {
            throw new BadRequestException("INVALID_STATUS",
                    "Only SHIPPED or DELIVERED can be set through this endpoint")
                    .withDetail("requestedStatus", String.valueOf(target));
        }
        Order order = load(orderNumber);
        order.transitionTo(target, "Fulfilment update");
        log.info("Order {} is now {}", orderNumber, target);
        return orderMapper.toResponse(order);
    }

    private OrderResponse doCancel(Order order, CancellationReason reason, String detail) {
        OrderStatus previous = order.getStatus();
        order.transitionTo(OrderStatus.CANCELLED, reason + (detail != null ? ": " + detail : ""));
        order.recordFailure(reason + (detail != null ? ": " + detail : ""));
        outboxService.enqueue(KafkaTopics.ORDER_CANCELLED, order.getOrderNumber(), OrderCancelledEvent.TYPE,
                eventMapper.toCancelled(order, previous, reason, detail));
        log.info("Order {} cancelled ({}) from {}", order.getOrderNumber(), reason, previous);
        return orderMapper.toResponse(order);
    }

    private Order load(String orderNumber) {
        return orderRepository.findWithItemsByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND",
                        "Order " + orderNumber + " not found"));
    }
}

package com.philips.ecommerce.cart.kafka;

import com.philips.ecommerce.cart.entity.ProcessedEvent;
import com.philips.ecommerce.cart.repository.ProcessedEventRepository;
import com.philips.ecommerce.cart.service.CartService;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Idempotent consumer: "already processed?" check, cart deletion and the processed_event insert run in ONE local
 * transaction (CartService's TransactionTemplate joins it). Kafka's at-least-once delivery may hand us the same
 * event again after a crash or rebalance; without the check a redelivered order-confirmed could wipe a NEW cart the
 * customer filled after checkout. At-least-once delivery + idempotent consumer = effectively-once.
 *
 * <p>Trade-off: the whole cart is cleared (as specified), including items added while the checkout was running.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartEventHandler {

    private final ProcessedEventRepository processedEventRepository;
    private final CartService cartService;

    @Transactional
    public boolean onOrderConfirmed(OrderConfirmedEvent event, String topic) {
        if (processedEventRepository.existsById(event.getEventId())) {
            log.info("Event {} for order {} already processed, skipping duplicate delivery", event.getEventId(),
                    event.getOrderNumber());
            return false;
        }
        boolean cleared = cartService.clearCart(event.getCustomerId());
        log.info("Order {} confirmed: cart of customer {} {}", event.getOrderNumber(), event.getCustomerId(),
                cleared ? "cleared" : "was already empty");
        processedEventRepository.save(new ProcessedEvent(event.getEventId(), OrderConfirmedEvent.TYPE, topic,
                Instant.now()));
        return true;
    }
}

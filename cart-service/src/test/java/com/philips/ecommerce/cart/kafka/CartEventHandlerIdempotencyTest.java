package com.philips.ecommerce.cart.kafka;

import com.philips.ecommerce.cart.client.InventoryServiceAdapter;
import com.philips.ecommerce.cart.client.ProductServiceAdapter;
import com.philips.ecommerce.cart.entity.Cart;
import com.philips.ecommerce.cart.repository.CartRepository;
import com.philips.ecommerce.cart.repository.ProcessedEventRepository;
import com.philips.ecommerce.cart.service.CartService;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotent consumer against a real database (H2): a redelivered order-confirmed must not clear the NEW cart the
 * customer filled after the first delivery was processed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CartEventHandler.class, CartService.class})
class CartEventHandlerIdempotencyTest {

    private static final String CUSTOMER = "CUST-2002";

    @MockBean
    private ProductServiceAdapter productAdapter;
    @MockBean
    private InventoryServiceAdapter inventoryAdapter;
    @Autowired
    private CartEventHandler handler;
    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private TestEntityManager entityManager;

    @Test
    void duplicateOrderConfirmedEventClearsTheCartOnlyOnce() {
        persistCart(1L, 2);
        OrderConfirmedEvent event = OrderConfirmedEvent.builder().eventId("evt-1").orderNumber("ORD-20240501-EEEE0001")
                .customerId(CUSTOMER).build();

        assertThat(handler.onOrderConfirmed(event, KafkaTopics.ORDER_CONFIRMED)).isTrue();
        assertThat(cartRepository.findWithItemsByCustomerId(CUSTOMER)).isEmpty();

        // the customer starts a new cart, then the same event is delivered again
        persistCart(7L, 1);
        assertThat(handler.onOrderConfirmed(event, KafkaTopics.ORDER_CONFIRMED)).isFalse();

        assertThat(cartRepository.findWithItemsByCustomerId(CUSTOMER).get().getItems()).hasSize(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    void confirmationForCustomerWithoutCartIsStillRecorded() {
        OrderConfirmedEvent event = OrderConfirmedEvent.builder().eventId("evt-2").orderNumber("ORD-20240501-EEEE0002")
                .customerId("CUST-NOCART").build();

        assertThat(handler.onOrderConfirmed(event, KafkaTopics.ORDER_CONFIRMED)).isTrue();
        assertThat(processedEventRepository.existsById("evt-2")).isTrue();
    }

    private void persistCart(Long productId, int quantity) {
        Cart cart = new Cart(CUSTOMER, "EUR");
        cart.addItem(productId, "SKU-" + productId, "Product " + productId, new BigDecimal("10.00"), quantity);
        entityManager.persist(cart);
        entityManager.flush();
        entityManager.clear();
    }
}

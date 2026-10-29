package com.philips.ecommerce.order.repository;

import com.philips.ecommerce.order.dto.OrderSummaryResponse;
import com.philips.ecommerce.order.entity.Order;
import com.philips.ecommerce.order.entity.OrderItem;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.entity.ShippingAddress;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository repository;
    @Autowired
    private TestEntityManager em;

    private Order persist(String number, String customer, int itemCount) {
        List<OrderItem> items = new ArrayList<>();
        for (int i = 1; i <= itemCount; i++) {
            items.add(new OrderItem((long) i, "SKU-" + i, "Product " + i, new BigDecimal("10.00"), 1));
        }
        Order order = Order.create(number, customer, "key-" + number, "EUR",
                new ShippingAddress("Jane", "Street 1", "City", "1000", "NL"), items);
        return em.persistAndFlush(order);
    }

    @Test
    void historyIsOneProjectionQueryWithItemCountNewestFirst() {
        persist("ORD-1", "CUST-1", 1);
        persist("ORD-2", "CUST-1", 3);
        persist("ORD-3", "CUST-2", 2);
        persist("ORD-4", "CUST-1", 2);
        em.clear();

        Page<OrderSummaryResponse> page = repository.findSummariesByCustomerId("CUST-1", PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(OrderSummaryResponse::getOrderNumber)
                .containsExactly("ORD-4", "ORD-2");
        assertThat(page.getContent()).extracting(OrderSummaryResponse::getItemCount).containsExactly(2, 3);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(page.getContent().get(0).getTotalAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void fetchJoinLoadsItemsAndHistory() {
        persist("ORD-10", "CUST-1", 2);
        em.clear();

        Optional<Order> withItems = repository.findWithItemsByOrderNumber("ORD-10");
        assertThat(withItems).isPresent();
        em.clear(); // detach: accessing a non-fetched lazy collection would now fail
        assertThat(withItems.get().getItems()).hasSize(2);

        Optional<Order> withHistory = repository.findWithHistoryByOrderNumber("ORD-10");
        em.clear();
        assertThat(withHistory.get().getStatusHistory()).hasSize(1);
        assertThat(repository.findWithItemsByIdempotencyKey("key-ORD-10")).isPresent();
    }
}

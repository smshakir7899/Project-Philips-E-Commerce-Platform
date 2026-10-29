package com.philips.ecommerce.inventory.kafka;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.inventory.config.ClockConfig;
import com.philips.ecommerce.inventory.config.InventoryProperties;
import com.philips.ecommerce.inventory.dto.ReservationItemRequest;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.repository.InventoryRepository;
import com.philips.ecommerce.inventory.repository.ProcessedEventRepository;
import com.philips.ecommerce.inventory.repository.StockReservationRepository;
import com.philips.ecommerce.inventory.service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Idempotent consumer against a real database: the same eventId delivered twice must change stock only once.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderEventHandler.class, ReservationService.class, ClockConfig.class,
        OrderEventHandlerIdempotencyTest.MetricsConfig.class})
class OrderEventHandlerIdempotencyTest {

    private static final String ORDER = "ORD-20240501-BBBB0001";

    /** Slices do not run @ConfigurationPropertiesScan, so the properties bean is enabled explicitly. */
    @TestConfiguration
    @EnableConfigurationProperties(InventoryProperties.class)
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private OrderEventHandler handler;
    @SpyBean
    private ReservationService reservationService;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private StockReservationRepository reservationRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void reserveTwoUnits() {
        inventoryRepository.saveAndFlush(new Inventory(1L, "SKU-1", 10, 2));
        reservationService.reserve(new ReservationRequest(ORDER,
                Collections.singletonList(new ReservationItemRequest(1L, 2))));
    }

    @Test
    void duplicateOrderConfirmedEventIsAppliedOnlyOnce() {
        OrderConfirmedEvent event = confirmed("evt-confirmed-1");

        assertThat(handler.onOrderConfirmed(event, KafkaTopics.ORDER_CONFIRMED)).isTrue();
        assertThat(handler.onOrderConfirmed(event, KafkaTopics.ORDER_CONFIRMED)).isFalse();

        verify(reservationService, times(1)).commit(ORDER);
        Inventory inventory = inventoryRepository.findByProductId(1L).get();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicateOrderCancelledEventRestocksOnlyOnce() {
        handler.onOrderConfirmed(confirmed("evt-confirmed-2"), KafkaTopics.ORDER_CONFIRMED);
        OrderCancelledEvent cancelled = cancelled("evt-cancelled-2", ORDER);

        assertThat(handler.onOrderCancelled(cancelled, KafkaTopics.ORDER_CANCELLED)).isTrue();
        assertThat(handler.onOrderCancelled(cancelled, KafkaTopics.ORDER_CANCELLED)).isFalse();

        verify(reservationService, times(1)).releaseIfPresent(ORDER);
        assertThat(inventoryRepository.findByProductId(1L).get().getAvailableQuantity()).isEqualTo(10);
        assertThat(reservationRepository.findWithItemsByOrderNumber(ORDER).get().getStatus())
                .isEqualTo(ReservationStatus.RETURNED);
    }

    @Test
    void redeliveredConfirmationAfterCancellationIsSkippedInsteadOfFailing() {
        OrderConfirmedEvent confirmed = confirmed("evt-confirmed-3");
        handler.onOrderConfirmed(confirmed, KafkaTopics.ORDER_CONFIRMED);
        handler.onOrderCancelled(cancelled("evt-cancelled-3", ORDER), KafkaTopics.ORDER_CANCELLED);

        // Without the processed_event check this would try to commit a RETURNED reservation (409 -> DLT).
        assertThat(handler.onOrderConfirmed(confirmed, KafkaTopics.ORDER_CONFIRMED)).isFalse();
        assertThat(inventoryRepository.findByProductId(1L).get().getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    void cancellationOfAnOrderThatNeverReservedIsANoOpButStillRecorded() {
        assertThat(handler.onOrderCancelled(cancelled("evt-cancelled-4", "ORD-UNKNOWN"),
                KafkaTopics.ORDER_CANCELLED)).isTrue();

        assertThat(processedEventRepository.existsById("evt-cancelled-4")).isTrue();
        assertThat(inventoryRepository.findByProductId(1L).get().getAvailableQuantity()).isEqualTo(8);
    }

    private static OrderConfirmedEvent confirmed(String eventId) {
        return OrderConfirmedEvent.builder().eventId(eventId).orderNumber(ORDER).customerId("CUST-1001").build();
    }

    private static OrderCancelledEvent cancelled(String eventId, String orderNumber) {
        return OrderCancelledEvent.builder().eventId(eventId).orderNumber(orderNumber).customerId("CUST-1001")
                .reason(CancellationReason.CUSTOMER_REQUESTED).build();
    }
}

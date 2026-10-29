package com.philips.ecommerce.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.order.config.ClockConfig;
import com.philips.ecommerce.order.dto.AddressDto;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.dto.OrderTrackingResponse;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.mapper.OrderEventMapper;
import com.philips.ecommerce.order.mapper.OrderMapper;
import com.philips.ecommerce.order.outbox.OutboxEvent;
import com.philips.ecommerce.order.outbox.OutboxEventRepository;
import com.philips.ecommerce.order.outbox.OutboxService;
import com.philips.ecommerce.order.outbox.OutboxStatus;
import com.philips.ecommerce.order.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs OrderStateService against a real (H2) database to prove that the order change and its outbox row are
 * committed - or rolled back - TOGETHER.
 * <p>
 * {@code NOT_SUPPORTED} switches off the test-managed transaction that @DataJpaTest normally wraps around each test,
 * so the service's own @Transactional boundaries are the ones that commit/roll back (as in production).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OrderStateService.class, OrderQueryService.class, OutboxService.class, OrderMapper.class,
        OrderEventMapper.class, OrderNumberGenerator.class, ClockConfig.class, JacksonAutoConfiguration.class})
class OrderStateServiceTest {

    @Autowired
    private OrderStateService stateService;
    @Autowired
    private OrderQueryService queryService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private NewOrderCommand command(String key) {
        return new NewOrderCommand("CUST-1001", key, "EUR",
                new AddressDto("Jane Doe", "Main St 1", "Amsterdam", "1011AA", "NL"),
                Arrays.asList(new OrderLine(1L, "SKU-1", "Shaver", new BigDecimal("89.99"), 2),
                        new OrderLine(2L, "SKU-2", "Brush", new BigDecimal("5.50"), 1)));
    }

    private List<OutboxEvent> outboxRows() {
        return outboxRepository.findAll().stream()
                .sorted(Comparator.comparing(OutboxEvent::getId))
                .collect(Collectors.toList());
    }

    @Test
    void createOrderStoresOrderAndOrderCreatedEventInOneTransaction() throws Exception {
        OrderResponse order = stateService.createOrder(command("key-create-1"));

        assertThat(order.getOrderNumber()).matches("ORD-\\d{8}-[0-9A-F]{8}");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("185.48");
        assertThat(order.getItems()).hasSize(2);

        List<OutboxEvent> rows = outboxRows();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo(KafkaTopics.ORDER_CREATED);
        assertThat(row.getEventType()).isEqualTo("OrderCreatedEvent");
        assertThat(row.getMessageKey()).isEqualTo(order.getOrderNumber());
        assertThat(row.getAggregateType()).isEqualTo("Order");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.path("eventId").asText()).isEqualTo(row.getEventId());
        assertThat(payload.path("orderNumber").asText()).isEqualTo(order.getOrderNumber());
        assertThat(payload.path("items")).hasSize(2);
    }

    @Test
    void orderAndOutboxRowAreRolledBackTogether() {
        // The service joins the caller's transaction; a failure before commit (e.g. the JVM dies) must lose BOTH
        // the order and its event - never one without the other.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            stateService.createOrder(command("key-rollback-1"));
            assertThat(outboxRepository.count()).isEqualTo(1); // visible inside the transaction
            throw new IllegalStateException("simulated crash before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void enqueueOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outboxService.enqueue("t", "k", "E", new Object()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void sagaHappyPathEndsConfirmedWithOrderConfirmedEvent() throws Exception {
        String orderNumber = stateService.createOrder(command("key-happy-1")).getOrderNumber();

        stateService.markInventoryReserved(orderNumber);
        OrderResponse confirmed = stateService.confirm(orderNumber, "PAY-0000000000AA");

        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPaymentReference()).isEqualTo("PAY-0000000000AA");
        List<OutboxEvent> rows = outboxRows();
        assertThat(rows).extracting(OutboxEvent::getTopic)
                .containsExactly(KafkaTopics.ORDER_CREATED, KafkaTopics.ORDER_CONFIRMED);
        assertThat(objectMapper.readTree(rows.get(1).getPayload()).path("paymentReference").asText())
                .isEqualTo("PAY-0000000000AA");

        OrderTrackingResponse tracking = queryService.getTracking(orderNumber);
        assertThat(tracking.getHistory()).extracting("toStatus")
                .containsExactly(OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void invalidTransitionWritesNoEvent() {
        String orderNumber = stateService.createOrder(command("key-invalid-1")).getOrderNumber();

        // CREATED -> CONFIRMED skips the reservation: refused, and the transaction is rolled back.
        assertThatThrownBy(() -> stateService.confirm(orderNumber, "PAY-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS_TRANSITION");

        assertThat(outboxRows()).hasSize(1);
        assertThat(queryService.getOrder(orderNumber).getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void failPaymentPublishesOrderCancelledWithReason() throws Exception {
        String orderNumber = stateService.createOrder(command("key-declined-1")).getOrderNumber();
        stateService.markInventoryReserved(orderNumber);

        OrderResponse failed = stateService.failPayment(orderNumber, CancellationReason.PAYMENT_DECLINED,
                "CARD_DECLINED", "PAY-0000000000BB");

        assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(failed.getFailureReason()).contains("PAYMENT_DECLINED");
        OutboxEvent cancelled = outboxRows().get(1);
        assertThat(cancelled.getTopic()).isEqualTo(KafkaTopics.ORDER_CANCELLED);
        JsonNode payload = objectMapper.readTree(cancelled.getPayload());
        assertThat(payload.path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(payload.path("previousStatus").asText()).isEqualTo("INVENTORY_RESERVED");
    }

    @Test
    void customerCancelOnlyForConfirmedOrders() throws Exception {
        String orderNumber = stateService.createOrder(command("key-cancel-1")).getOrderNumber();

        assertThatThrownBy(() -> stateService.cancelByCustomer(orderNumber))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS_TRANSITION");

        stateService.markInventoryReserved(orderNumber);
        stateService.confirm(orderNumber, "PAY-0000000000CC");
        OrderResponse cancelled = stateService.cancelByCustomer(orderNumber);

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        OutboxEvent last = outboxRows().get(2);
        assertThat(last.getTopic()).isEqualTo(KafkaTopics.ORDER_CANCELLED);
        assertThat(objectMapper.readTree(last.getPayload()).path("reason").asText()).isEqualTo("CUSTOMER_REQUESTED");
    }

    @Test
    void fulfilmentEndpointAcceptsOnlyShippedAndDelivered() {
        String orderNumber = stateService.createOrder(command("key-ship-1")).getOrderNumber();
        stateService.markInventoryReserved(orderNumber);
        stateService.confirm(orderNumber, "PAY-0000000000DD");

        assertThatThrownBy(() -> stateService.updateFulfilmentStatus(orderNumber, OrderStatus.CANCELLED))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS");
        assertThatThrownBy(() -> stateService.updateFulfilmentStatus(orderNumber, OrderStatus.DELIVERED))
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS_TRANSITION");

        assertThat(stateService.updateFulfilmentStatus(orderNumber, OrderStatus.SHIPPED).getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(stateService.updateFulfilmentStatus(orderNumber, OrderStatus.DELIVERED).getStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void duplicateIdempotencyKeyViolatesUniqueConstraint() {
        stateService.createOrder(command("key-dup-1"));

        assertThatThrownBy(() -> stateService.createOrder(command("key-dup-1")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(queryService.findByIdempotencyKey("key-dup-1")).isPresent();
    }

    @Test
    void unknownOrderIs404() {
        assertThatThrownBy(() -> stateService.markInventoryReserved("ORD-NOPE"))
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_FOUND");
    }
}

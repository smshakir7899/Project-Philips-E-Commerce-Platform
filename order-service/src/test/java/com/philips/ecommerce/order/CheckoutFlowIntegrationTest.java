package com.philips.ecommerce.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.outbox.OutboxEventRepository;
import com.philips.ecommerce.order.outbox.OutboxStatus;
import com.philips.ecommerce.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checkout through the real Spring context: MockMvc -> CheckoutService -> Feign (Spring Cloud
 * LoadBalancer + simple discovery pointing at WireMock) -> H2 -> outbox -> OutboxPublisher -> embedded Kafka.
 * No external infrastructure is needed.
 * <p>
 * The outbox publisher is enabled with fetch-mode SIMPLE because H2 does not support MySQL's
 * {@code FOR UPDATE SKIP LOCKED}. Topics are created at startup by the NewTopic beans (KafkaAdmin).
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.outbox.enabled=true",
        "app.outbox.fetch-mode=SIMPLE",
        "app.outbox.poll-interval-ms=200",
        "spring.cloud.discovery.client.simple.instances.cart-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.product-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.inventory-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.payment-service[0].uri=http://localhost:${wiremock.server.port}"
})
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
@EmbeddedKafka(partitions = 1)
@DirtiesContext
class CheckoutFlowIntegrationTest {

    private static final String ADDRESS = "\"shippingAddress\":{\"fullName\":\"Jane Doe\",\"line1\":\"Main St 1\","
            + "\"city\":\"Amsterdam\",\"postalCode\":\"1011AA\",\"country\":\"NL\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private WireMockServer wireMock;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> consumer;
    /** Everything polled so far in the current test (a record polled while waiting for another one is kept). */
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        received.clear();
        Map<String, Object> props = KafkaTestUtils.consumerProps("it-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(Arrays.asList(KafkaTopics.ORDER_CREATED, KafkaTopics.ORDER_CONFIRMED,
                KafkaTopics.ORDER_CANCELLED));

        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo("/api/products/batch")).willReturn(json(200,
                "[{\"id\":1,\"sku\":\"PH-SHAVER-1\",\"name\":\"Shaver S9000\",\"price\":89.99,\"currency\":\"EUR\","
                        + "\"active\":true,\"brand\":\"Philips\"},"
                        + "{\"id\":2,\"sku\":\"PH-BRUSH-2\",\"name\":\"Sonicare\",\"price\":20.00,\"currency\":\"EUR\","
                        + "\"active\":true}]")));
        wireMock.stubFor(post(urlEqualTo("/api/inventory/reservations")).willReturn(json(201,
                "{\"orderNumber\":\"any\",\"status\":\"RESERVED\",\"items\":[]}")));
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
                .withRequestBody(matchingJsonPath("$.cardToken", equalTo("tok_visa")))
                .willReturn(json(201, "{\"paymentReference\":\"PAY-00000000VISA\",\"status\":\"SUCCEEDED\"}")));
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
                .withRequestBody(matchingJsonPath("$.cardToken", equalTo("tok_declined")))
                .willReturn(json(201, "{\"paymentReference\":\"PAY-0000000DECLN\",\"status\":\"FAILED\","
                        + "\"failureReason\":\"CARD_DECLINED\"}")));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private void stubCart(String customerId) {
        // Cart price snapshot of product 1 (99.00) is outdated: the order must use product-service's 89.99.
        wireMock.stubFor(get(urlEqualTo("/api/carts/" + customerId)).willReturn(json(200,
                "{\"customerId\":\"" + customerId + "\",\"currency\":\"EUR\",\"items\":["
                        + "{\"productId\":1,\"sku\":\"PH-SHAVER-1\",\"productName\":\"Shaver S9000\","
                        + "\"unitPrice\":99.00,\"quantity\":2,\"lineTotal\":198.00},"
                        + "{\"productId\":2,\"sku\":\"PH-BRUSH-2\",\"productName\":\"Sonicare\","
                        + "\"unitPrice\":20.00,\"quantity\":1,\"lineTotal\":20.00}]}")));
    }

    private MvcResult checkout(String customerId, String cardToken, String key, int expectedStatus)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/orders/checkout")
                        .header("Idempotency-Key", key)
                        .header("X-Correlation-Id", "corr-" + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"cardToken\":\"" + cardToken + "\","
                                + ADDRESS + "}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    /** Polls the embedded broker until a record with this key arrives on the topic. */
    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                received.add(r);
            }
            return received.stream().anyMatch(r -> r.topic().equals(topic) && key.equals(r.key()));
        });
        return received.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).findFirst().get();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void successfulCheckoutConfirmsOrderAndPublishesOrderConfirmed() throws Exception {
        stubCart("CUST-1001");

        MvcResult result = checkout("CUST-1001", "tok_visa", "it-success-0001", 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String orderNumber = body.path("orderNumber").asText();
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("paymentReference").asText()).isEqualTo("PAY-00000000VISA");
        // 2 x 89.99 (product-service price, not the cart's 99.00) + 1 x 20.00
        assertThat(body.path("totalAmount").decimalValue()).isEqualByComparingTo("199.98");
        assertThat(orderRepository.findByOrderNumber(orderNumber).get().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);

        // Payment request used the deterministic idempotency key.
        wireMock.verify(postRequestedFor(urlEqualTo("/api/payments"))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", equalTo("PAY-" + orderNumber)))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("199.98"))));

        ConsumerRecord<String, String> confirmed = awaitRecord(KafkaTopics.ORDER_CONFIRMED, orderNumber);
        JsonNode event = objectMapper.readTree(confirmed.value());
        assertThat(event.path("paymentReference").asText()).isEqualTo("PAY-00000000VISA");
        assertThat(event.path("items")).hasSize(2);
        assertThat(header(confirmed, "eventType")).isEqualTo("OrderConfirmedEvent");
        assertThat(header(confirmed, "X-Correlation-Id")).isEqualTo("corr-it-success-0001");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isZero());

        // Same Idempotency-Key again: stored order is replayed, no second payment.
        mvc.perform(MockMvcRequestBuilders.post("/api/orders/checkout")
                        .header("Idempotency-Key", "it-success-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"CUST-1001\",\"cardToken\":\"tok_visa\"," + ADDRESS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(orderNumber));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void declinedPaymentFailsOrderAndPublishesOrderCancelled() throws Exception {
        stubCart("CUST-2002");

        MvcResult result = checkout("CUST-2002", "tok_declined", "it-declined-0001", 402);

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.path("code").asText()).isEqualTo("PAYMENT_DECLINED");
        String orderNumber = error.path("details").path("orderNumber").asText();
        assertThat(orderNumber).startsWith("ORD-");
        assertThat(orderRepository.findByOrderNumber(orderNumber).get().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_FAILED);

        ConsumerRecord<String, String> cancelled = awaitRecord(KafkaTopics.ORDER_CANCELLED, orderNumber);
        JsonNode event = objectMapper.readTree(cancelled.value());
        assertThat(event.path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(event.path("previousStatus").asText()).isEqualTo("INVENTORY_RESERVED");
        assertThat(header(cancelled, "eventType")).isEqualTo("OrderCancelledEvent");

        // order-created was published first for the same key (same partition => ordered).
        ConsumerRecord<String, String> created = awaitRecord(KafkaTopics.ORDER_CREATED, orderNumber);
        assertThat(created.value()).contains(orderNumber);
    }

    @Test
    void insufficientStockCancelsOrderWith409() throws Exception {
        stubCart("CUST-3003");
        wireMock.stubFor(post(urlEqualTo("/api/inventory/reservations")).willReturn(json(409,
                "{\"status\":409,\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"Not enough stock for product 1\","
                        + "\"details\":{\"productId\":1,\"requested\":2,\"available\":0}}")));

        MvcResult result = checkout("CUST-3003", "tok_visa", "it-stock-0001", 409);

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(error.path("details").path("available").asInt()).isZero();
        String orderNumber = error.path("details").path("orderNumber").asText();
        assertThat(orderRepository.findByOrderNumber(orderNumber).get().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        // 4xx is a business answer: not retried
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/inventory/reservations")));
    }

    @Test
    void inventoryTimeoutIsRetriedThenCancelsWith503() throws Exception {
        stubCart("CUST-4004");
        // longer than the test read-timeout (2 s) -> feign.RetryableException
        wireMock.stubFor(post(urlEqualTo("/api/inventory/reservations")).willReturn(json(201, "{}")
                .withFixedDelay(2500)));

        MvcResult result = checkout("CUST-4004", "tok_visa", "it-timeout-0001", 503);

        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.path("code").asText()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE");
        String orderNumber = error.path("details").path("orderNumber").asText();
        assertThat(orderRepository.findByOrderNumber(orderNumber).get().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        // retry.max-attempts = 2 in the test configuration
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/inventory/reservations")));

        ConsumerRecord<String, String> cancelled = awaitRecord(KafkaTopics.ORDER_CANCELLED, orderNumber);
        assertThat(objectMapper.readTree(cancelled.value()).path("reason").asText()).isEqualTo("INVENTORY_ERROR");
    }
}

package com.philips.ecommerce.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.outbox.OutboxEventRepository;
import com.philips.ecommerce.order.outbox.OutboxStatus;
import com.philips.ecommerce.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout saga against REAL infrastructure (Testcontainers), complementing the in-process
 * {@link CheckoutFlowIntegrationTest} (H2 + embedded Kafka):
 * <ul>
 *   <li>MySQL 8.0: Flyway migrations + Hibernate {@code ddl-auto=validate} + the native
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED} outbox query (which H2 cannot run).</li>
 *   <li>Kafka from the same Confluent 7.4.4 image as docker-compose, in ZooKeeper mode.</li>
 *   <li>Downstream services (cart, product, inventory, payment) are WireMock stubs, discovered through
 *       Spring Cloud's simple discovery client instead of Eureka.</li>
 * </ul>
 * Run with {@code ./mvnw -pl order-service -Pintegration-tests verify} (needs Docker).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.outbox.enabled=true",
        "app.outbox.fetch-mode=SKIP_LOCKED",
        "app.outbox.poll-interval-ms=200",
        "spring.cloud.discovery.client.simple.instances.cart-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.product-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.inventory-service[0].uri=http://localhost:${wiremock.server.port}",
        "spring.cloud.discovery.client.simple.instances.payment-service[0].uri=http://localhost:${wiremock.server.port}"
})
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
// Close the Spring context (outbox poller, Kafka listeners) BEFORE Testcontainers stops MySQL/Kafka:
// JUnit runs afterAll callbacks in reverse registration order (@Testcontainers is registered first).
@DirtiesContext
class CheckoutWorkflowMySqlKafkaIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("philips_order")
            .withUsername("philips")
            .withPassword("philips_pwd");

    /**
     * The classic KafkaContainer runs ZooKeeper inside the container for Confluent images (unless withKraft() is
     * called), which matches the ZooKeeper-based broker of docker-compose.yml. It is deprecated in favour of the
     * KRaft-only ConfluentKafkaContainer, which is exactly why it is used here.
     */
    @SuppressWarnings("deprecation")
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.4"))
            .withEmbeddedZookeeper();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final String ADDRESS = "\"shippingAddress\":{\"fullName\":\"Ada Lovelace\","
            + "\"line1\":\"High Tech Campus 52\",\"city\":\"Eindhoven\",\"postalCode\":\"5656AE\",\"country\":\"NL\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private WireMockServer wireMock;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(Arrays.asList(KafkaTopics.ORDER_CREATED, KafkaTopics.ORDER_CONFIRMED,
                KafkaTopics.ORDER_CANCELLED));
        received.clear();

        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo("/api/products/batch")).willReturn(json(200,
                "[{\"id\":1,\"sku\":\"PH-SONIC-9900\",\"name\":\"Sonicare 9900\",\"price\":329.99,"
                        + "\"currency\":\"EUR\",\"active\":true}]")));
        wireMock.stubFor(post(urlEqualTo("/api/inventory/reservations")).willReturn(json(201,
                "{\"orderNumber\":\"any\",\"status\":\"RESERVED\",\"items\":[]}")));
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
                .withRequestBody(matchingJsonPath("$.cardToken", equalTo("tok_visa")))
                .willReturn(json(201, "{\"paymentReference\":\"PAY-0000000000IT\",\"status\":\"SUCCEEDED\"}")));
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
                .withRequestBody(matchingJsonPath("$.cardToken", equalTo("tok_declined")))
                .willReturn(json(201, "{\"paymentReference\":\"PAY-00000000DECL\",\"status\":\"FAILED\","
                        + "\"failureReason\":\"CARD_DECLINED\"}")));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void flywayCreatedTheSchemaOnMySql() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
        // The unique index that makes checkout idempotent exists on the real table.
        Integer uniqueIdx = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'idempotency_key' "
                + "AND non_unique = 0", Integer.class);
        assertThat(uniqueIdx).isEqualTo(1);
    }

    @Test
    void successfulCheckoutIsStoredInMySqlAndPublishedInOrderToRealKafka() throws Exception {
        stubCart("CUST-IT-1");
        String key = "it-mysql-kafka-" + UUID.randomUUID();

        MvcResult result = checkout("CUST-IT-1", "tok_visa", key, 201);
        String orderNumber = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("orderNumber").asText();
        assertThat(orderRepository.findByOrderNumber(orderNumber).get().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);

        // Outbox rows were picked up with FOR UPDATE SKIP LOCKED and marked SENT.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isZero());

        ConsumerRecord<String, String> created = awaitRecord(KafkaTopics.ORDER_CREATED, orderNumber);
        ConsumerRecord<String, String> confirmed = awaitRecord(KafkaTopics.ORDER_CONFIRMED, orderNumber);
        JsonNode event = objectMapper.readTree(confirmed.value());
        assertThat(event.path("paymentReference").asText()).isEqualTo("PAY-0000000000IT");
        assertThat(event.path("totalAmount").decimalValue()).isEqualByComparingTo("659.98");
        // Both topics have 3 partitions and the same key (orderNumber) -> same default-partitioner result.
        assertThat(created.partition()).isEqualTo(confirmed.partition());

        // Replay with the same Idempotency-Key: 200, same order, still exactly one payment call.
        mvc.perform(MockMvcRequestBuilders.post("/api/orders/checkout")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CUST-IT-1", "tok_visa")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void declinedPaymentIsCompensatedThroughOrderCancelledOnRealKafka() throws Exception {
        stubCart("CUST-IT-2");

        MvcResult result = checkout("CUST-IT-2", "tok_declined", "it-declined-" + UUID.randomUUID(), 402);
        String orderNumber = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("details").path("orderNumber").asText();
        assertThat(orderRepository.findByOrderNumber(orderNumber).get().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_FAILED);

        JsonNode cancelled = objectMapper.readTree(awaitRecord(KafkaTopics.ORDER_CANCELLED, orderNumber).value());
        assertThat(cancelled.path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(cancelled.path("previousStatus").asText()).isEqualTo("INVENTORY_RESERVED");
    }

    private void stubCart(String customerId) {
        wireMock.stubFor(get(urlEqualTo("/api/carts/" + customerId)).willReturn(json(200,
                "{\"customerId\":\"" + customerId + "\",\"currency\":\"EUR\",\"items\":[{\"productId\":1,"
                        + "\"sku\":\"PH-SONIC-9900\",\"productName\":\"Sonicare 9900\",\"unitPrice\":329.99,"
                        + "\"quantity\":2,\"lineTotal\":659.98}]}")));
    }

    private MvcResult checkout(String customerId, String cardToken, String key, int expectedStatus)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/orders/checkout")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(customerId, cardToken)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private static String body(String customerId, String cardToken) {
        return "{\"customerId\":\"" + customerId + "\",\"cardToken\":\"" + cardToken + "\"," + ADDRESS + "}";
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                received.add(r);
            }
            return received.stream().anyMatch(r -> r.topic().equals(topic) && key.equals(r.key()));
        });
        return received.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).findFirst().get();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}

# Service Contracts (single source of truth)

Every port, URL, database, topic, DTO field and environment variable in this repository must match this file.
If code and this file disagree, the code is wrong.

## 1. Modules, packages, ports

| Module | Base package | Port | Eureka / app name | Database |
|---|---|---|---|---|
| config-server | `com.philips.ecommerce.config` | 8888 | config-server (not registered) | - |
| discovery-server | `com.philips.ecommerce.discovery` | 8761 | discovery-server | - |
| api-gateway | `com.philips.ecommerce.gateway` | 8080 | api-gateway | - |
| product-service | `com.philips.ecommerce.product` | 8081 | product-service | `philips_product` |
| inventory-service | `com.philips.ecommerce.inventory` | 8082 | inventory-service | `philips_inventory` |
| cart-service | `com.philips.ecommerce.cart` | 8083 | cart-service | `philips_cart` |
| order-service | `com.philips.ecommerce.order` | 8084 | order-service | `philips_order` |
| payment-service | `com.philips.ecommerce.payment` | 8085 | payment-service | `philips_payment` |
| notification-service | `com.philips.ecommerce.notification` | 8086 | notification-service | `philips_notification` |
| common-lib | `com.philips.ecommerce.common` | - | (library) | - |

Infrastructure (docker compose service name : host port):
`mysql:3306`, `redis:6379`, `zookeeper:2181`, `kafka:9092` (host listener; containers use `kafka:29092`),
`minio:9000` (S3 API) / `9001` (console), `kafka-ui:8090`, `sonarqube:9002` (profile `ci`), `jenkins:8070` (profile `ci`).

Sub-package convention inside every service: `controller`, `service`, `repository`, `entity`, `dto`, `mapper`,
`client` (+ `client.dto`), `config`, `kafka`, `outbox`, `exception`. Lombok is allowed. Java 8 only
(no `var`, no `List.of`, no `Optional.isEmpty`, no `String.isBlank`, no records, no text blocks).

## 2. Environment variables (defaults are the local-dev values)

| Variable | Default | Used by |
|---|---|---|
| `CONFIG_SERVER_URL` | `http://localhost:8888` | all business services and api-gateway (discovery-server is standalone) |
| `CONFIG_REPO_LOCATION` | `file:./config-repo/,file:../config-repo/` | config-server |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | all except config-server |
| `MYSQL_HOST` / `MYSQL_PORT` | `localhost` / `3306` | services with a database |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `philips` / `philips_pwd` | services with a database |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | product-service |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | order, payment, inventory, cart, notification |
| `AWS_REGION` | `eu-west-1` | product-service |
| `S3_BUCKET` | `philips-product-images` | product-service, minio-init |
| `S3_ENDPOINT` | `http://localhost:9000` (MinIO). Empty = real AWS S3 | product-service |
| `S3_PATH_STYLE_ACCESS` | `true` (MinIO). `false` for AWS | product-service |
| `S3_PRESIGN_ENDPOINT` | empty locally on the host; `http://localhost:9000` in Docker; empty on AWS | product-service (host used to sign presigned URLs) |
| `S3_PUBLIC_BASE_URL` | `http://localhost:9000/philips-product-images` | product-service |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `minioadmin` / `minioadmin` locally; **unset on EC2** (instance role) | product-service (AWS SDK default chain) |
| `PRODUCT_QUERY_MODE` | `optimized` (`baseline` for the perf experiment) | product-service |
| `PRODUCT_CACHE_ENABLED` | `true` | product-service |
| `JAVA_OPTS` | `-Xms128m -Xmx256m` | Docker images |

JDBC URL pattern: `jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/<database>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`

## 3. Configuration layout

* `config-repo/application.yml` - shared by all clients (Eureka, Kafka client defaults, JPA defaults, actuator, logging).
* `config-repo/<spring.application.name>.yml` - service-specific (datasource URL, business properties, Feign/Resilience4j).
* `config-repo/product-service-baseline.yml` - profile used for the performance baseline.
* Every module's jar is built as `target/<artifactId>.jar` (`<finalName>${project.artifactId}</finalName>`); Dockerfiles copy it.
* discovery-server and config-server are self-contained (no config import). Startup order:
  infrastructure -> config-server -> discovery-server -> business services -> api-gateway.
* Each business service / gateway `src/main/resources/application.yml` contains ONLY: `server.port`, `spring.application.name`,
  `spring.config.import: "configserver:${CONFIG_SERVER_URL:http://localhost:8888}"`, `spring.cloud.config.fail-fast: true`,
  and `spring.cloud.config.retry.*` (needs `spring-retry` + `spring-boot-starter-aop`).
* Each service `src/test/resources/application.yml` is self-contained for tests (H2 in MySQL mode,
  `spring.cloud.config.enabled: false`, `eureka.client.enabled: false`, `spring.flyway.enabled: false`,
  `spring.jpa.hibernate.ddl-auto: create-drop`). It shadows the main `application.yml` on the test classpath.
* Schema is owned by **Flyway** (`src/main/resources/db/migration/V1__...sql`); Hibernate runs with `ddl-auto: validate`.
* Custom properties live under the `app.` prefix and are bound with `@ConfigurationProperties`.

## 4. Error model (all services)

Body = `com.philips.ecommerce.common.dto.ApiErrorResponse`:
`{timestamp, status, error, code, message, path, correlationId, fieldErrors[{field,message,rejectedValue}], details{}}`.
Throw `BusinessException` subclasses from common-lib (`ResourceNotFoundException` 404, `ConflictException` 409,
`BadRequestException` 400, `ServiceUnavailableException` 503) or a service-specific subclass of `BusinessException`.
`GlobalExceptionHandler`, `CorrelationIdFilter`, `CorrelationIdFeignInterceptor` and `RemoteServiceErrorDecoder`
are auto-configured by common-lib (do not redeclare them).
Feign errors arrive as `com.philips.ecommerce.common.feign.RemoteServiceException` (keeps remote status, `code`, `details`).
Feign timeouts / connection errors arrive as `feign.RetryableException`; an open circuit breaker as
`io.github.resilience4j.circuitbreaker.CallNotPermittedException`. Services convert those to
`ServiceUnavailableException` with codes `<SERVICE>_SERVICE_UNAVAILABLE`, e.g. `PRODUCT_SERVICE_UNAVAILABLE`.

Header `X-Correlation-Id` is propagated gateway -> services -> Feign -> Kafka header.

Paging: `com.philips.ecommerce.common.dto.PageResponse<T>` = `{content[], page, size, totalElements, totalPages, last}`.
Money: `BigDecimal` with scale 2, currency ISO code, default `EUR`. Timestamps: ISO-8601 `Instant` (UTC).

## 5. REST APIs

All paths below are identical when called directly on the service port or through the gateway on 8080.

### product-service (8081)
DTOs:
* `CategoryResponse {id, name, slug, description}`; `CategoryRequest {name @NotBlank max100, slug @NotBlank @Pattern [a-z0-9-]+ max100, description max500}`
* `ProductSummaryResponse {id, sku, name, brand, price, currency, categoryId, categoryName, active, thumbnailUrl}`
* `ProductDetailResponse {id, sku, name, description, brand, price, currency, active, category: CategoryResponse, images: [ProductImageResponse {id, url, contentType, sortOrder}], attributes: [ProductAttributeResponse {name, value}], createdAt, updatedAt}`
* `ProductRequest {sku @NotBlank max64, name @NotBlank max200, description max4000, brand @NotBlank max100, price @NotNull @DecimalMin(0.01) @Digits(10,2), currency @Pattern [A-Z]{3} (default EUR), categoryId @NotNull, attributes: [ProductAttributeRequest {name @NotBlank, value @NotBlank}]}`

| Method & path | Result |
|---|---|
| `GET /api/products?q=&categoryId=&minPrice=&maxPrice=&page=0&size=20&sort=name,asc` | 200 `PageResponse<ProductSummaryResponse>` (only active; size max 100; sort fields: name, price, createdAt) |
| `GET /api/products/{id}` | 200 `ProductDetailResponse`; 404 `PRODUCT_NOT_FOUND` |
| `GET /api/products/sku/{sku}` | 200 `ProductDetailResponse`; 404 `PRODUCT_NOT_FOUND` |
| `GET /api/products/batch?ids=1,2,3` | 200 `List<ProductSummaryResponse>` (includes inactive; missing ids simply absent; max 100 ids) |
| `POST /api/products` | 201 `ProductDetailResponse` + Location; 409 `DUPLICATE_SKU`; 404 `CATEGORY_NOT_FOUND` |
| `PUT /api/products/{id}` | 200 `ProductDetailResponse` (evicts cache) |
| `DELETE /api/products/{id}` | 204 (soft delete: active=false, evicts cache) |
| `POST /api/products/{id}/images` (multipart field `file`, jpeg/png/webp, max 5MB) | 201 `ProductImageResponse` (stored in S3/MinIO) |
| `DELETE /api/products/{id}/images/{imageId}` | 204 |
| `GET /api/products/{id}/images/{imageId}/presigned-url` | 200 `{url, expiresAt}` |
| `GET /api/categories` / `GET /api/categories/{id}` / `POST /api/categories` | 200 list / 200 / 201 |
| `GET /api/products/admin/cache/stats` | 200 `{enabled, hits, misses, errors, hitRatio, ttlSeconds}` |
| `DELETE /api/products/admin/cache` | 204 (flush product keys; used for cold-cache benchmarks) |
| `GET /api/products/admin/perf/stats` | 200 `{queryMode, cacheEnabled, hibernate{...counters}, cache{...}}` |
| `POST /api/products/admin/perf/reset` | 204 (reset Hibernate statistics and cache counters) |

### inventory-service (8082)
* `InventoryResponse {productId, sku, availableQuantity, reservedQuantity, lowStockThreshold, inStock, updatedAt}`
* `AvailabilityResponse {productId, requestedQuantity, availableQuantity, available}`
* `StockUpdateRequest {sku @NotBlank, availableQuantity @NotNull @Min(0), lowStockThreshold @NotNull @Min(0)}`
* `ReservationRequest {orderNumber @NotBlank, items @NotEmpty [ReservationItemRequest {productId @NotNull, quantity @NotNull @Min(1)}]}`
* `ReservationResponse {orderNumber, status, items: [ReservationItemResponse {productId, quantity}], createdAt, expiresAt}`
* Reservation status: `RESERVED, COMMITTED, RELEASED, EXPIRED, RETURNED`

| Method & path | Result |
|---|---|
| `GET /api/inventory/{productId}` | 200 `InventoryResponse`; 404 `INVENTORY_NOT_FOUND` |
| `GET /api/inventory?productIds=1,2` | 200 `List<InventoryResponse>` |
| `GET /api/inventory/{productId}/availability?quantity=2` | 200 `AvailabilityResponse`; 404 `INVENTORY_NOT_FOUND` |
| `PUT /api/inventory/{productId}` | 200 `InventoryResponse` (upsert) |
| `POST /api/inventory/reservations` | 201 `ReservationResponse`; 200 if a reservation for this orderNumber already exists (idempotent); 409 `INSUFFICIENT_STOCK` details `{productId, requested, available}`; 404 `INVENTORY_NOT_FOUND` |
| `GET /api/inventory/reservations/{orderNumber}` | 200; 404 `RESERVATION_NOT_FOUND` |
| `POST /api/inventory/reservations/{orderNumber}/commit` | 200 (RESERVED -> COMMITTED; idempotent) |
| `POST /api/inventory/reservations/{orderNumber}/release` | 200 (RESERVED -> RELEASED, COMMITTED -> RETURNED i.e. restock; idempotent) |

### cart-service (8083)
`customerId` path variable pattern `^[A-Za-z0-9-]{3,64}$` (e.g. `CUST-1001`). Max quantity per line: 10.
* `CartResponse {customerId, items: [CartItemResponse {productId, sku, productName, unitPrice, quantity, lineTotal}], totalQuantity, subtotal, currency, updatedAt}`
* `AddCartItemRequest {productId @NotNull, quantity @NotNull @Min(1) @Max(10)}`; `UpdateCartItemRequest {quantity @NotNull @Min(1) @Max(10)}`

| Method & path | Result |
|---|---|
| `GET /api/carts/{customerId}` | 200 `CartResponse` (empty cart if none exists) |
| `POST /api/carts/{customerId}/items` | 200 `CartResponse` (adds to existing line); 404 `PRODUCT_NOT_FOUND`; 400 `PRODUCT_INACTIVE`, `QUANTITY_LIMIT_EXCEEDED`; 409 `INSUFFICIENT_STOCK`; 503 `PRODUCT_SERVICE_UNAVAILABLE` / `INVENTORY_SERVICE_UNAVAILABLE` |
| `PUT /api/carts/{customerId}/items/{productId}` | 200 `CartResponse`; 404 `CART_ITEM_NOT_FOUND` |
| `DELETE /api/carts/{customerId}/items/{productId}` | 200 `CartResponse`; 404 `CART_ITEM_NOT_FOUND` |
| `DELETE /api/carts/{customerId}` | 204 |

### order-service (8084)
* `CheckoutRequest {customerId @NotBlank, cardToken @NotBlank, shippingAddress @Valid @NotNull AddressDto {fullName, line1, city, postalCode, country} (all @NotBlank)}` + header `Idempotency-Key` (required, 8..100 chars)
* `OrderResponse {orderNumber, customerId, status, totalAmount, currency, items: [OrderItemResponse {productId, sku, productName, unitPrice, quantity, lineTotal}], paymentReference, failureReason, shippingAddress: AddressDto, createdAt, updatedAt}`
* `OrderSummaryResponse {orderNumber, status, totalAmount, currency, itemCount, createdAt}`
* `OrderTrackingResponse {orderNumber, customerId, currentStatus, history: [StatusHistoryResponse {fromStatus, toStatus, reason, changedAt}]}`
* `StatusUpdateRequest {status @NotNull}` (only `SHIPPED`, `DELIVERED` accepted)
* Order status: `CREATED, INVENTORY_RESERVED, CONFIRMED, PAYMENT_FAILED, CANCELLED, SHIPPED, DELIVERED`
* Allowed transitions: `CREATED -> INVENTORY_RESERVED | CANCELLED`; `INVENTORY_RESERVED -> CONFIRMED | PAYMENT_FAILED | CANCELLED`;
  `CONFIRMED -> SHIPPED | CANCELLED`; `SHIPPED -> DELIVERED`. `PAYMENT_FAILED, CANCELLED, DELIVERED` are terminal.
* Order number format: `ORD-yyyyMMdd-XXXXXXXX` (8 upper-case hex chars).

| Method & path | Result |
|---|---|
| `POST /api/orders/checkout` | 201 `OrderResponse` (CONFIRMED); 200 + header `Idempotent-Replayed: true` when the Idempotency-Key was seen before (returns stored order whatever its status); 400 `EMPTY_CART`, `INVALID_PRODUCT` (no order stored); 409 `INSUFFICIENT_STOCK` (order stored as CANCELLED); 402 `PAYMENT_DECLINED` (order PAYMENT_FAILED); 503 `INVENTORY_SERVICE_UNAVAILABLE` (order CANCELLED) / `PAYMENT_SERVICE_UNAVAILABLE` (order PAYMENT_FAILED). Error responses carry `details.orderNumber` when an order was stored. |
| `GET /api/orders/{orderNumber}` | 200 `OrderResponse`; 404 `ORDER_NOT_FOUND` |
| `GET /api/orders?customerId=CUST-1001&page=0&size=10` | 200 `PageResponse<OrderSummaryResponse>` newest first |
| `GET /api/orders/{orderNumber}/tracking` | 200 `OrderTrackingResponse` |
| `POST /api/orders/{orderNumber}/cancel` | 200 `OrderResponse` (CONFIRMED -> CANCELLED, reason CUSTOMER_REQUESTED); 409 `INVALID_STATUS_TRANSITION` |
| `PATCH /api/orders/{orderNumber}/status` | 200 `OrderResponse`; 409 `INVALID_STATUS_TRANSITION` |
| `GET /api/orders/admin/outbox?status=FAILED` | 200 list of outbox events |
| `POST /api/orders/admin/outbox/{id}/retry` | 200 (resets to PENDING) |

### payment-service (8085) - SIMULATED gateway, no real payment provider
* `PaymentRequest {orderNumber @NotBlank, customerId @NotBlank, amount @NotNull @DecimalMin(0.01), currency @NotBlank, cardToken @NotBlank, idempotencyKey @NotBlank}`
* `PaymentResponse {paymentReference, orderNumber, customerId, amount, currency, status, failureReason, createdAt, updatedAt}`
* Payment status: `PROCESSING, SUCCEEDED, FAILED, REFUNDED`. Reference format: `PAY-XXXXXXXXXXXX`.
* Simulation (`app.payment.simulation.*`): `tok_declined` -> FAILED `CARD_DECLINED`; `tok_insufficient_funds` -> FAILED
  `INSUFFICIENT_FUNDS`; amount > `max-amount` (5000.00) -> FAILED `AMOUNT_LIMIT_EXCEEDED`; `tok_timeout` -> sleeps
  `timeout-delay-ms` (6000) then SUCCEEDED (longer than order-service's 3s read timeout); `tok_error` -> 503
  `PAYMENT_GATEWAY_ERROR` (payment stored FAILED `GATEWAY_ERROR`); anything else (e.g. `tok_visa`) -> SUCCEEDED.

| Method & path | Result |
|---|---|
| `POST /api/payments` | 201 `PaymentResponse` (status SUCCEEDED **or** FAILED - a declined payment is a recorded attempt); 200 when `idempotencyKey` already exists (returns stored payment, may be PROCESSING); 503 `PAYMENT_GATEWAY_ERROR` |
| `GET /api/payments/{paymentReference}` | 200; 404 `PAYMENT_NOT_FOUND` |
| `GET /api/payments/order/{orderNumber}` | 200 latest payment of the order; 404 `PAYMENT_NOT_FOUND` |
| `POST /api/payments/{paymentReference}/refund` | 200 (SUCCEEDED -> REFUNDED, publishes payment-refunded); 409 `INVALID_PAYMENT_STATE` |
| `GET /api/payments/admin/outbox?status=FAILED`, `POST /api/payments/admin/outbox/{id}/retry` | as order-service |

### notification-service (8086)
* `NotificationResponse {id, eventId, customerId, orderNumber, type, channel, recipient, subject, message, status, createdAt}`
* Notification type: `ORDER_PLACED, ORDER_CONFIRMED, ORDER_CANCELLED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_REFUNDED`; channel `EMAIL` (simulated: logged + stored, recipient `<customerId>@example.com`)
* `FailedEventResponse {id, originalTopic, messageKey, payload, exceptionMessage, consumerGroup, status (NEW|REPLAYED), createdAt, replayedAt}`

| Method & path | Result |
|---|---|
| `GET /api/notifications?customerId=&page=&size=` | 200 `PageResponse<NotificationResponse>` newest first |
| `GET /api/notifications/order/{orderNumber}` | 200 `List<NotificationResponse>` |
| `GET /api/notifications/admin/failed-events?status=NEW` | 200 `List<FailedEventResponse>` (dead-lettered records from ALL consumers) |
| `POST /api/notifications/admin/failed-events/{id}/replay` | 200 (republishes payload with original key to original topic) |
| `GET /api/notifications/admin/simulated-failures` / `PUT` body `{customerIds:[...]}` | 200 - notifications for these customers throw, to demonstrate retry + DLT |

## 6. Kafka

Constants: `com.philips.ecommerce.common.event.KafkaTopics`. Broker: ZooKeeper-based Confluent 7.4.x, `auto.create.topics.enable=false`.
All topics: 3 partitions, replication factor 1 (local). Value = JSON string (`StringSerializer` / `StringDeserializer`,
mapped with Spring's `ObjectMapper`). Key = `orderNumber` for every topic (preserves per-order ordering within a partition).
Headers: `eventType` (e.g. `OrderCreatedEvent`), `X-Correlation-Id`.

| Topic | Event class (common-lib) | Producer | Consumer groups |
|---|---|---|---|
| `order-created` | `OrderCreatedEvent` | order-service (outbox) | notification-service |
| `order-confirmed` | `OrderConfirmedEvent` | order-service (outbox) | inventory-service (commit reservation), cart-service (clear cart), notification-service |
| `order-cancelled` | `OrderCancelledEvent` | order-service (outbox) | inventory-service (release / restock), payment-service (void / refund), notification-service |
| `payment-processed` | `PaymentProcessedEvent` | payment-service (outbox) | notification-service |
| `payment-refunded` | `PaymentRefundedEvent` | payment-service (outbox) | notification-service |
| `<topic>.DLT` (5 topics) | original payload | `DeadLetterPublishingRecoverer` in each consumer | notification-service-dlt |

Consumer group id = `spring.application.name`. DLT listener group = `notification-service-dlt`.
Producer settings: `acks=all`, `enable.idempotence=true`, `retries` high, `max.in.flight.requests.per.connection=5`.
Consumer settings: `enable-auto-commit=false`, `auto-offset-reset=earliest`, listener `ack-mode: record`.
Error handling: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(app.kafka.retry.interval-ms=1000, app.kafka.retry.max-attempts=3)`;
deserialization/`JsonProcessingException` and `IllegalArgumentException` are not retried.
Idempotent consumers: table `processed_event(event_id PK, event_type, topic, processed_at)` checked and written in the
SAME local transaction as the business change.

Transactional outbox (order-service, payment-service): table `outbox_event` written in the same DB transaction as the
state change; `OutboxPublisher` polls PENDING rows (`SELECT ... FOR UPDATE SKIP LOCKED`), sends synchronously, marks
SENT, or increments `attempts` and sets FAILED after `app.outbox.max-attempts` (10). Admin endpoints allow retry.

## 7. Saga (orchestrated by order-service)

1. `POST /api/orders/checkout` -> check `Idempotency-Key` (unique column) -> replay if known.
2. Feign `GET /api/carts/{customerId}` -> 400 `EMPTY_CART` if no items.
3. Feign `GET /api/products/batch?ids=` -> authoritative prices; missing/inactive -> 400 `INVALID_PRODUCT`.
4. TX1: insert order `CREATED` + items + history + outbox `order-created`.
5. Feign `POST /api/inventory/reservations` (idempotent by orderNumber; Resilience4j retry + circuit breaker).
   409 -> TX: `CANCELLED` (INSUFFICIENT_STOCK) + outbox `order-cancelled` -> respond 409.
   timeout/5xx -> TX: `CANCELLED` (INVENTORY_ERROR) + outbox `order-cancelled` (inventory releases if it did reserve) -> 503.
6. TX2: `INVENTORY_RESERVED`.
7. Feign `POST /api/payments` (idempotencyKey = `PAY-` + orderNumber; read timeout 3s).
   SUCCEEDED -> TX3: `CONFIRMED` + paymentReference + outbox `order-confirmed` -> 201.
   FAILED -> TX3: `PAYMENT_FAILED` + outbox `order-cancelled` (PAYMENT_DECLINED) -> 402.
   timeout/5xx/PROCESSING -> TX3: `PAYMENT_FAILED` + outbox `order-cancelled` (PAYMENT_ERROR) -> 503.
8. Async: inventory commits (confirmed) or releases (cancelled); cart cleared on confirmed; payment-service voids/refunds
   on cancelled (marks PROCESSING payments `cancelRequested` so a late success is refunded immediately); notifications.
9. Safety net: inventory-service expires RESERVED reservations older than `app.inventory.reservation-ttl-minutes` (15).

Remote calls are never inside a DB transaction; each local step is its own `@Transactional` method on a separate bean.

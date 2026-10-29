# Architecture

Diagrams are written in Mermaid. They render on GitHub and GitLab, in IntelliJ (Markdown plugin), and in VS Code
(the "Markdown Preview Mermaid Support" extension), or you can paste them into https://mermaid.live.
Pre-rendered SVGs of every diagram (generated with mermaid-cli 11) are in [`docs/diagrams/`](diagrams/).

## 1. Overall architecture

```mermaid
flowchart LR
    client([Client / Postman / scripts]) -->|HTTP :8080| gw[api-gateway<br/>Spring Cloud Gateway]
    subgraph platform[Spring Cloud platform]
        cfg[config-server :8888<br/>native ./config-repo]
        eur[discovery-server :8761<br/>Eureka]
    end
    gw -->|lb://product-service| ps[product-service :8081]
    gw -->|lb://inventory-service| is[inventory-service :8082]
    gw -->|lb://cart-service| cs[cart-service :8083]
    gw -->|lb://order-service| os[order-service :8084]
    gw -->|lb://payment-service| pay[payment-service :8085]
    gw -->|lb://notification-service| ns[notification-service :8086]

    cs -. OpenFeign .-> ps
    cs -. OpenFeign .-> is
    os -. OpenFeign .-> cs
    os -. OpenFeign .-> ps
    os -. OpenFeign .-> is
    os -. OpenFeign .-> pay

    ps --- redis[(Redis 7<br/>cache-aside)]
    ps --- s3[(Amazon S3 / MinIO<br/>product images)]
    ps --- db1[(philips_product)]
    is --- db2[(philips_inventory)]
    cs --- db3[(philips_cart)]
    os --- db4[(philips_order)]
    pay --- db5[(philips_payment)]
    ns --- db6[(philips_notification)]

    os == outbox ==> k{{Kafka + ZooKeeper}}
    pay == outbox ==> k
    k ==> is
    k ==> cs
    k ==> pay
    k ==> ns

    ps & is & cs & os & pay & ns & gw -. register / fetch config .-> platform
```

* **Synchronous** (OpenFeign + Eureka client-side load balancing + Resilience4j retry and circuit breaker): calls
  where the caller needs an answer to continue: validating the cart, pricing, reserving stock, charging payment.
* **Asynchronous** (Kafka): state changes that other services react to: commit or release stock, clear the cart,
  refund, notify. Producers use a **transactional outbox**, and consumers are **idempotent** (`processed_event` table).
* **Database per service**: six schemas on one MySQL server locally. No service reads another service's tables.

## 2. Checkout saga: happy path

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant GW as api-gateway
    participant O as order-service
    participant CA as cart-service
    participant P as product-service
    participant I as inventory-service
    participant PAY as payment-service
    participant K as Kafka
    participant N as notification-service
    C->>GW: POST /api/orders/checkout (Idempotency-Key)
    GW->>O: route lb://order-service
    O->>O: idempotency key seen? (no)
    O->>CA: GET /api/carts/{customerId}
    CA-->>O: items
    O->>P: GET /api/products/batch?ids=...
    P-->>O: current prices, active flags
    Note over O: TX1: INSERT order CREATED + items + history + outbox(order-created)
    O->>I: POST /api/inventory/reservations {orderNumber, items}
    Note over I: one TX: SELECT ... FOR UPDATE (sorted ids)<br/>available -= q, reserved += q
    I-->>O: 201 RESERVED
    Note over O: TX2: status INVENTORY_RESERVED
    O->>PAY: POST /api/payments {idempotencyKey=PAY-orderNumber}
    Note over PAY: TX: PROCESSING → simulated gateway → TX: SUCCEEDED + outbox(payment-processed)
    PAY-->>O: 201 SUCCEEDED, paymentReference
    Note over O: TX3: CONFIRMED + outbox(order-confirmed)
    O-->>C: 201 OrderResponse (CONFIRMED)
    O--)K: OutboxPublisher: order-created, order-confirmed
    K--)I: order-confirmed → reservation COMMITTED
    K--)CA: order-confirmed → cart cleared
    K--)N: order-created / order-confirmed / payment-processed
    N->>N: store + "send" (simulated) e-mail
```

## 3. Payment failure and compensation

```mermaid
sequenceDiagram
    autonumber
    participant O as order-service
    participant I as inventory-service
    participant PAY as payment-service
    participant K as Kafka
    participant N as notification-service
    O->>I: reserve stock
    I-->>O: 201 RESERVED
    O->>PAY: POST /api/payments (cardToken=tok_declined)
    PAY-->>O: 201 status=FAILED reason=CARD_DECLINED
    Note over O: TX: PAYMENT_FAILED + outbox(order-cancelled, PAYMENT_DECLINED)
    O-->>O: respond 402 PAYMENT_DECLINED {details.orderNumber}
    O--)K: order-cancelled
    K--)I: release: reserved -= q, available += q → RELEASED
    K--)PAY: payment FAILED → nothing to refund (no-op)
    K--)N: ORDER_CANCELLED + PAYMENT_FAILED notifications

    Note over O,PAY: Variant tok_timeout: payment takes 6 s, Feign read timeout is 3 s
    O->>PAY: POST /api/payments (tok_timeout)
    O-->>O: SocketTimeout → PAYMENT_FAILED + order-cancelled(PAYMENT_ERROR) → 503
    K--)PAY: order-cancelled while payment PROCESSING → cancelRequested=true
    PAY->>PAY: gateway finally says SUCCESS → status REFUNDED + outbox(payment-refunded)
    PAY--)K: payment-refunded → N notifies the refund
```

Inventory failures follow the same pattern: a `409 INSUFFICIENT_STOCK` means nothing was reserved, so the order goes
straight to `CANCELLED`. A timeout means a reservation *may* exist, so the order is `CANCELLED` and `order-cancelled` is
still published, and inventory-service releases the reservation if it exists. The reservation-expiry job is the safety
net if every message were lost.

## 4. Kafka processing: outbox, idempotent consumer, retry, DLT, replay

```mermaid
sequenceDiagram
    autonumber
    participant SVC as order-service (business TX)
    participant DB as outbox_event table
    participant PUB as OutboxPublisher (@Scheduled)
    participant K as Kafka topic (3 partitions, key=orderNumber)
    participant CON as consumer (e.g. inventory-service)
    participant PE as processed_event table
    participant DLT as <topic>.DLT
    participant NS as notification-service-dlt
    SVC->>DB: INSERT state change + outbox row (same TX = atomic)
    PUB->>DB: SELECT ... WHERE status=PENDING FOR UPDATE SKIP LOCKED
    PUB->>K: send(key, json, headers eventType, X-Correlation-Id).get()
    PUB->>DB: status=SENT (or attempts++ / FAILED)
    K->>CON: record
    CON->>PE: eventId already processed?
    alt duplicate (redelivery / replay)
        CON-->>K: skip, commit offset
    else new
        CON->>CON: business change + INSERT processed_event (one TX)
        CON-->>K: commit offset (ack-mode=record)
    end
    Note over CON: exception → DefaultErrorHandler retries (FixedBackOff 1 s × 3)
    CON->>DLT: DeadLetterPublishingRecoverer (same partition, original headers)
    DLT->>NS: store failed_event (topic, partition, offset, exception, group)
    NS->>K: POST /admin/failed-events/{id}/replay → republish to original topic
```

## 5. Redis cache-aside (product-service, optimized mode)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant PC as ProductController
    participant Q as OptimizedProductQueryService
    participant R as Redis
    participant DB as MySQL (source of truth)
    C->>PC: GET /api/products/42
    PC->>Q: getProduct(42)
    Q->>R: GET product:v1:detail:42
    alt cache hit
        R-->>Q: JSON → hits++
    else miss (or Redis down → errors++, fall through)
        R-->>Q: nil → misses++
        Q->>DB: 2-3 bounded queries (entity graph + batch)
        Q->>R: SET product:v1:detail:42 json EX 600
    end
    Q-->>C: 200 ProductDetailResponse
    Note over PC,DB: PUT /api/products/42 → TX commit → AFTER_COMMIT:<br/>DEL product:v1:detail:42 and INCR product:v1:list-version<br/>(all list/search keys embed the version, so old pages become unreachable and expire by TTL)
```

## 6. Database relationships (one schema per service)

```mermaid
erDiagram
    CATEGORY ||--o{ PRODUCT : contains
    PRODUCT ||--o{ PRODUCT_IMAGE : has
    PRODUCT ||--o{ PRODUCT_ATTRIBUTE : has
    CATEGORY { bigint id PK
        varchar slug UK
        varchar name }
    PRODUCT { bigint id PK
        varchar sku UK
        varchar name
        varchar brand
        decimal price
        bigint category_id FK
        boolean active
        bigint version }
    PRODUCT_IMAGE { bigint id PK
        bigint product_id FK
        varchar s3_key
        int sort_order }
    PRODUCT_ATTRIBUTE { bigint id PK
        bigint product_id FK
        varchar name
        varchar value }

    INVENTORY { bigint id PK
        bigint product_id UK
        varchar sku UK
        int available_quantity
        int reserved_quantity
        bigint version }
    STOCK_RESERVATION ||--|{ STOCK_RESERVATION_ITEM : includes
    STOCK_RESERVATION { bigint id PK
        varchar order_number UK
        varchar status
        datetime expires_at }
    STOCK_RESERVATION_ITEM { bigint id PK
        bigint reservation_id FK
        bigint product_id
        int quantity }

    CART ||--o{ CART_ITEM : holds
    CART { bigint id PK
        varchar customer_id UK }
    CART_ITEM { bigint id PK
        bigint cart_id FK
        bigint product_id
        int quantity
        decimal unit_price }

    ORDERS ||--|{ ORDER_ITEM : contains
    ORDERS ||--o{ ORDER_STATUS_HISTORY : tracks
    ORDERS { bigint id PK
        varchar order_number UK
        varchar idempotency_key UK
        varchar customer_id
        varchar status
        decimal total_amount }
    ORDER_ITEM { bigint id PK
        bigint order_id FK
        bigint product_id
        int quantity }
    ORDER_STATUS_HISTORY { bigint id PK
        bigint order_id FK
        varchar from_status
        varchar to_status }

    PAYMENT { bigint id PK
        varchar payment_reference UK
        varchar idempotency_key UK
        varchar order_number
        varchar status
        boolean cancel_requested }

    NOTIFICATION { bigint id PK
        varchar event_id UK
        varchar customer_id
        varchar type }
    FAILED_EVENT { bigint id PK
        varchar original_topic
        longtext payload
        varchar status }
```

The order-service and payment-service schemas also have `outbox_event`. The inventory, cart and payment schemas have
`processed_event`. Cross-service references (for example `order_item.product_id`) are **logical** IDs, not foreign
keys, because each service owns its own database.

## 7. Consistency model

| Concern | Mechanism | Where |
|---|---|---|
| Atomic "update DB + publish event" | Transactional outbox | `order-service/.../outbox`, `payment-service/.../outbox` |
| At-least-once delivery → effectively once | `processed_event` / unique `event_id` checked in the business TX | inventory, cart, payment, notification consumers |
| Duplicate checkout | Unique `orders.idempotency_key`, replay returns stored order | `CheckoutService` |
| Duplicate payment | Unique `payment.idempotency_key` (`PAY-<orderNumber>`) | `PaymentService` |
| Overselling | `SELECT ... FOR UPDATE` in sorted product order, all-or-nothing | `ReservationService` |
| Lost compensation | Reservation TTL expiry job | inventory-service scheduler |
| Concurrent admin edits | `@Version` optimistic locking → 409 `CONCURRENT_MODIFICATION` | entities with `version` |
| Per-order ordering | Kafka key = orderNumber (same partition) | all producers |

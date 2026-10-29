# Philips E-Commerce Platform: Java 8 Microservices Learning Project

A complete, runnable e-commerce backend built to study, and to defend in interviews, every item of a Java backend
resume: **Java 8, Spring Boot 2.7, Spring MVC/REST, Spring Data JPA/Hibernate, MySQL, Spring Cloud (Config,
Eureka, Gateway, OpenFeign), Resilience4j, Redis, Apache Kafka (ZooKeeper mode), Swagger/OpenAPI, Docker,
AWS S3/EC2, Jenkins, SonarQube, JUnit 5 and Mockito**, plus a reproducible before/after performance experiment.

> This is a learning project. "Philips" is only the scenario (the product catalogue uses Philips-style consumer
> products). Payments are **simulated**: there is no real payment-gateway integration.

* Where is X implemented? See [docs/learning-guide/RESUME-MAP.md](docs/learning-guide/RESUME-MAP.md).
* Diagrams: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Contracts: [docs/CONTRACTS.md](docs/CONTRACTS.md).
  Decisions: [docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md). Versions: [docs/VERSION-MATRIX.md](docs/VERSION-MATRIX.md).
* Performance results: [docs/PERFORMANCE-RESULTS.md](docs/PERFORMANCE-RESULTS.md). AWS/Jenkins/Sonar: [docs/DEPLOYMENT-GUIDE.md](docs/DEPLOYMENT-GUIDE.md).

---

## 1. Architecture in one minute

```
client ─► api-gateway :8080 ─(lb:// via Eureka)─► product :8081 ─ MySQL philips_product + Redis + S3/MinIO
                                                 inventory :8082 ─ MySQL philips_inventory
                                                 cart :8083 ────── MySQL philips_cart   (Feign → product, inventory)
                                                 order :8084 ───── MySQL philips_order  (Feign → cart, product, inventory, payment)
                                                 payment :8085 ─── MySQL philips_payment (simulated gateway)
                                                 notification :8086 MySQL philips_notification
config-server :8888 (./config-repo)   discovery-server :8761 (Eureka)   Kafka :9092 + ZooKeeper :2181
order/payment ──outbox──► Kafka ──► inventory (commit/release), cart (clear), payment (refund), notification (+DLT store)
```

Checkout is an **orchestrated saga** in order-service. It makes synchronous calls (cart → prices → reserve stock →
charge) and publishes asynchronous Kafka events (commit or release stock, clear the cart, refund, notify) through a
**transactional outbox** to **idempotent consumers**. Every failure path (insufficient stock, decline, timeout,
duplicate request, duplicate event, poison message) has a compensation and a test.

## 2. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **8** (reference) or 11/17/21 | Verified: the full build + 387 tests pass on Temurin **8u504** and on **21.0.12**. On JDK 9+ the POM applies `--release 8` automatically, so the bytecode and API level stay Java 8. The Docker images always run Java 8. |
| Maven | 3.6.3+ | or use the included wrapper `./mvnw` (downloads Maven 3.9.9) |
| Docker | Engine 24+ with Compose v2 | Docker Desktop: give it **≥ 6 GB RAM** |
| curl, jq, python3 | any recent | used by the scripts and the benchmark |

Free ports: 3306, 6379, 2181, 9092, 9000, 9001, 8090, 8080–8086, 8761, 8888 (`scripts/check-prereqs.sh` checks them).

## 3. Quick start (exact commands)

```bash
cd philips-ecommerce
cp .env.example .env                       # safe local defaults (no real secrets)
scripts/check-prereqs.sh                   # tools, versions, free ports, Docker memory
./mvnw -B clean verify                     # build + 387 unit/slice/embedded-Kafka tests (JDK 8)
docker compose build                       # 9 images on eclipse-temurin:8-jre
scripts/start.sh                           # starts everything in dependency order, waits for health
scripts/health-check.sh                    # 28 checks: containers, Eureka, gateway routes, topics, bucket
scripts/smoke-test.sh                      # 78 end-to-end checks of the business journeys
```

Or run everything in one go: `scripts/verify.sh` (add `--with-it` for Testcontainers tests and `--with-perf` for
the benchmark). It writes `verify-report-<timestamp>.txt`.

Shut down: `scripts/stop.sh` (keeps data) or `scripts/stop.sh --volumes` (wipes MySQL, Kafka, Redis and MinIO data).

### Startup order (and why)

| # | Component | Waits for | Why |
|---|---|---|---|
| 1 | `mysql`, `redis`, `zookeeper`, `minio` | - | infrastructure |
| 2 | `mysql-init` (one-shot) | MySQL healthy | creates the 6 schemas and grants the app user |
| 3 | `kafka` → `kafka-init` (one-shot) | ZooKeeper healthy → Kafka healthy | broker registers in ZooKeeper; then the 5 topics + 5 `.DLT` topics are created (auto-create is off) |
| 4 | `minio-init` (one-shot) | MinIO healthy | creates the `philips-product-images` bucket |
| 5 | `config-server` :8888 | - | every other app imports its config at startup (`fail-fast` + retry) |
| 6 | `discovery-server` :8761 | - | Eureka registry |
| 7 | product, inventory, cart, order, payment, notification :8081–8086 | config + discovery + their infrastructure | Flyway migrates each schema, then they register in Eureka |
| 8 | `api-gateway` :8080 | config + discovery | routes `lb://<service>` through Eureka |

`docker compose up -d` enforces this through `depends_on: condition: service_healthy / service_completed_successfully`.
`scripts/start.sh` additionally starts the layers one by one and waits for each `/actuator/health`. Health endpoints
for every component are listed in section 5.

### Running services from the IDE instead

```bash
scripts/start.sh --infra-only              # MySQL, Redis, ZooKeeper, Kafka, MinIO, Kafka UI (+ init jobs)
# then start, in this order, each *Application class (or: ./mvnw -pl <module> spring-boot:run)
#   ConfigServerApplication → DiscoveryServerApplication → the six services → ApiGatewayApplication
```

All defaults point at `localhost` (see `docs/CONTRACTS.md`, section 2), so no environment variables are needed.

## 4. Environment configuration

* `.env` (from `.env.example`) holds Docker Compose variables: MySQL root/app passwords, MinIO credentials,
  `IMAGE_TAG`, `PRODUCT_QUERY_MODE` (`optimized`|`baseline`), `PRODUCT_CACHE_ENABLED`.
* Application configuration is **centralised** in `config-repo/` and served by config-server:
  `application.yml` (shared) plus `<service>.yml`. Values use `${ENV_VAR:default}` placeholders, so the same files
  work on the host (`localhost`) and in Docker (`mysql`, `kafka:29092`, ...).
* Each service's own `src/main/resources/application.yml` only holds its port, its name and the config-server import.

## 5. Ports, health and documentation URLs

| Component | URL | Health |
|---|---|---|
| API Gateway (use this) | http://localhost:8080 | `/actuator/health` |
| Swagger UI (all services) | http://localhost:8080/swagger-ui.html | |
| product-service | http://localhost:8081/swagger-ui.html | http://localhost:8081/actuator/health |
| inventory-service | http://localhost:8082/swagger-ui.html | :8082/actuator/health |
| cart-service | http://localhost:8083/swagger-ui.html | :8083/actuator/health |
| order-service | http://localhost:8084/swagger-ui.html | :8084/actuator/health |
| payment-service | http://localhost:8085/swagger-ui.html | :8085/actuator/health |
| notification-service | http://localhost:8086/swagger-ui.html | :8086/actuator/health |
| Eureka dashboard | http://localhost:8761 | |
| Config server | http://localhost:8888/product-service/default | :8888/actuator/health |
| Kafka UI | http://localhost:8090 | |
| MinIO console (S3) | http://localhost:9001 (minioadmin / minioadmin) | |
| MySQL | localhost:3306 (philips / philips_pwd) | |

Postman: import `postman/philips-ecommerce.postman_collection.json` and `postman/local.postman_environment.json`.
The "Checkout journey" folder runs in execution order. Headless: `scripts/postman-test.sh` (newman via `npx`).

## 6. API walkthrough (execution order, through the gateway)

All responses below were **captured from the running stack** on 2026-10-28 (timestamps and generated ids will
differ on your machine). `G=http://localhost:8080`.

**1. Browse and search** (Redis-cached in optimized mode)
```bash
curl "$G/api/products?categoryId=1&size=20&sort=price,desc"     # PageResponse {content[], page, size, totalElements, totalPages, last}
curl "$G/api/products?q=sonicare"                                # FULLTEXT search
curl "$G/api/products/1"
```
```json
{"id":1,"sku":"PH-SONIC-9900","name":"Philips Sonicare DiamondClean Prestige 9900 Electric Toothbrush","price":329.99,
 "currency":"EUR","category":{"id":1,"name":"Oral Care",...},"images":[...],
 "attributes":[{"name":"Battery life","value":"Up to 4 weeks"}, ...], ...}
```

**2. Check stock**
```bash
curl "$G/api/inventory/1/availability?quantity=2"
```
```json
{"productId":1,"requestedQuantity":2,"availableQuantity":117,"available":true}
```

**3. Add to cart** (cart-service validates the product and stock through Feign)
```bash
curl -X POST $G/api/carts/CUST-1001/items -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'
curl -X POST $G/api/carts/CUST-1001/items -H 'Content-Type: application/json' -d '{"productId":4,"quantity":1}'
```
```json
{"customerId":"CUST-1001","items":[{"productId":1,"sku":"PH-SONIC-9900","productName":"Philips Sonicare DiamondClean Prestige 9900 Electric Toothbrush","unitPrice":329.99,"quantity":2,"lineTotal":659.98},
 {"productId":4,"sku":"PH-HUE-E27-START3","productName":"Philips Hue White and Color Ambiance E27 Starter Kit (3 bulbs + Bridge)","unitPrice":169.99,"quantity":1,"lineTotal":169.99}],
 "totalQuantity":3,"subtotal":829.97,"currency":"EUR","updatedAt":"2026-10-28T13:11:32.913Z"}
```

**4. Checkout** (saga: cart → prices → reserve → pay → confirm)
```bash
curl -i -X POST $G/api/orders/checkout -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{
  "customerId":"CUST-1001","cardToken":"tok_visa",
  "shippingAddress":{"fullName":"Ada Lovelace","line1":"High Tech Campus 52","city":"Eindhoven","postalCode":"5656 AE","country":"NL"}}'
```
```
HTTP/1.1 201 Created
{"orderNumber":"ORD-20261028-384FCA01","customerId":"CUST-1001","status":"CONFIRMED","totalAmount":829.97,"currency":"EUR",
 "items":[...],"paymentReference":"PAY-BB12CC17395D","shippingAddress":{...},"createdAt":"2026-10-28T13:11:32.967Z",...}
```
Sending the **same `Idempotency-Key` again** gives `HTTP/1.1 200 OK` with `Idempotent-Replayed: true` and the same
order. There is no second reservation and no second charge.

**5. Asynchronous effects** (Kafka, about 1 s later)
```bash
curl $G/api/inventory/reservations/ORD-20261028-384FCA01     # {"orderNumber":"ORD-...","status":"COMMITTED",...}
curl $G/api/carts/CUST-1001                                  # {"totalQuantity":0,"items":[],...}  (cleared by order-confirmed)
curl $G/api/notifications/order/ORD-20261028-384FCA01
```
```json
[{"type":"ORDER_PLACED","subject":"We received your order ORD-20261028-384FCA01"},
 {"type":"ORDER_CONFIRMED","subject":"Your order ORD-20261028-384FCA01 is confirmed"},
 {"type":"PAYMENT_SUCCEEDED","subject":"Payment received for order ORD-20261028-384FCA01"}]
```

**6. Tracking and history**
```bash
curl $G/api/orders/ORD-20261028-384FCA01/tracking
curl "$G/api/orders?customerId=CUST-1001&page=0&size=10"
```
```json
{"orderNumber":"ORD-20261028-384FCA01","customerId":"CUST-1001","currentStatus":"CONFIRMED","history":[
 {"toStatus":"CREATED","reason":"Order placed","changedAt":"2026-10-28T13:11:32.966Z"},
 {"fromStatus":"CREATED","toStatus":"INVENTORY_RESERVED","reason":"Inventory reserved","changedAt":"2026-10-28T13:11:33.052Z"},
 {"fromStatus":"INVENTORY_RESERVED","toStatus":"CONFIRMED","reason":"Payment PAY-BB12CC17395D succeeded","changedAt":"2026-10-28T13:11:33.071Z"}]}
```

**7. Failure examples** (same error body shape everywhere)
```
cardToken "tok_declined" → HTTP 402
{"status":402,"error":"Payment Required","code":"PAYMENT_DECLINED","message":"Payment was declined: CARD_DECLINED",
 "path":"/api/orders/checkout","correlationId":"2f805664-...","details":{"orderNumber":"ORD-20261028-218C7D07",
 "paymentReference":"PAY-B6927523C5A1","failureReason":"CARD_DECLINED"}}

{"productId":2,"quantity":0} → HTTP 400
{"status":400,"code":"VALIDATION_FAILED","message":"Request validation failed",
 "fieldErrors":[{"field":"quantity","message":"must be greater than or equal to 1","rejectedValue":0}], ...}

inventory-service stopped → HTTP 503 (order stored as CANCELLED, reason INVENTORY_ERROR)
{"status":503,"code":"INVENTORY_SERVICE_UNAVAILABLE","message":"Stock could not be reserved, the order was cancelled: ...",
 "details":{"orderNumber":"ORD-20261028-6D14A6AE"}}
```

**8. Product image (S3 API via MinIO locally)**
```bash
curl -F "file=@photo.png;type=image/png" $G/api/products/3/images
# {"id":26,"url":"http://localhost:9000/philips-product-images/products/3/<uuid>.png","contentType":"image/png","sortOrder":1}
curl $G/api/products/3/images/26/presigned-url        # {"url":"http://localhost:9000/...X-Amz-Signature=...","expiresAt":...}
```

> After running the benchmark, the catalogue also contains 50,000 `BENCH-*` products. Use `scripts/stop.sh --volumes`
> to return to the 20 demo products.

## 7. Payment simulation and failure scenarios

| Input | Result |
|---|---|
| `cardToken: tok_visa` (or any other value) | 201, order `CONFIRMED` |
| `tok_declined` / `tok_insufficient_funds` | 402 `PAYMENT_DECLINED`; order `PAYMENT_FAILED`; stock released via Kafka |
| order total > 5000 EUR (product 19) | 402, payment `AMOUNT_LIMIT_EXCEEDED` |
| `tok_timeout` | gateway answers after 6 s but order-service's Feign read timeout is 3 s → 503 `PAYMENT_SERVICE_UNAVAILABLE`, order `PAYMENT_FAILED`; the late success is **refunded automatically** |
| `tok_error` | 503 `PAYMENT_SERVICE_UNAVAILABLE` (payment stored FAILED `GATEWAY_ERROR`) |
| product 5 with stock lowered after add-to-cart | 409 `INSUFFICIENT_STOCK`, order `CANCELLED` |
| product 20 (inactive) / 999999 | 400 `PRODUCT_INACTIVE` / 404 `PRODUCT_NOT_FOUND` on add-to-cart |
| same `Idempotency-Key` twice | 200 + `Idempotent-Replayed: true`, same order, no second charge |
| `PUT /api/notifications/admin/simulated-failures {"customerIds":["X"]}` | notifications for X fail → 3 retries → `<topic>.DLT` → `GET /api/notifications/admin/failed-events` → `POST .../{id}/replay` |
| stop inventory-service and check out | 503 `INVENTORY_SERVICE_UNAVAILABLE` (retry, then circuit breaker), order `CANCELLED` |

## 8. Performance experiment (baseline vs optimized)

```bash
scripts/start.sh                      # stack must be running
perf/run-benchmark.sh                 # seeds 50,000 products, runs S1..S5, writes perf/results/<ts>/REPORT.md
perf/run-benchmark.sh --skip-seed     # repeat (report the median of ≥ 3 runs)
```

| Scenario | What changes |
|---|---|
| S1 | baseline code (`PRODUCT_QUERY_MODE=baseline`), V2 indexes dropped, no cache |
| S2 | + V2 indexes → isolates **indexing** |
| S3 | optimized JPA/SQL code, cache disabled → isolates **query optimization** |
| S4 | + Redis cache, **cold** (flushed, no warmup) |
| S5 | + Redis cache, **warm** → isolates **caching** |

**Measured on 2026-10-28** (median of 3 runs, 50,000 products, 2,000 requests, concurrency 16, one laptop; full
analysis in [docs/PERFORMANCE-RESULTS.md](docs/PERFORMANCE-RESULTS.md), raw JSON in `perf/results-reference/`):

| | S1 baseline | S2 + indexes | S3 + query/JPA optimization | S5 + Redis (warm) |
|---|---|---|---|---|
| Mean latency (ms) | 146.4 | 176.0 | 35.9 | **13.3** |
| p95 latency (ms) | 396.7 | 613.4 | 96.5 | **27.3** |
| Throughput (req/s) | 108 | 90 | 443 | **1,193** |
| SQL statements / request | 17.6 | 17.6 | 2.9 | **0.4** |

Overall mean latency improved **90.9%** (S1 → S5). Without the cache (S1 → S3) the improvement is 75.5%. **Indexes
alone made the naive queries slower** (−20%), because a low-selectivity `(active, name)` index led the optimizer into
49k random lookups for the `LIKE '%term%'` count query (`EXPLAIN ANALYZE` in the results doc). The baseline is
deliberately naive, so treat these numbers as a lab result rather than a production promise.

Methodology, fairness controls and caveats: [perf/README.md](perf/README.md). Run the baseline yourself with
`PRODUCT_QUERY_MODE=baseline PRODUCT_CACHE_ENABLED=false docker compose up -d --no-deps --force-recreate product-service`.

## 9. Tests

| Command | What runs |
|---|---|
| `./mvnw -B test` | 387 tests: Mockito unit tests, `@WebMvcTest`, `@DataJpaTest` (H2), WireMock Feign tests, `@EmbeddedKafka` flows |
| `./mvnw -B -Pintegration-tests verify` | adds 22 `*IT` Testcontainers tests (needs Docker): product (MySQL 8 + Redis), order (checkout saga on MySQL + ZooKeeper-mode Kafka), inventory (Kafka → MySQL, idempotency, row-lock race), cart / payment / notification (MySQL schema, `@Version`, `SKIP LOCKED`, index usage) |
| `scripts/smoke-test.sh` | 78 HTTP checks against the running stack (through the gateway) |
| `scripts/postman-test.sh` | runs the Postman collection with newman (98 requests, 240 assertions) |
| `./mvnw -B -pl order-service test -Dtest=CheckoutServiceTest` | a single test class |

JaCoCo reports: `<module>/target/site/jacoco/index.html`. SonarQube: [docs/DEPLOYMENT-GUIDE.md](docs/DEPLOYMENT-GUIDE.md#4-sonarqube).

## 10. Troubleshooting

| Problem | Fix |
|---|---|
| `Access denied for user 'philips'@'%' to database ...` | the `mysql-init` job did not run: `docker compose up -d mysql-init && docker compose logs mysql-init` |
| Services restart with `No resolvable bootstrap urls` | Kafka is not up yet: `docker compose ps kafka kafka-init` |
| Service fails with `Could not locate PropertySource` | config-server not healthy yet; start order matters (`scripts/start.sh` handles it) |
| Gateway returns 503 `PRODUCT_SERVICE_UNAVAILABLE` right after start | the service is not registered in Eureka yet; wait about 30 s (Eureka registry refresh) |
| Exit code 137 / OOMKilled | give Docker Desktop more memory (≥ 6 GB) |
| `release version 8 not supported` | your JDK is too new for `--release 8` (JDK 22+ still supports 8 with a warning; very new JDKs may drop it); use JDK 8, 11, 17 or 21 |
| `minio/minio` or `minio/mc` pull fails | expected: MinIO stopped publishing those images; this project uses `bitnamilegacy/minio` |
| Port already in use | `scripts/check-prereqs.sh` lists the busy port; stop the other process or change the host port in `docker-compose.yml` |
| Slow Docker pulls | the first `docker compose up` downloads about 2 GB of images (MySQL, Confluent, MinIO, Kafka UI) |
| Stale data after changing init scripts | `scripts/stop.sh --volumes` |

Logs: `docker compose logs -f order-service`. Every line carries `[service,correlationId]`; send your own
`X-Correlation-Id` header and grep for it across services.

## 11. Repository layout

```
philips-ecommerce/
├── pom.xml                      parent POM: versions, Java 8 enforcement, JaCoCo, Sonar, IT profile
├── common-lib/                  shared events, error model, exceptions, auto-configuration, Feign decoder
├── config-server/ discovery-server/ api-gateway/
├── product-service/ inventory-service/ cart-service/ order-service/ payment-service/ notification-service/
├── config-repo/                 centralised YAML served by config-server
├── docker-compose.yml  docker/  mysql init + grants, kafka topics, minio bucket, jenkins image, multistage Dockerfile
├── perf/                        benchmark: SQL (seed, indexes, EXPLAIN), bench.py, report.py, run-benchmark.sh, results/
├── scripts/                     check-prereqs, build, start, stop, health-check, smoke-test, postman-test, verify
├── postman/                     collection + environment
├── deploy/                      EC2 user-data + deploy script, prod compose, IAM policies, S3 setup, security groups, Jenkins
├── Jenkinsfile  sonar-project.properties  .env.example  mvnw
└── docs/                        CONTRACTS, ARCHITECTURE (diagrams), DESIGN-DECISIONS, VERSION-MATRIX,
                                 DEPLOYMENT-GUIDE, PERFORMANCE-RESULTS, learning-guide/
```

## 12. Verification status (honest)

What was **actually executed** in the authoring environment (macOS arm64, Docker 29.8, 8 GB Docker VM):

| Check | Result |
|---|---|
| `mvn clean install` on **Temurin JDK 8u504** (all 11 modules, enforcer bytecode rule) | **PASSED**: 387 tests, 0 failures |
| Same build on Temurin JDK 21.0.12 (`--release 8`) | **PASSED**: 387 tests, 0 failures |
| `-Pintegration-tests` (Testcontainers: MySQL 8.0, Redis 7, Kafka `cp-kafka:7.4.4` in ZooKeeper mode) for product, order, inventory, cart, payment, notification | **PASSED**: 22 tests |
| `docker compose build` + `up` (9 Java 8 images + MySQL, Redis, ZooKeeper, Kafka, MinIO, Kafka UI) | **all containers healthy** |
| `scripts/health-check.sh` | **PASSED**: 28/28 |
| `scripts/smoke-test.sh` (full journeys incl. timeout refund + DLT replay) | **PASSED**: 78/78 |
| Manual: S3 upload/presigned URL via MinIO; checkout with inventory-service stopped; two parallel checkouts for the last unit (exactly one 201, one 409) | **PASSED** |
| `scripts/verify.sh` end to end on JDK 8 (prereqs → build → tests → up → health → smoke → Kafka lag 0 / Redis checks) | **PASSED**: 7 steps, perf step skipped (run separately ×3); report in `docs/verification/` |
| `perf/run-benchmark.sh` × 3 | **RUN**: results above (measured, not estimated) |
| Postman collection executed with newman (`scripts/postman-test.sh`), twice in a row | **PASSED**: 98 requests, 240 assertions, 0 failures |
| All 6 Mermaid diagrams rendered with mermaid-cli 11 (SVGs in `docs/diagrams/`) | **PASSED** |
| `docker compose config`, `bash -n` on all scripts, shellcheck (warnings level), JSON validation of Postman/IAM files | **PASSED** (1 minor shellcheck warning) |

**Not executed here** (documented and scripted only, they need your accounts or servers): AWS EC2/S3/IAM/ECR steps,
the Jenkins pipeline run, SonarQube analysis, and `docker compose --profile ci` (the SonarQube and Jenkins images
were not pulled).

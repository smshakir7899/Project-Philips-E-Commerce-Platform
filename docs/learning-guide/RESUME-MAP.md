# Resume Claim → Code Map

Use this file to find the code behind each line of the resume. Paths are relative to the repository root.
Abbreviations: `P` = `product-service/src/main/java/com/philips/ecommerce/product`, `I` = inventory, `C` = cart,
`O` = order, `PAY` = payment, `N` = notification, `CL` = `common-lib/src/main/java/com/philips/ecommerce/common`
(each one stands for `<module>/src/main/java/com/philips/ecommerce/<name>`).

---

## 1. Java 8

| What | Where |
|---|---|
| Source/bytecode level 1.8, `release 8` on newer JDKs | `pom.xml`: `<maven.compiler.source>`, profile `jdk9plus-release8` |
| Build fails if any dependency has Java 9+ bytecode | `pom.xml`: `maven-enforcer-plugin` → `enforceBytecodeVersion maxJdkVersion 1.8` |
| Java 8 runtime in containers | every `<module>/Dockerfile`: `FROM eclipse-temurin:8-jre` |
| Java 8 idioms: streams, `Optional`, lambdas, `java.time` | e.g. `O/service/CheckoutService.java`, `CL/dto/PageResponse.java#of(Page, Function)` |
| Why each library version | `docs/VERSION-MATRIX.md` |

## 2. Spring Boot, Spring MVC, REST APIs

| What | Where |
|---|---|
| REST controllers, status codes (201 + `Location`, 204, 402, 409) | `P/controller/ProductController.java`, `O/controller/OrderController.java` (checkout 201 vs 200 replay), `I/controller/ReservationController.java` |
| Request DTOs + Bean Validation (`@Valid`, `@NotBlank`, `@Pattern`, `@Digits`) | `P/dto/ProductRequest.java`, `O/dto/CheckoutRequest.java`, `C/dto/AddCartItemRequest.java`; path-variable validation via `@Validated` in `C/controller/CartController.java` |
| Pagination, filtering, sort whitelist | `P/dto/ProductSearchCriteria.java`, `P/dto/ProductSortField.java`, `CL/dto/PageResponse.java` |
| Global exception handling → uniform error body | `CL/web/GlobalExceptionHandler.java`, `CL/dto/ApiErrorResponse.java`, `CL/exception/*` |
| Custom Spring Boot auto-configuration (shared library) | `CL/web/CommonWebAutoConfiguration.java` + `common-lib/src/main/resources/META-INF/spring.factories` |
| Correlation id across services (MDC) | `CL/web/CorrelationIdFilter.java`, `CL/web/CorrelationIdFeignInterceptor.java`, `api-gateway/.../filter/CorrelationIdGlobalFilter.java`, `N/kafka/KafkaMdc.java` |
| Type-safe configuration | `@ConfigurationProperties` classes: `P/config/ProductProperties.java`, `P/config/StorageProperties.java`, `PAY/config/PaymentSimulationProperties.java` |

## 3. Swagger / OpenAPI

| What | Where |
|---|---|
| OpenAPI metadata bean | `*/config/OpenApiConfig.java` in every service |
| Endpoint docs (`@Tag`, `@Operation`, `@ApiResponse`) | all controllers |
| Swagger aggregated in the gateway | `config-repo/api-gateway.yml` (`springdoc.swagger-ui.urls`, `/docs/<service>/v3/api-docs` routes with `RewritePath`) |
| URLs | `http://localhost:8080/swagger-ui.html` (all services), `http://localhost:808x/swagger-ui.html` (one service) |

## 4. Spring Data JPA, Hibernate, MySQL

| What | Where |
|---|---|
| Entity mappings, `@ManyToOne(fetch = LAZY)`, `@OneToMany(orphanRemoval)` | `P/entity/Product.java`, `O/entity/Order.java`, `C/entity/Cart.java` |
| `@Embedded` value object | `O/entity/ShippingAddress.java` |
| Optimistic locking `@Version` → 409 `CONCURRENT_MODIFICATION` | `P/entity/Product.java`, `I/entity/Inventory.java`; handler in `CL/web/GlobalExceptionHandler.java` |
| Pessimistic locking `SELECT ... FOR UPDATE` | `I/repository/InventoryRepository.java` (`@Lock(PESSIMISTIC_WRITE)`), used by `I/service/ReservationService.java#reserve` |
| `FOR UPDATE SKIP LOCKED` (native query) | `O/outbox/OutboxEventRepository.java`, `PAY/outbox/OutboxEventRepository.java` |
| N+1 avoided: `@EntityGraph`, fetch join, `@BatchSize`, ID-first pagination | `P/repository/ProductRepository.java` (read the Javadoc on each method), `P/service/OptimizedProductReader.java`, `P/entity/Product.java` (`@BatchSize`) |
| N+1 present on purpose (baseline) | `P/service/BaselineProductQueryService.java` |
| Projection query (no entity loading) for order history | `O/repository/OrderRepository.java` |
| Transaction boundaries | `@Transactional` per local step in `O/service/OrderStateService.java`; orchestrator `O/service/CheckoutService.java` deliberately **not** transactional; `@Transactional(propagation = MANDATORY)` in `O/outbox/OutboxService.java#enqueue`; read-only transactions in `P/service/OptimizedProductReader.java` |
| Schema + indexes + seed data (Flyway) | `*/src/main/resources/db/migration/V*.sql`; product indexes with justification: `product-service/.../V2__performance_indexes.sql` |
| `ddl-auto: validate` + `open-in-view: false` | `config-repo/application.yml` |
| Statement counts as tests | `product-service/src/test/.../repository/ProductQueryStatementCountTest.java` |

## 5. Microservices with Spring Cloud

| What | Where |
|---|---|
| Config Server (native backend) | `config-server/` (`@EnableConfigServer`), files in `config-repo/`; clients use `spring.config.import: configserver:` in each `src/main/resources/application.yml` |
| Eureka discovery | `discovery-server/` (`@EnableEurekaServer`); clients: `eureka.client.*` in `config-repo/application.yml` |
| API Gateway (routing, `lb://`, retry, circuit breaker, fallback, CORS) | `config-repo/api-gateway.yml`, `api-gateway/.../controller/FallbackController.java` |
| OpenFeign clients | `C/client/ProductClient.java`, `O/client/*Client.java`; `@EnableFeignClients` on the application classes; timeouts under `feign.client.config` in `config-repo/order-service.yml` |
| Remote error decoding (keeps the remote error code) | `CL/feign/RemoteServiceErrorDecoder.java`, `CL/feign/RemoteServiceException.java` |
| Resilience4j retry + circuit breaker | `@Retry`/`@CircuitBreaker` in `O/client/InventoryServiceAdapter.java`, `O/client/PaymentServiceAdapter.java`, `C/client/ProductServiceAdapter.java`; config `resilience4j.*` in `config-repo/order-service.yml` and `cart-service.yml`; business 4xx excluded via `O/client/RemoteCallFailurePredicate.java` |

## 6. Business workflow and saga

| What | Where |
|---|---|
| Checkout orchestration (every branch) | `O/service/CheckoutService.java#checkout` |
| Order state machine | `O/entity/OrderStatus.java#canTransitionTo`, `O/entity/Order.java#transitionTo` |
| Idempotent checkout (`Idempotency-Key`) | `CheckoutService` (lookup + unique-constraint race), unique key in `V1__create_order_schema.sql` |
| Stock reservation (all-or-nothing, sorted locking) | `I/service/ReservationService.java#reserve` |
| Compensation: release / restock | `I/kafka/OrderEventHandler.java#onOrderCancelled`, `I/service/ReservationService.java#release` |
| Compensation: refund (incl. late success, tombstone) | `PAY/kafka/OrderCancellationHandler.java#handle`, `PAY/service/PaymentService.java#process` |
| Simulated payment gateway | `PAY/client/SimulatedPaymentGateway.java#charge` (tokens `tok_declined`, `tok_timeout`, ...) |
| Safety net for lost messages | `I/service/ReservationExpiryJob.java` (`@Scheduled`) |
| Diagrams | `docs/ARCHITECTURE.md` sections 2–4 |

## 7. Apache Kafka (ZooKeeper mode)

| What | Where |
|---|---|
| Broker + ZooKeeper + topic creation | `docker-compose.yml` (`zookeeper`, `kafka`, `kafka-init`), `docker/kafka/create-topics.sh` |
| Topic names (single source) | `CL/event/KafkaTopics.java` |
| Event DTOs | `CL/event/*Event.java` |
| Producer settings (`acks=all`, idempotence) | `config-repo/application.yml` → `spring.kafka.producer` |
| Topics as beans (partitions, replicas) | `*/config/KafkaTopicConfig.java` |
| Transactional outbox producer | `O/outbox/OutboxService.java#enqueue`, `O/outbox/OutboxPublisher.java#publishPending` (same in payment) |
| Keys for ordering | outbox `messageKey` = orderNumber |
| Consumers + consumer groups | `@KafkaListener` in `I/kafka/OrderEventListener.java`, `C/kafka/OrderConfirmedListener.java`, `PAY/kafka/OrderCancelledListener.java`, `N/kafka/NotificationEventListener.java` |
| Idempotent consumer (`processed_event`) | `I/kafka/OrderEventHandler.java`, `C/kafka/CartEventHandler.java`, `PAY/kafka/OrderCancellationHandler.java`; notification uses unique `event_id` |
| Retry + DLT | `I/config/KafkaConsumerConfig.java` (`DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `FixedBackOff`, non-retryable exceptions) |
| Central DLT store + replay | `N/kafka/DeadLetterListener.java`, `N/kafka/DltHeaders.java`, `N/service/FailedEventService.java#replay` |
| Demo of failure → DLT → replay | `scripts/smoke-test.sh` scenario 8, `N/service/SimulatedFailureRegistry.java` |
| Tests with embedded Kafka | `CheckoutFlowIntegrationTest`, `NotificationKafkaIntegrationTest`, `OrderCancelledKafkaIntegrationTest` (inventory), `PaymentKafkaIntegrationTest` |

## 8. Redis

| What | Where |
|---|---|
| Cache-aside get → miss → load → set with TTL | `P/service/ProductCacheService.java#getDetail`, `#getListPage` |
| Key design `product:v1:detail:{id}`, versioned list namespace | `ProductCacheService` (constants and `listKey`) |
| Invalidation after commit | `P/service/ProductCacheEvictor.java#evictAfterCommit`, called from `P/service/ProductCommandService.java` |
| Redis outage → fall back to MySQL | `ProductCacheService` (`errors` counter) |
| Hit/miss metrics + endpoints | `P/controller/CacheStatsController.java`, `P/controller/PerfStatsController.java` |
| Flush via `SCAN` (not `KEYS`) | `ProductCacheService#flushAll` |
| Integration test (real Redis + MySQL) | `product-service/src/test/.../ProductCatalogIT.java` |

## 9. Performance claim ("improved performance by 30%")

| What | Where |
|---|---|
| Baseline implementation | `P/service/BaselineProductQueryService.java` (`app.product.query-mode=baseline`, profile file `config-repo/product-service-baseline.yml`) |
| Optimized implementation | `P/service/OptimizedProductQueryService.java` + `OptimizedProductReader.java` + `ProductCacheService.java` |
| Index changes | `V2__performance_indexes.sql`, `perf/sql/drop-indexes.sql`, `perf/sql/create-indexes.sql`, `perf/sql/explain-queries.sql` |
| Benchmark harness | `perf/run-benchmark.sh` (scenarios S1–S5), `perf/bench.py` (load generator, p50/p95/p99), `perf/report.py` (formulas) |
| Methodology | `perf/README.md` |
| Measured results | `docs/PERFORMANCE-RESULTS.md` and `perf/results/<timestamp>/REPORT.md` |

## 10. AWS (EC2, S3)

| What | Where |
|---|---|
| S3 upload / delete / presigned GET with AWS SDK v2 | `P/service/storage/S3ProductImageStorage.java` (`store`, `delete`, `presignGet`) |
| Client config, endpoint override (MinIO), no hardcoded keys | `P/config/S3Config.java`, `P/config/StorageProperties.java` |
| Compensation if the DB write fails after upload | `P/service/ProductImageService.java` |
| Least-privilege IAM policy, EC2 trust policy | `deploy/iam/*.json` |
| Bucket hardening script | `deploy/aws/s3-bucket-setup.sh` |
| EC2 bootstrap + deploy | `deploy/ec2/user-data.sh`, `deploy/ec2/deploy.sh`, `deploy/docker-compose.prod.yml` |
| Security groups | `deploy/aws/security-groups.md` |
| Step-by-step guide | `docs/DEPLOYMENT-GUIDE.md` |

## 11. Docker, Jenkins, SonarQube, Maven

| What | Where |
|---|---|
| Dockerfiles (non-root, healthcheck, exec form) | `<module>/Dockerfile`, multi-stage alternative `docker/Dockerfile.multistage` |
| Compose: networks, volumes, health checks, startup ordering | `docker-compose.yml` (`depends_on.condition`, `mysql-init`, `kafka-init`, `minio-init`) |
| Jenkins pipeline | `Jenkinsfile` (header table: which stages need which credentials), `deploy/jenkins/README.md`, `docker/jenkins/` |
| SonarQube | `pom.xml` (`sonar.*` properties, exclusions, JaCoCo XML path), `sonar-project.properties`, `docker/sonarqube/README-quality-gate.md` |
| Maven multi-module, BOMs, plugin management, profiles | `pom.xml`, Maven Wrapper `mvnw` |

## 12. JUnit 5 and Mockito

| Kind | Examples |
|---|---|
| Pure unit tests with Mockito (`@Mock`, `verify`, `ArgumentCaptor`) | `order-service/src/test/.../service/CheckoutServiceTest.java` (one test per saga branch), `product-service/.../ProductCacheServiceTest.java` |
| Parameterized tests | `order-service/src/test/.../entity/OrderStatusTest.java` |
| `@WebMvcTest` controller slices | `ProductControllerTest`, `OrderControllerTest`, `CartControllerWebTest`, `InventoryControllersWebTest`, `PaymentControllerTest` |
| `@DataJpaTest` on H2 | `ProductQueryStatementCountTest`, `OrderRepositoryTest`, inventory `RepositoryQueriesTest` |
| WireMock (Feign end to end) | `FeignAdaptersWireMockTest` (cart), `CheckoutFlowIntegrationTest` (order) |
| Testcontainers (real MySQL 8, Redis 7, ZooKeeper-mode Kafka) | `ProductCatalogIT` (MySQL + Redis cache-aside), `CheckoutWorkflowMySqlKafkaIT` (saga + outbox `SKIP LOCKED` + Kafka ordering), `InventoryMySqlKafkaIT` (row-lock race, duplicate events), `CartMySqlIT` (`@Version`), `PaymentMySqlIT` (two publishers + `SKIP LOCKED`), `NotificationMySqlIT` (index in `EXPLAIN`); run with `./mvnw -Pintegration-tests verify` |
| Counts | `./mvnw test` → 387 tests; `-Pintegration-tests` adds 22; `scripts/postman-test.sh` → 240 API assertions |

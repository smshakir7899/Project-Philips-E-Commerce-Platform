# Study Path (about 10 sessions)

Each step has **read** (code), **run** (an observable behaviour) and **explain** (say it out loud as if in an interview).

### 1. Build and Java 8 enforcement
* Read: `pom.xml` (parent, BOM imports, `pluginManagement`, enforcer, `jdk9plus-release8` profile).
* Run: `./mvnw -B -pl common-lib verify`. Then add `List.of(1)` to any class and build on JDK 17: it fails
  because of `--release 8`. Revert the change.
* Explain: why `-source/-target 8` alone does not stop Java 9 API usage, and what `enforceBytecodeVersion` checks.

### 2. One service end to end: product-service
* Read: `ProductController` → `ProductQueryService` → `OptimizedProductReader` → `ProductRepository` → `Product` entity → `V1`/`V2` migrations.
* Run: `curl localhost:8081/api/products/1` twice, then `curl localhost:8081/api/products/admin/perf/stats`
  (statements: 2 for the miss, 0 for the hit).
* Explain: lazy vs eager loading, N+1, `@EntityGraph`, why a collection fetch join breaks pagination, `readOnly` transactions.

### 3. Errors and validation
* Read: `common-lib` `GlobalExceptionHandler`, `BusinessException`, `CommonWebAutoConfiguration`, `spring.factories`.
* Run: `curl -X POST localhost:8080/api/carts/CUST-1/items -H 'Content-Type: application/json' -d '{"productId":2,"quantity":0}'`.
* Explain: how auto-configuration registers beans without component scan, and why every service returns the same error shape.

### 4. Spring Cloud plumbing
* Read: `config-repo/application.yml`, a service `application.yml` (`spring.config.import`), `config-repo/api-gateway.yml`.
* Run: `curl localhost:8888/order-service/default | jq '.propertySources[].name'`; open the Eureka dashboard on :8761.
  Run `docker compose stop cart-service`, watch the gateway return 503 `CART_SERVICE_UNAVAILABLE`, then start it again.
* Explain: client-side load balancing (`lb://`), why config placeholders are resolved by the client, fail-fast + retry.

### 5. Synchronous calls and resilience
* Read: `order-service/.../client/*Client.java`, `InventoryServiceAdapter`, `RemoteCallFailurePredicate`, `RemoteServiceErrorDecoder`, `config-repo/order-service.yml` (feign + resilience4j).
* Run: stop inventory-service and check out → 503 `INVENTORY_SERVICE_UNAVAILABLE`; check `localhost:8084/actuator/circuitbreakers`.
* Explain: retry vs circuit breaker, why 4xx must not open the circuit, and why idempotency makes retries safe.

### 6. The saga
* Read: `CheckoutService#checkout` top to bottom, `OrderStateService`, `OrderStatus`.
* Run: `scripts/smoke-test.sh --verbose` and follow one order through `/tracking`.
* Explain: why there is no `@Transactional` on the orchestrator, what is compensated where, and the `tok_timeout`
  "unknown outcome" case.

### 7. Kafka
* Read: `OutboxService`, `OutboxPublisher`, `inventory-service/.../kafka/OrderEventListener` + `OrderEventHandler`, `KafkaConsumerConfig`.
* Run: Kafka UI http://localhost:8090 → topics → `order-confirmed` → messages (key = orderNumber, headers).
  `docker compose exec kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --all-groups`.
* Explain: partitions and ordering, consumer groups, at-least-once delivery, idempotent consumers, the outbox.

### 8. Failure handling: DLT and replay
* Read: `notification-service/.../kafka/DeadLetterListener`, `DltHeaders`, `FailedEventService#replay`.
* Run: smoke-test scenario 8, or by hand: `PUT /api/notifications/admin/simulated-failures`, check out, list failed events, replay.
* Explain: retry with backoff, non-retryable exceptions, why replay is safe for every consumer group.

### 9. Concurrency in the database
* Read: `InventoryRepository` (`@Lock`), `ReservationService#reserve` (sorted ids), `@Version` in `Inventory`/`Product`.
* Run: two parallel checkouts for the last unit of a product (set the stock to 1 with `PUT /api/inventory/{id}`). Exactly one succeeds.
* Explain: pessimistic vs optimistic locking, deadlock avoidance through lock ordering, `SKIP LOCKED` in the outbox.

### 10. Performance and operations
* Read: `perf/README.md`, `BaselineProductQueryService` vs `OptimizedProductQueryService`, `ProductCacheService`,
  `docs/PERFORMANCE-RESULTS.md` section 4.
* Run: `perf/run-benchmark.sh --scenarios "S1 S3 S5"`, and `perf/sql/explain-queries.sql` with and without indexes.
* Then: `Dockerfile`, `docker-compose.yml` (healthchecks, `depends_on`), `Jenkinsfile`, `docs/DEPLOYMENT-GUIDE.md`.
* Explain: how you measured the improvement, why indexes alone made it worse, cache invalidation after commit, and IAM roles vs access keys.

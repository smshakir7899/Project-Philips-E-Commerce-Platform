# Interview Questions, Answered With This Codebase

Short model answers. Each one points at the code that proves it.

## Spring Boot / REST
**Q: How do you return consistent errors across microservices?**
One `@RestControllerAdvice` (`common-lib/.../GlobalExceptionHandler`) shipped as Spring Boot auto-configuration
(`META-INF/spring.factories`). Domain errors extend `BusinessException` (status + machine code). Feign clients decode
remote errors back into `RemoteServiceException`, so a 409 `INSUFFICIENT_STOCK` keeps its meaning across hops.

**Q: 200 vs 201 vs 202 vs 402 vs 409?**
`OrderController`: 201 for a new confirmed order (with `Location`), 200 for an idempotent replay, 402 for a declined
payment, 409 for insufficient stock or an invalid state transition, 503 when a dependency is down. 202 would fit an
asynchronous checkout API; this design answers synchronously.

**Q: What does `@Transactional` do, and when does it not work?**
A proxy opens and commits the transaction around the method. It does **not** apply to self-invocation (calling
`this.method()`), private methods, or exceptions you catch. By default it rolls back on unchecked exceptions only.
That is why `OrderStateService` is a separate bean from `CheckoutService`. See also `propagation = MANDATORY` in
`OutboxService#enqueue`.

## JPA / Hibernate / MySQL
**Q: What is the N+1 problem and how did you fix it?**
Loading N products and then touching a lazy association fires one query per product. Baseline:
`BaselineProductQueryService` (about 17.6 statements per request). Fix: `@EntityGraph(attributePaths = "category")`, one
batched thumbnail query for the page ids, `@BatchSize` for attributes. Result: 2.9 statements per request (proved by
`ProductQueryStatementCountTest` and the benchmark).

**Q: Why not `JOIN FETCH` the images in the paged list query?**
A collection fetch join multiplies rows, so Hibernate cannot apply SQL `LIMIT`. It warns `HHH000104` and paginates
in memory. Fetching two bags at once throws `MultipleBagFetchException`. See the Javadoc in `ProductRepository`.

**Q: Optimistic vs pessimistic locking?**
`@Version` (optimistic) for rare admin edits returns 409 `CONCURRENT_MODIFICATION` on conflict.
`@Lock(PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`) for stock reservation, which is high-contention and must never
oversell. Rows are locked in sorted product-id order to avoid deadlocks (`ReservationService#reserve`).

**Q: How do you choose an index?**
From the query: equality columns first, then the range or sort column, e.g. `(category_id, active, price)` for
"category X, price between, order by price" (`V2__performance_indexes.sql`). Verify with `EXPLAIN ANALYZE`. Our
benchmark shows the opposite effect too: a low-selectivity `(active, name)` index made a `LIKE '%x%'` query *slower*
(`docs/PERFORMANCE-RESULTS.md`, section 4).

## Microservices / Spring Cloud
**Q: How do services find each other?**
Eureka (`discovery-server`). Clients register themselves, and Feign or the gateway resolve `lb://service-name`
through Spring Cloud LoadBalancer (client-side load balancing).

**Q: Why a Config Server?**
One place for configuration (`config-repo/`), environment-specific values through `${ENV:default}` placeholders
resolved by each client, and the option of `/actuator/refresh`. Services `fail-fast` with retry if it is not reachable.

**Q: Retry vs circuit breaker?**
Retry handles transient faults: `feign.RetryableException`, i.e. timeouts and refused connections. It is only safe
for idempotent operations. Here reservation and payment are idempotent by key. A circuit breaker stops calling a
failing dependency so threads are not tied up; it fails fast with 503. Business 4xx responses are excluded
(`RemoteCallFailurePredicate`).

**Q: How do you keep data consistent across services without distributed transactions?**
A saga (`CheckoutService`), where each step is a local transaction with a compensating action (release stock, refund),
plus a transactional outbox (no dual write), idempotent consumers, and a reservation-expiry safety net.

**Q: Payment timed out. Was the customer charged?**
Unknown, so the order is failed and `order-cancelled` (PAYMENT_ERROR) is published. payment-service refunds if the
payment succeeded, marks `cancelRequested` if it is still processing (a late success is refunded immediately), or
stores a tombstone so a late-arriving request is refused (`OrderCancellationHandler`, `PaymentService`). Demonstrated
by `tok_timeout` in the smoke test.

## Kafka
**Q: How is ordering guaranteed?**
Only within a partition. The key is `orderNumber`, so all events of one order land in the same partition. The outbox
publisher also holds back later events of an order whose earlier event failed to send.

**Q: What happens if a consumer crashes after processing but before committing the offset?**
The record is redelivered (at-least-once). The handler checks `processed_event` in the same DB transaction as the
business change, so the second delivery has no effect.

**Q: Poison message?**
`DefaultErrorHandler` retries 3 times with a 1 s backoff, then `DeadLetterPublishingRecoverer` sends the record to
`<topic>.DLT`. Deserialization errors skip the retries. notification-service stores every DLT record (`FailedEvent`)
and can replay it.

**Q: Why ZooKeeper?**
This is the classic architecture: ZooKeeper stores cluster metadata and handles controller election. Kafka 3.3+
offers KRaft (no ZooKeeper), and Kafka 4.0 removed ZooKeeper. The Confluent 7.4 images used here support both modes.

## Redis
**Q: Explain cache-aside.**
Read: check Redis, on a miss load from MySQL and `SET` with a TTL. Write: update MySQL, then invalidate.
`ProductCacheService#getDetail`.

**Q: How do you invalidate list caches without `KEYS *`?**
Every list key contains a version (`product:v1:list:{version}:{hash}`). A write does `INCR product:v1:list-version`,
which makes old keys unreachable in O(1). They then expire through their TTL.

**Q: Why invalidate after commit?**
If you evict before commit, a concurrent reader can reload the old row and cache it for the whole TTL.
`ProductCacheEvictor` registers a `TransactionSynchronization#afterCommit`.

**Q: What if Redis is down?**
Reads fall back to MySQL and an `errors` counter is incremented; Redis is never the source of truth.

## AWS / DevOps
**Q: How does the app authenticate to S3 on EC2?**
Through the instance profile (IAM role). The AWS SDK `DefaultCredentialsProvider` finds it via IMDSv2, so no keys
exist anywhere. The policy is least privilege, scoped to `products/*` (`deploy/iam/product-service-s3-policy.json`).

**Q: Public bucket?**
No. Block Public Access is on. Clients get presigned URLs (`S3ProductImageStorage#presignGet`) or go through
CloudFront with Origin Access Control. Only local MinIO is publicly readable.

**Q: Walk through your pipeline.**
`Jenkinsfile`: checkout → compile (enforcer) → unit tests + JaCoCo → optional Testcontainers ITs → SonarQube +
quality gate → package → Docker build → push to the registry → SSH deploy to EC2 (`deploy.sh` pulls, starts,
health-checks, records the tag for rollback).

**Q: Why `exec java` in the Dockerfile ENTRYPOINT?**
So Java becomes PID 1 and receives SIGTERM, which lets Spring shut down gracefully. Without `exec` the shell receives
the signal and the JVM is killed after the timeout.

## Testing
**Q: Unit vs slice vs integration tests?**
Unit: Mockito, no Spring (`CheckoutServiceTest`). Slice: `@WebMvcTest` (`OrderControllerTest`), `@DataJpaTest`
(`OrderRepositoryTest`). Integration in-process: `@SpringBootTest` + `@EmbeddedKafka` + WireMock
(`CheckoutFlowIntegrationTest`). Real infrastructure: Testcontainers MySQL + Redis (`ProductCatalogIT`).

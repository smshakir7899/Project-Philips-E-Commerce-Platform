# Version Matrix (Java 8 compatible)

| Component | Version | Why this version |
|---|---|---|
| Java (source/bytecode) | **1.8** | Resume requirement. Enforced by `maven-compiler-plugin` (`source/target 1.8`, plus `release 8` when building on JDK 9+) and by `maven-enforcer-plugin` + `extra-enforcer-rules:enforceBytecodeVersion` (fails if any runtime dependency ships class files newer than Java 8). |
| Java runtime in Docker | `eclipse-temurin:8-jre` | Same major version as the bytecode, so the containers really run on Java 8. |
| Spring Boot | **2.7.18** | Last Spring Boot line supporting Java 8 (Boot 3.x needs Java 17). |
| Spring Framework | 5.3.31 (managed by Boot) | Java 8 baseline. |
| Spring Cloud | **2021.0.9** (Jubilee) | The release train compatible with Boot 2.7. Contains Config 3.1, Netflix Eureka 3.1, OpenFeign 3.1, Gateway 3.1, LoadBalancer 3.1, CircuitBreaker 2.1. |
| Hibernate ORM | 5.6.15.Final (managed) | JPA 2.2 (`javax.persistence`), Java 8. |
| MySQL server | 8.0 (Docker `mysql:8.0`) | `FOR UPDATE SKIP LOCKED`, recursive CTEs, FULLTEXT on InnoDB, `EXPLAIN ANALYZE`. |
| MySQL JDBC | `com.mysql:mysql-connector-j` 8.0.33 (managed) | |
| Flyway | 8.5.13 (managed) + `flyway-mysql` | Java 8 compatible. Since Flyway 8.2 MySQL support lives in the separate `flyway-mysql` artifact; without it startup fails with "Unsupported Database". |
| Redis server / client | 7 (`redis:7-alpine`) / Lettuce 6.1 via Spring Data Redis 2.7 | |
| Apache Kafka broker | Confluent Platform **7.4.4** (`cp-kafka`, `cp-zookeeper`) = Apache Kafka 3.4 | 7.4 is a ZooKeeper-capable release (ZooKeeper mode was removed in Kafka 4.0 / CP 8.0). |
| Kafka client / Spring Kafka | kafka-clients 3.1.2 / spring-kafka 2.8.11 (managed by Boot 2.7.18) | Clients 3.1 talk to 3.4 brokers (forward/backward compatible protocol). |
| springdoc-openapi | **1.8.0** (`springdoc-openapi-ui`, `-webflux-ui`) | The 1.x line is for Boot 2.x; 2.x requires Boot 3. |
| Resilience4j | **1.7.0** (`resilience4j-spring-boot2`) | Must equal the version Spring Cloud 2021.0.9 brings for the other resilience4j jars (mixing 1.7.1 with 1.7.0 causes `NoSuchMethodError`). 2.x requires Java 17. |
| AWS SDK for Java | **v2 2.29.52** (`software.amazon.awssdk:s3`) | SDK v2 supports Java 8; the enforcer bytecode rule verifies the selected release. |
| MinIO | `bitnamilegacy/minio:2025.4.22` (MinIO RELEASE.2025-04-22, bundles `mc`) | S3-compatible local storage; no AWS account needed locally. MinIO Inc. stopped publishing `minio/minio` / `minio/mc` images in 2025, so a frozen redistribution is used. |
| Lombok | 1.18.36 | Works on JDK 8 through 21 compilers. |
| JUnit / Mockito / AssertJ | JUnit 5.8.2, Mockito 4.5.1, AssertJ 3.22 (managed by Boot) | Mockito 4.x is the last line supporting Java 8. |
| Testcontainers | **1.21.4** | Last 1.x; Java 8 compatible; contains the fix for Docker Engine 29 API version negotiation. |
| WireMock | via `spring-cloud-contract-wiremock` 3.1.x (managed by Spring Cloud) | |
| JaCoCo | 0.8.11 | |
| Maven | 3.9.9 (Maven Wrapper included) | Needs Java 8+. |
| SonarQube | 10.x Community (Docker, profile `ci`) | Scanner needs JDK 17 **runtime**; analysed bytecode stays Java 8 (`sonar.java.source=8`). |
| Jenkins | `jenkins/jenkins:lts-jdk17` | Controller JVM is independent from the project JDK; the pipeline uses a `jdk8` tool for builds. |
| Docker Engine / Compose | 24+ / Compose v2 | Compose file uses `depends_on.condition` and profiles. |

## Things that would break Java 8 (and are avoided)
* Spring Boot 3 / Spring 6 / Jakarta EE 9 (`jakarta.*` packages), springdoc 2.x, Resilience4j 2.x, Mockito 5, Testcontainers 2.x, Flyway 9+ MySQL module.
* Language/API: `var`, records, text blocks, switch expressions, `List.of`, `Map.of`, `Optional.isEmpty`, `String.isBlank/strip/repeat/lines`, `Stream.toList`, `HttpClient` (java.net.http).
* Compiling on JDK 17 with only `-source 8 -target 8` does **not** catch Java 9+ API usage; the root POM switches to `--release 8` automatically on JDK 9+.

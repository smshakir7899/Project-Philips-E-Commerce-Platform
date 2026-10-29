# =============================================================================================
# cart-service runtime image (port 8083).
#
# Build:  ./mvnw -B -pl cart-service -am package -DskipTests      (produces cart-service/target/cart-service.jar)
#         docker compose build cart-service                          (or: docker build -t philips-ecommerce/cart-service:local cart-service)
# The jar is built on the host / CI agent and only COPIED here, which keeps the image small and
# the build fast. See docker/Dockerfile.multistage for a variant that compiles inside Docker.
# =============================================================================================

# Java 8 JRE (not JDK): the bytecode is Java 8 (maven-enforcer checks it), so the runtime matches
# exactly; a JRE has no compiler/jdk tools -> smaller image and attack surface. Temurin publishes
# multi-arch images, so the same tag works on Apple Silicon (arm64) and on x86_64 EC2 instances.
# Pin a digest or a full tag (e.g. 8u504-b01-jre) in a real pipeline for reproducible builds.
FROM eclipse-temurin:8-jre

LABEL org.opencontainers.image.title="cart-service" \
      org.opencontainers.image.source="philips-ecommerce" \
      org.opencontainers.image.description="Philips e-commerce learning project - cart-service"

# Run as an unprivileged system user (uid < 1000; uid 1000 is already taken by 'ubuntu' in this
# Ubuntu-based image). If the app is compromised it cannot modify the image or bind ports < 1024.
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --chown=app:app target/cart-service.jar app.jar
USER app

EXPOSE 8083

# Small heap for a laptop running ~9 JVMs (+ MySQL, Kafka, ...) within 8 GB:
#  -Xms128m -Xmx256m : bounded heap; the container mem_limit (400m) leaves room for metaspace,
#                      thread stacks, code cache and direct buffers (heap is NOT the whole RSS).
#  -XX:+UseSerialGC  : single-threaded collector with the lowest memory/CPU overhead; ideal for
#                      small heaps and containers with ~1 CPU (G1 would add footprint for nothing).
# 8u191+ is container-aware, so -XX:MaxRAMPercentage=75 is an alternative to a fixed -Xmx.
# Override at runtime:  docker run -e JAVA_OPTS="-Xmx512m" ...
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseSerialGC" \
    TZ=UTC

# Docker marks the container healthy only when Actuator reports UP (HTTP 200; DOWN => 503 => curl
# -f fails). docker compose 'depends_on: condition: service_healthy' relies on this.
# start-period: Spring Boot + config-server fetch + Flyway need time; failures during it don't count.
# curl is present in eclipse-temurin (Ubuntu) images - verified, no extra package needed.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8083/actuator/health > /dev/null || exit 1

# Exec form + "exec" inside sh -c:
#  - sh -c is needed so $JAVA_OPTS is expanded (exec form alone does no variable expansion);
#  - "exec" REPLACES the shell with the JVM, so java becomes PID 1 and receives SIGTERM from
#    "docker stop" directly -> Spring Boot shuts down gracefully (finishes requests, commits
#    Kafka offsets, closes the Hikari pool) instead of being SIGKILLed after the 10 s timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

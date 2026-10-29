package com.philips.ecommerce.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Centralised configuration for every business service and the api-gateway.
 *
 * <p>Clients call {@code GET /{application}/{profile}} (e.g. {@code /order-service/default}) during startup via
 * {@code spring.config.import: configserver:...}. The server answers with an ordered list of property sources:
 * {@code <application>-<profile>.yml}, {@code <application>.yml}, then {@code application.yml} (shared defaults).
 * The CLIENT merges them, which is why {@code ${VAR:default}} placeholders in config-repo are resolved with the
 * client's own environment and not the server's.</p>
 *
 * <p>The server is deliberately NOT a Eureka client. Business services must locate it before they can read
 * anything else (including the Eureka URL itself, which lives in config-repo). If config-server also depended on
 * Eureka we would have a bootstrap chicken-and-egg problem and an extra failure mode at startup. A fixed URL
 * ({@code CONFIG_SERVER_URL}) keeps the startup order simple: config-server -> discovery-server -> services.</p>
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}

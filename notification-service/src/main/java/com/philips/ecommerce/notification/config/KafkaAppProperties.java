package com.philips.ecommerce.notification.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.Min;

/**
 * Binds the shared {@code app.kafka.*} block of config-repo/application.yml. Named KafkaAppProperties to avoid
 * confusion with Spring Boot's own {@code KafkaProperties} ({@code spring.kafka.*}).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaAppProperties {

    @Valid
    private Topic topic = new Topic();

    @Valid
    private Retry retry = new Retry();

    @Data
    public static class Topic {
        @Min(1)
        private int partitions = 3;
        @Min(1)
        private short replicas = 1;
    }

    @Data
    public static class Retry {
        /** Pause between two delivery attempts of the same record. */
        @Min(0)
        private long intervalMs = 1000;
        /** Number of RETRIES after the first failed attempt (FixedBackOff semantics): 3 -> 4 deliveries in total. */
        @Min(0)
        private long maxAttempts = 3;
    }
}

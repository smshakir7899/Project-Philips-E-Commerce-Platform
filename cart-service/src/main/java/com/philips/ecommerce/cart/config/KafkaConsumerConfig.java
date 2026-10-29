package com.philips.ecommerce.cart.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling for all @KafkaListener methods. Spring Boot 2.7 applies a single CommonErrorHandler bean to the
 * auto-configured listener container factory, so declaring the bean is enough.
 *
 * <p>Flow for a failing record: the container re-seeks the partition and redelivers it
 * {@code max-attempts - 1} more times with a fixed back-off (blocking retry: later records of that partition wait,
 * which preserves per-order ordering). When attempts are exhausted the DeadLetterPublishingRecoverer publishes the
 * original record (+ exception headers) to {@code <topic>.DLT}, the offset is committed and consumption continues.
 * notification-service stores DLT records and offers a replay endpoint.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate, AppKafkaProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // FixedBackOff's second argument is the number of RETRIES (after the first attempt), hence "- 1":
        // app.kafka.retry.max-attempts=3 -> 1 delivery + 2 retries.
        long retries = Math.max(0, properties.getRetry().getMaxAttempts() - 1);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(properties.getRetry().getIntervalMs(), retries));

        // Deterministic failures go straight to the DLT: retrying a malformed payload or a business rule
        // violation would fail identically every time and only delay the partition.
        // Transient failures (DB down, lock wait timeout, deadlock victim, optimistic lock) are retried.
        errorHandler.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                ResourceNotFoundException.class,
                ConflictException.class);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Delivery attempt {} failed for topic={} partition={} offset={}: {}", deliveryAttempt,
                        record.topic(), record.partition(), record.offset(), ex.getMessage()));
        return errorHandler;
    }
}

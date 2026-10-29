package com.philips.ecommerce.payment.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling for @KafkaListener containers.
 * <p>
 * Spring Boot 2.7 automatically plugs a single {@code CommonErrorHandler} bean into its auto-configured
 * {@code ConcurrentKafkaListenerContainerFactory}, so declaring the bean is enough.
 * <p>
 * Behaviour: a failing record is redelivered (in-memory, same consumer) {@code max-attempts} more times with
 * {@code interval-ms} pause; then it is published to {@code <topic>.DLT} and the offset is committed so the
 * partition is not blocked by a poison message. notification-service stores DLT records for inspection/replay.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 @Value("${app.kafka.retry.interval-ms:1000}") long intervalMs,
                                                 @Value("${app.kafka.retry.max-attempts:3}") long maxAttempts) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        // FixedBackOff's second argument is the number of RETRIES after the first delivery.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, maxAttempts));
        // Retrying cannot fix a malformed payload or an invalid event: send those to the DLT immediately.
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }
}

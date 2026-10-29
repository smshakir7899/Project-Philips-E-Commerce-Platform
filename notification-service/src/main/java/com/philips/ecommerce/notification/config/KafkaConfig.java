package com.philips.ecommerce.notification.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.philips.ecommerce.notification.kafka.EventTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka listener infrastructure. Two container factories with DIFFERENT error handling:
 *
 * <ul>
 *     <li>{@code kafkaListenerContainerFactory} (default, used by the five business listeners):
 *         {@link DefaultErrorHandler} retries a failing record in place ({@code FixedBackOff(interval, maxAttempts)},
 *         i.e. 1 + maxAttempts deliveries), then the {@link DeadLetterPublishingRecoverer} publishes it to
 *         {@code <topic>.DLT} (same partition) with diagnostic headers and the offset is committed, so one poison
 *         record cannot block its partition forever. Exceptions that can never succeed on retry (unparseable JSON,
 *         missing ids) skip the retries and go straight to the DLT.</li>
 *     <li>{@code dltKafkaListenerContainerFactory} (only the dead-letter listener): retries a few times (e.g. the
 *         database is briefly unavailable), then only LOGS. It must never publish to a DLT itself - a failing
 *         dead-letter consumer would otherwise create {@code <topic>.DLT.DLT} records, or loop forever.</li>
 * </ul>
 *
 * <p>Both factories are built with Spring Boot's {@link ConcurrentKafkaListenerContainerFactoryConfigurer}, so the
 * {@code spring.kafka.listener.*} properties (ack-mode record, concurrency 3, missing-topics-fatal false) still apply.
 * Declaring a bean named {@code kafkaListenerContainerFactory} makes Boot's own default factory back off.</p>
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    public static final String DLT_CONTAINER_FACTORY = "dltKafkaListenerContainerFactory";

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<?, ?> kafkaTemplate,
            KafkaAppProperties kafkaProperties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(retryThenDeadLetterErrorHandler(kafkaTemplate, kafkaProperties));
        return factory;
    }

    @Bean(name = DLT_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> dltKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaAppProperties kafkaProperties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(retryThenLogErrorHandler(kafkaProperties));
        return factory;
    }

    /**
     * Creates the five dead-letter topics at startup (KafkaAdmin skips topics that already exist).
     *
     * <p>Only the DLTs are declared here: this service owns the dead-letter pipeline, and the broker runs with
     * {@code auto.create.topics.enable=false}, so a recoverer publishing to a missing DLT would fail and the record
     * would be retried forever. The MAIN topics are owned by their producers (order-service, payment-service) and by
     * the infrastructure script; a consumer declaring them could silently create them with the wrong settings.</p>
     *
     * <p>Partition count = main topics' count (3), because the recoverer's default destination is the SAME partition
     * number as the failed record.</p>
     */
    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics(KafkaAppProperties kafkaProperties) {
        String[] names = EventTopics.deadLetterTopics();
        NewTopic[] topics = new NewTopic[names.length];
        for (int i = 0; i < names.length; i++) {
            topics[i] = TopicBuilder.name(names[i])
                    .partitions(kafkaProperties.getTopic().getPartitions())
                    .replicas(kafkaProperties.getTopic().getReplicas())
                    .build();
        }
        return new KafkaAdmin.NewTopics(topics);
    }

    static DefaultErrorHandler retryThenDeadLetterErrorHandler(KafkaTemplate<?, ?> kafkaTemplate,
                                                               KafkaAppProperties kafkaProperties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff(kafkaProperties));
        // Deterministic failures: retrying cannot help, dead-letter immediately.
        // (Deserialization exceptions are already non-retryable by default.)
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        // Each in-place retry is logged ("Seek to current after exception"). A retry is expected behaviour, not an
        // error; the real alarm is the record arriving on the DLT (logged at WARN by FailedEventService).
        errorHandler.setLogLevel(KafkaException.Level.WARN);
        return errorHandler;
    }

    static DefaultErrorHandler retryThenLogErrorHandler(KafkaAppProperties kafkaProperties) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((ConsumerRecord<?, ?> record, Exception exception) ->
                log.error("Could not store dead letter {}-{}@{} (key {}) after retries - skipped. Payload: {}",
                        record.topic(), record.partition(), record.offset(), record.key(), record.value(), exception),
                backOff(kafkaProperties));
        errorHandler.setLogLevel(KafkaException.Level.WARN);
        return errorHandler;
    }

    private static FixedBackOff backOff(KafkaAppProperties kafkaProperties) {
        return new FixedBackOff(kafkaProperties.getRetry().getIntervalMs(), kafkaProperties.getRetry().getMaxAttempts());
    }
}

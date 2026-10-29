package com.philips.ecommerce.payment.config;

import com.philips.ecommerce.common.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics this service PRODUCES (including the dead-letter topic of the topic it consumes).
 * Spring Boot's KafkaAdmin creates missing NewTopic beans at startup; that matters because the broker runs with
 * auto.create.topics.enable=false. Declaring the same topic in several services is harmless (creation is idempotent).
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

    private final int partitions;
    private final int replicas;

    public KafkaTopicConfig(@Value("${app.kafka.topic.partitions:3}") int partitions,
                            @Value("${app.kafka.topic.replicas:1}") int replicas) {
        this.partitions = partitions;
        this.replicas = replicas;
    }

    @Bean
    public NewTopic paymentProcessedTopic() {
        return topic(KafkaTopics.PAYMENT_PROCESSED);
    }

    @Bean
    public NewTopic paymentRefundedTopic() {
        return topic(KafkaTopics.PAYMENT_REFUNDED);
    }

    /**
     * DeadLetterPublishingRecoverer writes to the SAME partition number of the DLT as the failed record had,
     * so the DLT needs at least as many partitions as order-cancelled.
     */
    @Bean
    public NewTopic orderCancelledDeadLetterTopic() {
        return topic(KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX);
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}

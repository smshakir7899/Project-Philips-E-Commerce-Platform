package com.philips.ecommerce.order.config;

import com.philips.ecommerce.common.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics produced by order-service. The broker runs with auto.create.topics.enable=false, so topics must exist
 * explicitly: Spring Boot's KafkaAdmin creates every NewTopic bean at startup if it is missing (idempotent).
 * 3 partitions: up to 3 consumer instances per group can share the load; the key (orderNumber) keeps the events of
 * one order in one partition, so they are consumed in order.
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
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CREATED).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic orderConfirmedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CONFIRMED).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CANCELLED).partitions(partitions).replicas(replicas).build();
    }
}

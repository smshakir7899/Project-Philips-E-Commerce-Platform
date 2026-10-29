package com.philips.ecommerce.inventory.config;

import com.philips.ecommerce.common.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares the topics this service consumes plus their dead-letter topics. Spring Boot's KafkaAdmin creates
 * missing ones at startup (the broker runs with auto.create.topics.enable=false); existing topics are left alone.
 *
 * <p>The .DLT topics MUST have at least as many partitions as the source topic: by default
 * DeadLetterPublishingRecoverer writes a failed record to the SAME partition number in the DLT.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.kafka.topic", name = "auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics inventoryConsumedTopics(AppKafkaProperties properties) {
        return new KafkaAdmin.NewTopics(
                topic(KafkaTopics.ORDER_CONFIRMED, properties),
                topic(KafkaTopics.ORDER_CONFIRMED + KafkaTopics.DLT_SUFFIX, properties),
                topic(KafkaTopics.ORDER_CANCELLED, properties),
                topic(KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX, properties));
    }

    private static NewTopic topic(String name, AppKafkaProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.getTopic().getPartitions())
                .replicas(properties.getTopic().getReplicas())
                .build();
    }
}

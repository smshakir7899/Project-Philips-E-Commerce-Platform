package com.philips.ecommerce.cart.config;

import com.philips.ecommerce.common.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares the consumed topic and its dead-letter topic; Spring Boot's
 * KafkaAdmin creates them if missing (the broker has
 * auto.create.topics.enable=false). The DLT needs the same partition count
 * because DeadLetterPublishingRecoverer writes to the same partition number as
 * the failed record.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.kafka.topic", name = "auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

	@Bean
	public KafkaAdmin.NewTopics cartConsumedTopics(AppKafkaProperties properties) {
		return new KafkaAdmin.NewTopics(topic(KafkaTopics.ORDER_CONFIRMED, properties),
				topic(KafkaTopics.ORDER_CONFIRMED + KafkaTopics.DLT_SUFFIX, properties));
	}

	private static NewTopic topic(String name, AppKafkaProperties properties) {
		return TopicBuilder.name(name).partitions(properties.getTopic().getPartitions())
				.replicas(properties.getTopic().getReplicas()).build();
	}
}

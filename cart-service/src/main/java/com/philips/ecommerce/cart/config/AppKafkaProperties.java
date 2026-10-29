package com.philips.ecommerce.cart.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;

/**
 * Kafka settings shared by all services, bound from {@code app.kafka.*}
 * (config-repo/application.yml). Named AppKafkaProperties to avoid confusion
 * with Spring Boot's own KafkaProperties ({@code spring.kafka.*}).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.kafka")
public class AppKafkaProperties {

	private final Topic topic = new Topic();
	private final Retry retry = new Retry();

	@Getter
	@Setter
	public static class Topic {
		@Min(1)
		private int partitions = 3;
		@Min(1)
		private short replicas = 1;
		/**
		 * Declare NewTopic beans so KafkaAdmin creates missing topics (the broker has
		 * auto-create disabled).
		 */
		private boolean autoCreate = true;
	}

	@Getter
	@Setter
	public static class Retry {
		@Min(0)
		private long intervalMs = 1000;
		/**
		 * Total delivery attempts (first try + retries) before the record goes to the
		 * dead-letter topic.
		 */
		@Min(1)
		private int maxAttempts = 3;
	}
}

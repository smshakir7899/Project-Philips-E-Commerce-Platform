package com.philips.ecommerce.inventory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Scheduled processing only when the expiry job is enabled ({@code app.inventory.expiry-job-enabled}).
 * Tests switch it off so background threads do not interfere with assertions.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.inventory", name = "expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

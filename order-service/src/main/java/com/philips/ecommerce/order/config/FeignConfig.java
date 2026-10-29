package com.philips.ecommerce.order.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Kept out of the @SpringBootApplication class on purpose: slice tests (@WebMvcTest, @DataJpaTest) apply the main
 * class's annotations, and @EnableFeignClients there would try to create Feign clients in every slice.
 * Feign timeouts are configured per client under feign.client.config.* (config-repo/order-service.yml).
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(basePackages = "com.philips.ecommerce.order.client")
public class FeignConfig {
}

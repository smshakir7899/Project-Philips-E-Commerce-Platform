package com.philips.ecommerce.cart.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Scans the @FeignClient interfaces. Correlation-id propagation
 * (RequestInterceptor) and error decoding (RemoteServiceErrorDecoder ->
 * RemoteServiceException) are contributed by common-lib's auto-configuration.
 * Feign's own Retryer stays at its Spring Cloud default (never retry): retries
 * are done by Resilience4j in the adapters, otherwise the two retry layers
 * would multiply.
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(basePackages = "com.philips.ecommerce.cart.client")
public class FeignConfig {
}

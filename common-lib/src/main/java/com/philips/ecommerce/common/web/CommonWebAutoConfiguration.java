package com.philips.ecommerce.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.feign.RemoteServiceErrorDecoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Spring Boot auto-configuration (registered in META-INF/spring.factories). Any servlet service that depends on
 * common-lib automatically gets the global exception handler, correlation-id filter and Feign interceptor
 * and the Feign error decoder without needing component-scan of this package.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignCorrelationConfiguration {

        @Bean
        public CorrelationIdFeignInterceptor correlationIdFeignInterceptor() {
            return new CorrelationIdFeignInterceptor();
        }

        /** Picked up by every @FeignClient (Feign looks up ErrorDecoder beans in the application context). */
        @Bean
        @ConditionalOnMissingBean(feign.codec.ErrorDecoder.class)
        public RemoteServiceErrorDecoder remoteServiceErrorDecoder(ObjectMapper objectMapper) {
            return new RemoteServiceErrorDecoder(objectMapper);
        }
    }
}

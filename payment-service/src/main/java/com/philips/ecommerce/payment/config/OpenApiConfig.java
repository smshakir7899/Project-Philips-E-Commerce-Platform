package com.philips.ecommerce.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("payment-service API")
                .version("1.0.0")
                .description("SIMULATED payments only - there is no real payment gateway integration. "
                        + "Card tokens: tok_visa (success), tok_declined, tok_insufficient_funds, tok_timeout, "
                        + "tok_error."));
    }
}

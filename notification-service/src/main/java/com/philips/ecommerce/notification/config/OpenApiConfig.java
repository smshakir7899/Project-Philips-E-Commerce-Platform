package com.philips.ecommerce.notification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title shown in the gateway's aggregated Swagger UI (http://localhost:8080/swagger-ui.html). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI notificationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("notification-service API")
                .version("1.0.0")
                .description("Simulated customer e-mails and the platform-wide dead-letter store with replay."));
    }
}

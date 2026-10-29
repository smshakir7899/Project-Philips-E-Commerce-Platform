package com.philips.ecommerce.product.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc-openapi metadata. Swagger UI: http://localhost:8081/swagger-ui.html */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI productServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Product Service API")
                .version("v1")
                .description("Product catalog, categories, product images (S3) and performance/caching admin endpoints. "
                        + "Errors use the common ApiErrorResponse body {timestamp, status, error, code, message, path, "
                        + "correlationId, fieldErrors, details}.")
                .contact(new Contact().name("Philips E-Commerce learning project")));
    }
}

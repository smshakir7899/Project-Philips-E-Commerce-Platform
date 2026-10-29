package com.philips.ecommerce.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Product catalog microservice (port 8081).
 * <p>
 * Besides the catalog itself, this service hosts the performance experiment
 * behind the resume claim "improved performance by 30% through Redis caching,
 * MySQL query optimization, database indexing and efficient JPA/Hibernate data
 * access". Two read-path implementations exist
 * ({@code BaselineProductQueryService} and
 * {@code OptimizedProductQueryService}); exactly one is active, selected by
 * {@code app.product.query-mode}. See {@code perf/README.md} for the
 * methodology.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProductServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProductServiceApplication.class, args);
	}
}

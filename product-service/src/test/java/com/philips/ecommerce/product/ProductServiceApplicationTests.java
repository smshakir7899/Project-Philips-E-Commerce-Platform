package com.philips.ecommerce.product;

import com.philips.ecommerce.product.service.BaselineProductQueryService;
import com.philips.ecommerce.product.service.OptimizedProductQueryService;
import com.philips.ecommerce.product.service.OptimizedProductReader;
import com.philips.ecommerce.product.service.ProductQueryService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full context on H2 (no config-server, Eureka, Redis or S3 needed). Starting the context also validates every
 * JPQL @Query at startup. Verifies that exactly ONE ProductQueryService bean exists per query mode.
 */
class ProductServiceApplicationTests {

    @Nested
    @SpringBootTest
    class OptimizedModeByDefault {

        @Autowired
        private ApplicationContext context;

        @Test
        void onlyTheOptimizedImplementationIsActive() {
            assertThat(context.getBeansOfType(ProductQueryService.class)).hasSize(1);
            assertThat(context.getBean(ProductQueryService.class)).isInstanceOf(OptimizedProductQueryService.class);
            assertThat(context.getBean(ProductQueryService.class).mode()).isEqualTo("optimized");
        }
    }

    @Nested
    @SpringBootTest(properties = "app.product.query-mode=baseline")
    class BaselineMode {

        @Autowired
        private ApplicationContext context;

        @Test
        void onlyTheBaselineImplementationIsActive() {
            assertThat(context.getBeansOfType(ProductQueryService.class)).hasSize(1);
            assertThat(context.getBean(ProductQueryService.class)).isInstanceOf(BaselineProductQueryService.class);
            assertThat(context.getBeansOfType(OptimizedProductReader.class)).isEmpty();
        }
    }
}

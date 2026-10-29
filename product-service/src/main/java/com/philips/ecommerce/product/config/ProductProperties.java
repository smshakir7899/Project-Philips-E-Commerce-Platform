package com.philips.ecommerce.product.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/** Binds {@code app.product.*} (config-repo/product-service.yml). */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.product")
public class ProductProperties {

    /** {@code optimized} (default) or {@code baseline}; decides which ProductQueryService bean is created. */
    @NotBlank
    @Pattern(regexp = "optimized|baseline")
    private String queryMode = "optimized";

    @Valid
    private Cache cache = new Cache();

    @Valid
    private Perf perf = new Perf();

    @Getter
    @Setter
    public static class Cache {
        /** Master switch for the Redis cache-aside layer (env PRODUCT_CACHE_ENABLED). */
        private boolean enabled = true;
        /** TTL of product:v1:detail:{id} entries. Long, because detail entries are evicted explicitly on change. */
        @Min(1)
        private long detailTtlSeconds = 600;
        /**
         * TTL of list/search pages. Shorter, because a write does not delete old list pages: it only bumps the
         * list version, and the orphaned pages simply expire (see ProductCacheService).
         */
        @Min(1)
        private long listTtlSeconds = 120;
    }

    @Getter
    @Setter
    public static class Perf {
        /** Mirrors spring.jpa.properties.hibernate.generate_statistics (env PRODUCT_HIBERNATE_STATS). */
        private boolean hibernateStatistics = true;
    }
}

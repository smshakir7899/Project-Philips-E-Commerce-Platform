package com.philips.ecommerce.product.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** GET /api/products/admin/cache/stats. Counters are process-local and reset by POST /api/products/admin/perf/reset. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStatsResponse {
    private boolean enabled;
    private long hits;
    private long misses;
    private long errors;
    /** hits / (hits + misses); 0 when there was no lookup yet. */
    private double hitRatio;
    /** TTL of detail entries (list pages use app.product.cache.list-ttl-seconds). */
    private long ttlSeconds;
}

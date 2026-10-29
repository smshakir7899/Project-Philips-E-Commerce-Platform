package com.philips.ecommerce.product.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** GET /api/products/admin/perf/stats: what perf/bench.py records before and after each measured run. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerfStatsResponse {
    /** optimized | baseline - taken from the ACTIVE ProductQueryService bean, not from configuration. */
    private String queryMode;
    private boolean cacheEnabled;
    /** Hibernate Statistics counters since the last reset (prepareStatementCount, queryExecutionCount, ...). */
    private Map<String, Object> hibernate;
    private CacheStatsResponse cache;
}

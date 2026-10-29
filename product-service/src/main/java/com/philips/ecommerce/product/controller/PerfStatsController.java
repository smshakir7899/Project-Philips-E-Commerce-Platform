package com.philips.ecommerce.product.controller;

import com.philips.ecommerce.product.dto.PerfStatsResponse;
import com.philips.ecommerce.product.service.ProductCacheService;
import com.philips.ecommerce.product.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes Hibernate {@link Statistics} so the benchmark can report DATABASE WORK per request (JDBC statements,
 * entity loads, collection fetches) next to latency. Statement counts are deterministic for a given workload,
 * which makes them the most reproducible evidence of N+1 elimination and cache effectiveness.
 * Counters are JVM-wide; reset before a measured run and read right after it. Unauthenticated (see
 * CacheStatsController).
 */
@Tag(name = "Admin - performance", description = "Counters used by perf/bench.py")
@RestController
@RequestMapping("/api/products/admin/perf")
public class PerfStatsController {

    private final Statistics statistics;
    private final ProductQueryService queryService;
    private final ProductCacheService cacheService;

    public PerfStatsController(EntityManagerFactory entityManagerFactory, ProductQueryService queryService,
                               ProductCacheService cacheService) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.queryService = queryService;
        this.cacheService = cacheService;
    }

    @Operation(summary = "Query mode, cache state, Hibernate statistics and cache counters")
    @GetMapping("/stats")
    public PerfStatsResponse stats() {
        Map<String, Object> hibernate = new LinkedHashMap<>();
        hibernate.put("statisticsEnabled", statistics.isStatisticsEnabled());
        // JDBC PreparedStatements created = SQL statements sent to MySQL (the N in N+1)
        hibernate.put("prepareStatementCount", statistics.getPrepareStatementCount());
        // HQL/JPQL/native queries executed (does not include lazy loads, which are entity/collection fetches)
        hibernate.put("queryExecutionCount", statistics.getQueryExecutionCount());
        hibernate.put("queryExecutionMaxTimeMs", statistics.getQueryExecutionMaxTime());
        hibernate.put("queryExecutionMaxTimeQueryString", statistics.getQueryExecutionMaxTimeQueryString());
        hibernate.put("entityLoadCount", statistics.getEntityLoadCount());
        // entityFetchCount / collectionFetchCount = lazy initializations that needed their own SELECT
        hibernate.put("entityFetchCount", statistics.getEntityFetchCount());
        hibernate.put("collectionLoadCount", statistics.getCollectionLoadCount());
        hibernate.put("collectionFetchCount", statistics.getCollectionFetchCount());
        hibernate.put("transactionCount", statistics.getTransactionCount());
        hibernate.put("sessionOpenCount", statistics.getSessionOpenCount());
        hibernate.put("connectCount", statistics.getConnectCount());
        hibernate.put("flushCount", statistics.getFlushCount());
        hibernate.put("secondLevelCache", "n/a (Hibernate 2nd-level cache not enabled; Redis cache-aside is used)");
        return PerfStatsResponse.builder()
                .queryMode(queryService.mode())
                .cacheEnabled(cacheService.isEnabled())
                .hibernate(hibernate)
                .cache(cacheService.stats())
                .build();
    }

    @Operation(summary = "Reset Hibernate statistics and cache counters")
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        statistics.clear();
        cacheService.resetStats();
        return ResponseEntity.noContent().build();
    }
}

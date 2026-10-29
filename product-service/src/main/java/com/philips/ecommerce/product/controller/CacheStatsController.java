package com.philips.ecommerce.product.controller;

import com.philips.ecommerce.product.dto.CacheStatsResponse;
import com.philips.ecommerce.product.service.ProductCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cache administration. Like every /admin endpoint in this learning project it is unauthenticated; in a real
 * deployment it must be blocked at the gateway or protected (e.g. Spring Security with an admin role).
 */
@Tag(name = "Admin - cache", description = "Redis product cache statistics and flush")
@RestController
@RequestMapping("/api/products/admin/cache")
public class CacheStatsController {

    private final ProductCacheService cacheService;

    public CacheStatsController(ProductCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Operation(summary = "Cache hit/miss/error counters since the last reset")
    @GetMapping("/stats")
    public CacheStatsResponse stats() {
        return cacheService.stats();
    }

    @Operation(summary = "Delete all product:v1:* keys (SCAN based); used for cold-cache benchmarks")
    @DeleteMapping
    public ResponseEntity<Void> flush() {
        cacheService.flushAll();
        return ResponseEntity.noContent().build();
    }
}

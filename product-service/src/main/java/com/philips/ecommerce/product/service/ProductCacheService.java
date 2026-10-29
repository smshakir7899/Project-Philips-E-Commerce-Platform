package com.philips.ecommerce.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.product.config.ProductProperties;
import com.philips.ecommerce.product.dto.CacheStatsResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Explicit Redis cache-aside for product reads (deliberately NOT {@code @Cacheable}, so every step is visible).
 *
 * <h3>Read (cache-aside)</h3>
 * GET key -> hit: deserialize and return; miss: load from MySQL (the source of truth), SET key with TTL, return.
 *
 * <h3>Keys</h3>
 * <ul>
 *   <li>{@code product:v1:detail:{id}} - product detail, TTL {@code detail-ttl-seconds} (600).</li>
 *   <li>{@code product:v1:list:{listVersion}:{sha256(normalized query)}} - list/search pages and SKU lookups,
 *       TTL {@code list-ttl-seconds} (120). Hashing keeps keys short and free of user-controlled characters.</li>
 *   <li>{@code product:v1:list-version} - an integer, INCR-ed on every product change.</li>
 * </ul>
 * The {@code v1} segment is a schema version: when the cached DTO shape changes incompatibly, bump it and old
 * entries are simply never read again.
 *
 * <h3>Invalidation</h3>
 * A product change can affect any number of list pages (every page whose filter or sort it matches), and it is
 * impossible to compute which. Deleting them with {@code KEYS product:v1:list:*} is O(N) and BLOCKS the
 * single-threaded Redis server; even a SCAN-and-delete loop is slow and racy (pages written during the scan
 * survive). Instead the list keys embed a version: {@code INCR list-version} atomically makes all existing
 * pages unreachable, and they expire on their own via the short list TTL. Detail entries map 1:1 to a product,
 * so they are deleted precisely.
 *
 * <h3>Failure handling</h3>
 * Redis is an optimization, never a dependency: every Redis error is logged, counted
 * ({@code product.cache.errors}) and the request falls back to MySQL. Writes to the cache after a failed read
 * are skipped so an outage costs one short timeout per request, not two. If an invalidation fails, a stale
 * entry can survive at most one TTL - the bounded-staleness price of cache-aside.
 * Known, accepted limitations: no stampede protection (many concurrent misses of the same hot key all hit
 * MySQL) and the classic cache-aside race (a slow reader can write an old value after an eviction).
 */
@Slf4j
@Service
public class ProductCacheService {

    static final String KEY_PREFIX = "product:v1:";
    static final String DETAIL_PREFIX = KEY_PREFIX + "detail:";
    static final String LIST_PREFIX = KEY_PREFIX + "list:";
    static final String LIST_VERSION_KEY = KEY_PREFIX + "list-version";
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration detailTtl;
    private final Duration listTtl;
    private final JavaType detailType;
    private final JavaType pageType;

    // AtomicLong: resettable values for the benchmark endpoints. Micrometer counters: monotonic, for
    // Prometheus-style monitoring via /actuator/metrics/product.cache.hits (they are not reset).
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter errorCounter;

    public ProductCacheService(StringRedisTemplate redis, ObjectMapper objectMapper, ProductProperties properties,
                               MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = properties.getCache().isEnabled();
        this.detailTtl = Duration.ofSeconds(properties.getCache().getDetailTtlSeconds());
        this.listTtl = Duration.ofSeconds(properties.getCache().getListTtlSeconds());
        this.detailType = objectMapper.getTypeFactory().constructType(ProductDetailResponse.class);
        this.pageType = objectMapper.getTypeFactory()
                .constructParametricType(PageResponse.class, ProductSummaryResponse.class);
        this.hitCounter = Counter.builder("product.cache.hits").description("Product cache hits")
                .register(meterRegistry);
        this.missCounter = Counter.builder("product.cache.misses").description("Product cache misses")
                .register(meterRegistry);
        this.errorCounter = Counter.builder("product.cache.errors")
                .description("Redis errors that degraded to a MySQL read").register(meterRegistry);
        log.info("Product cache {} (detail TTL {}s, list TTL {}s)", enabled ? "ENABLED" : "DISABLED",
                detailTtl.getSeconds(), listTtl.getSeconds());
    }

    // ------------------------------------------------------------------ reads

    public ProductDetailResponse getDetail(Long productId, Supplier<ProductDetailResponse> loader) {
        return getOrLoad(detailKey(productId), detailType, detailTtl, loader);
    }

    /**
     * SKU lookups live in the versioned namespace: a PUT may change the SKU, and the old SKU's entry could not be
     * found for deletion - the version bump invalidates it without knowing it.
     */
    public ProductDetailResponse getDetailBySku(String sku, Supplier<ProductDetailResponse> loader) {
        return getVersioned("sku=" + sku, detailType, listTtl, loader);
    }

    public PageResponse<ProductSummaryResponse> getListPage(String normalizedQuery,
                                                            Supplier<PageResponse<ProductSummaryResponse>> loader) {
        return getVersioned("list|" + normalizedQuery, pageType, listTtl, loader);
    }

    private <T> T getVersioned(String logicalKey, JavaType type, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String version;
        try {
            version = currentListVersion();
        } catch (RuntimeException ex) {
            recordError("GET " + LIST_VERSION_KEY, ex);
            return loader.get();
        }
        return getOrLoad(LIST_PREFIX + version + ":" + sha256(logicalKey), type, ttl, loader);
    }

    <T> T getOrLoad(String key, JavaType type, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String cached;
        try {
            cached = redis.opsForValue().get(key);
        } catch (RuntimeException ex) {
            recordError("GET " + key, ex);
            return loader.get(); // degrade: MySQL is the source of truth
        }
        if (cached != null) {
            try {
                T value = objectMapper.readValue(cached, type);
                hits.incrementAndGet();
                hitCounter.increment();
                return value;
            } catch (JsonProcessingException ex) {
                // e.g. an incompatible entry written by an older version: treat as a miss and overwrite it
                log.warn("Discarding unreadable cache entry {}: {}", key, ex.getOriginalMessage());
            }
        }
        misses.incrementAndGet();
        missCounter.increment();
        T value = loader.get(); // exceptions (e.g. 404) propagate and nothing is cached (no negative caching)
        if (value != null) {
            put(key, value, ttl);
        }
        return value;
    }

    private void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException ex) {
            log.error("Cannot serialize {} for cache key {}", value.getClass().getSimpleName(), key, ex);
        } catch (RuntimeException ex) {
            recordError("SET " + key, ex);
        }
    }

    private String currentListVersion() {
        String version = redis.opsForValue().get(LIST_VERSION_KEY);
        return version == null ? "0" : version;
    }

    // ------------------------------------------------------------------ invalidation

    /**
     * Called AFTER the database transaction committed (see ProductCacheEvictor): deletes the detail entry and
     * bumps the list version so every cached list/search/SKU page becomes unreachable.
     */
    public void evictProduct(Long productId) {
        if (!enabled) {
            return;
        }
        try {
            redis.delete(detailKey(productId));
            Long newVersion = redis.opsForValue().increment(LIST_VERSION_KEY);
            log.debug("Evicted product {} (list version now {})", productId, newVersion);
        } catch (RuntimeException ex) {
            recordError("evict product " + productId, ex);
        }
    }

    /**
     * Deletes every {@code product:v1:*} key using SCAN (incremental, non-blocking cursor) instead of KEYS
     * (which walks the whole keyspace in one blocking call). Used for cold-cache benchmarks.
     *
     * @return number of deleted keys
     */
    public long flushAll() {
        try {
            Long deleted = redis.execute((RedisCallback<Long>) connection -> deleteByPattern(connection,
                    KEY_PREFIX + "*"));
            long count = deleted == null ? 0 : deleted;
            log.info("Flushed {} product cache keys", count);
            return count;
        } catch (RuntimeException ex) {
            recordError("flush", ex);
            throw new ServiceUnavailableException("CACHE_UNAVAILABLE", "Redis is not reachable: " + ex.getMessage(), ex);
        }
    }

    private long deleteByPattern(RedisConnection connection, String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build();
        long deleted = 0;
        List<byte[]> batch = new ArrayList<>(SCAN_BATCH);
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == SCAN_BATCH) {
                    deleted += deleteKeys(connection, batch);
                }
            }
        }
        if (!batch.isEmpty()) {
            deleted += deleteKeys(connection, batch);
        }
        return deleted;
    }

    private long deleteKeys(RedisConnection connection, List<byte[]> batch) {
        Long count = connection.keyCommands().del(batch.toArray(new byte[0][]));
        batch.clear();
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ stats

    public CacheStatsResponse stats() {
        long h = hits.get();
        long m = misses.get();
        double ratio = (h + m) == 0 ? 0.0 : (double) h / (h + m);
        return CacheStatsResponse.builder()
                .enabled(enabled)
                .hits(h)
                .misses(m)
                .errors(errors.get())
                .hitRatio(Math.round(ratio * 10000) / 10000.0)
                .ttlSeconds(detailTtl.getSeconds())
                .build();
    }

    public void resetStats() {
        hits.set(0);
        misses.set(0);
        errors.set(0);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ helpers

    static String detailKey(Long productId) {
        return DETAIL_PREFIX + productId;
    }

    private void recordError(String operation, RuntimeException ex) {
        errors.incrementAndGet();
        errorCounter.increment();
        // WARN without stack trace: during an outage this fires on every request
        log.warn("Redis {} failed (cache degraded, MySQL stays the source of truth): {}", operation, ex.toString());
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", ex);
        }
    }
}

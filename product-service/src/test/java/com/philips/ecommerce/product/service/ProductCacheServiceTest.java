package com.philips.ecommerce.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.product.TestFixtures;
import com.philips.ecommerce.product.config.ProductProperties;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCacheServiceTest {

    private static final String DETAIL_KEY = "product:v1:detail:1";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private SimpleMeterRegistry meterRegistry;
    private ProductCacheService cache;
    private final AtomicInteger loaderCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        meterRegistry = new SimpleMeterRegistry();
        cache = new ProductCacheService(redis, objectMapper, properties(true), meterRegistry);
    }

    @Test
    void hitReturnsCachedValueWithoutCallingTheLoader() throws Exception {
        ProductDetailResponse cached = TestFixtures.detail(1L);
        when(valueOps.get(DETAIL_KEY)).thenReturn(objectMapper.writeValueAsString(cached));

        ProductDetailResponse result = cache.getDetail(1L, () -> load(TestFixtures.detail(99L)));

        assertThat(result).isEqualTo(cached);
        assertThat(loaderCalls).hasValue(0);
        assertThat(cache.stats().getHits()).isEqualTo(1);
        assertThat(cache.stats().getMisses()).isZero();
        assertThat(meterRegistry.get("product.cache.hits").counter().count()).isEqualTo(1.0);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void missLoadsFromDatabaseAndStoresWithDetailTtl() throws Exception {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);
        ProductDetailResponse fromDb = TestFixtures.detail(1L);

        ProductDetailResponse result = cache.getDetail(1L, () -> load(fromDb));

        assertThat(result).isEqualTo(fromDb);
        assertThat(loaderCalls).hasValue(1);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(DETAIL_KEY), json.capture(), eq(Duration.ofSeconds(600)));
        // what was written can be read back into an equal object (round trip incl. Instant fields)
        assertThat(objectMapper.readValue(json.getValue(), ProductDetailResponse.class)).isEqualTo(fromDb);
        assertThat(cache.stats().getMisses()).isEqualTo(1);
        assertThat(cache.stats().getHitRatio()).isZero();
    }

    @Test
    void redisDownFallsBackToDatabaseAndCountsTheError() {
        when(valueOps.get(DETAIL_KEY)).thenThrow(new RedisConnectionFailureException("Connection refused"));
        ProductDetailResponse fromDb = TestFixtures.detail(1L);

        ProductDetailResponse result = cache.getDetail(1L, () -> load(fromDb));

        assertThat(result).isEqualTo(fromDb);
        assertThat(cache.stats().getErrors()).isEqualTo(1);
        assertThat(meterRegistry.get("product.cache.errors").counter().count()).isEqualTo(1.0);
        // no second (slow) round trip to a Redis that just failed
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisWriteFailureStillReturnsTheLoadedValue() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);
        doThrow(new RedisConnectionFailureException("timeout"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        ProductDetailResponse result = cache.getDetail(1L, () -> load(TestFixtures.detail(1L)));

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(cache.stats().getErrors()).isEqualTo(1);
    }

    @Test
    void unreadableEntryIsTreatedAsMissAndOverwritten() {
        when(valueOps.get(DETAIL_KEY)).thenReturn("{not json");

        ProductDetailResponse result = cache.getDetail(1L, () -> load(TestFixtures.detail(1L)));

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(cache.stats().getMisses()).isEqualTo(1);
        verify(valueOps).set(eq(DETAIL_KEY), anyString(), eq(Duration.ofSeconds(600)));
    }

    @Test
    void loaderExceptionPropagatesAndNothingIsCached() {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null);

        assertThatThrownBy(() -> cache.getDetail(1L, () -> {
            throw new ResourceNotFoundException("PRODUCT_NOT_FOUND", "nope");
        })).isInstanceOf(ResourceNotFoundException.class);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void listPagesAreKeyedByListVersionAndQueryHashWithListTtl() {
        when(valueOps.get(ProductCacheService.LIST_VERSION_KEY)).thenReturn("7");
        when(valueOps.get(startsWith("product:v1:list:7:"))).thenReturn(null);
        PageResponse<ProductSummaryResponse> page = new PageResponse<>(Collections.singletonList(
                ProductSummaryResponse.builder().id(1L).sku("PH-1").price(new BigDecimal("10.00")).build()),
                0, 20, 1, 1, true);

        PageResponse<ProductSummaryResponse> result = cache.getListPage("q=|cat=1", () -> {
            loaderCalls.incrementAndGet();
            return page;
        });

        assertThat(result).isEqualTo(page);
        String expectedKey = "product:v1:list:7:" + ProductCacheService.sha256("list|q=|cat=1");
        verify(valueOps).set(eq(expectedKey), anyString(), eq(Duration.ofSeconds(120)));
        assertThat(expectedKey).hasSize("product:v1:list:7:".length() + 64);
    }

    @Test
    void cachedListPageIsDeserializedWithItsGenericType() throws Exception {
        PageResponse<ProductSummaryResponse> page = new PageResponse<>(Collections.singletonList(
                ProductSummaryResponse.builder().id(3L).sku("PH-3").categoryName("Oral Care").build()),
                1, 10, 11, 2, true);
        when(valueOps.get(ProductCacheService.LIST_VERSION_KEY)).thenReturn(null); // never written yet -> "0"
        when(valueOps.get(startsWith("product:v1:list:0:"))).thenReturn(objectMapper.writeValueAsString(page));

        PageResponse<ProductSummaryResponse> result = cache.getListPage("x", () -> null);

        assertThat(result).isEqualTo(page);
        assertThat(result.getContent().get(0)).isInstanceOf(ProductSummaryResponse.class);
    }

    @Test
    void failingListVersionLookupDegradesToDatabase() {
        when(valueOps.get(ProductCacheService.LIST_VERSION_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        PageResponse<ProductSummaryResponse> page = new PageResponse<>(Collections.<ProductSummaryResponse>emptyList(),
                0, 20, 0, 0, true);

        assertThat(cache.getListPage("x", () -> page)).isSameAs(page);
        assertThat(cache.stats().getErrors()).isEqualTo(1);
    }

    @Test
    void evictionDeletesDetailKeyAndIncrementsListVersion() {
        cache.evictProduct(5L);

        verify(redis).delete("product:v1:detail:5");
        verify(valueOps).increment(ProductCacheService.LIST_VERSION_KEY);
    }

    @Test
    void evictionFailureIsSwallowedAndCounted() {
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete("product:v1:detail:5");

        cache.evictProduct(5L);

        assertThat(cache.stats().getErrors()).isEqualTo(1);
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        ProductCacheService disabled = new ProductCacheService(redis, objectMapper, properties(false), meterRegistry);

        ProductDetailResponse result = disabled.getDetail(1L, () -> load(TestFixtures.detail(1L)));
        disabled.evictProduct(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(disabled.stats().isEnabled()).isFalse();
        verifyNoInteractions(redis);
    }

    @Test
    void hitRatioAndResetStats() throws Exception {
        when(valueOps.get(DETAIL_KEY)).thenReturn(null, objectMapper.writeValueAsString(TestFixtures.detail(1L)),
                objectMapper.writeValueAsString(TestFixtures.detail(1L)), objectMapper.writeValueAsString(TestFixtures.detail(1L)));
        for (int i = 0; i < 4; i++) {
            cache.getDetail(1L, () -> load(TestFixtures.detail(1L)));
        }
        assertThat(cache.stats().getHitRatio()).isEqualTo(0.75);
        assertThat(cache.stats().getTtlSeconds()).isEqualTo(600);

        cache.resetStats();

        assertThat(cache.stats().getHits()).isZero();
        assertThat(cache.stats().getMisses()).isZero();
    }

    private ProductDetailResponse load(ProductDetailResponse value) {
        loaderCalls.incrementAndGet();
        return value;
    }

    private static ProductProperties properties(boolean enabled) {
        ProductProperties properties = new ProductProperties();
        properties.getCache().setEnabled(enabled);
        properties.getCache().setDetailTtlSeconds(600);
        properties.getCache().setListTtlSeconds(120);
        return properties;
    }
}

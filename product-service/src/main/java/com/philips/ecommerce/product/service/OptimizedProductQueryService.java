package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OPTIMIZED read path ("after"), the default ({@code app.product.query-mode=optimized} or property missing).
 * <p>
 * Order of operations per request: Redis (no transaction, no JDBC connection) -> on miss, {@link OptimizedProductReader}
 * (read-only transaction, bounded number of indexed queries) -> store in Redis. Statements per request:
 * 0 on a cache hit; on a miss see {@link OptimizedProductReader}.
 * <p>
 * The batch endpoint is intentionally NOT cached: order-service uses it at checkout as the authoritative price
 * source, and a stale price there costs real money. It still benefits from the 2-query loading.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.product.query-mode", havingValue = "optimized", matchIfMissing = true)
public class OptimizedProductQueryService implements ProductQueryService {

    public static final String MODE = "optimized";

    private final OptimizedProductReader reader;
    private final ProductCacheService cache;

    public OptimizedProductQueryService(OptimizedProductReader reader, ProductCacheService cache) {
        this.reader = reader;
        this.cache = cache;
        log.info("Product query mode = OPTIMIZED (cache {})", cache.isEnabled() ? "enabled" : "disabled");
    }

    @Override
    public PageResponse<ProductSummaryResponse> list(ProductSearchCriteria criteria) {
        return cache.getListPage(criteria.normalizedKey(), () -> reader.loadPage(criteria));
    }

    @Override
    public ProductDetailResponse getById(Long id) {
        return cache.getDetail(id, () -> reader.loadDetail(id));
    }

    @Override
    public ProductDetailResponse getBySku(String sku) {
        return cache.getDetailBySku(sku, () -> reader.loadDetailBySku(sku));
    }

    @Override
    public List<ProductSummaryResponse> getBatch(List<Long> ids) {
        return reader.loadBatch(ids);
    }

    @Override
    public String mode() {
        return MODE;
    }
}

package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;

import java.util.List;

/**
 * All catalog READ paths. Two implementations exist and exactly one bean is active
 * ({@code app.product.query-mode}):
 * <ul>
 *   <li>{@link BaselineProductQueryService} - naive but "works": entities + lazy loading (N+1), LIKE '%q%'
 *       search, read-write transactions, no cache. The "before" of the performance experiment.</li>
 *   <li>{@link OptimizedProductQueryService} - entity graphs, batched thumbnail query, FULLTEXT search,
 *       read-only transactions and Redis cache-aside. The "after".</li>
 * </ul>
 * Both return identical DTOs for identical input (except the documented search semantics), so the
 * benchmark compares like with like.
 */
public interface ProductQueryService {

    /** Active products only; text search when {@code criteria.getQ()} is set. */
    PageResponse<ProductSummaryResponse> list(ProductSearchCriteria criteria);

    /** Includes inactive products (callers check {@code active}). @throws ResourceNotFoundException PRODUCT_NOT_FOUND */
    ProductDetailResponse getById(Long id);

    /** @throws com.philips.ecommerce.common.exception.ResourceNotFoundException PRODUCT_NOT_FOUND */
    ProductDetailResponse getBySku(String sku);

    /**
     * Summaries for the given ids, in request order, INCLUDING inactive products; unknown ids are simply absent.
     * Used by order-service at checkout for authoritative prices, so it always reads MySQL (never the cache).
     */
    List<ProductSummaryResponse> getBatch(List<Long> ids);

    /** "baseline" or "optimized" - reported by the perf stats endpoint to prove which bean is running. */
    String mode();
}

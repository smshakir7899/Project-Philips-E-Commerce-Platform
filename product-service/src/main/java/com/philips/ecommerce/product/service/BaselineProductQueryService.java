package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.entity.ProductImage;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.ProductRepository;
import com.philips.ecommerce.product.repository.ProductSpecifications;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BASELINE read path ("before"), active only with {@code app.product.query-mode=baseline}.
 * <p>
 * This is how a lot of real code looks: correct, readable - and slow under load. It is NOT a straw man with
 * artificial sleeps; every inefficiency below is a common, real mistake:
 * <ol>
 *   <li><b>N+1 selects.</b> Queries return bare entities; the mapper then touches the LAZY category proxy and
 *       the LAZY images collection of every product. Expected JDBC statements (Hibernate
 *       {@code prepareStatementCount}) per request:
 *       <ul>
 *         <li>list page with N products from C distinct categories: {@code 1 (page) + 1 (count) + C + N}
 *             - e.g. 20 products of one category = 23 statements. (Spring Data skips the count when the first
 *             page is not full; each category proxy is initialized once per persistence context.)</li>
 *         <li>search: same formula, but the page query is a full scan (see 2).</li>
 *         <li>detail: {@code 1 (product) + 1 (category) + 1 (images) + 1 (attributes) = 4}.</li>
 *         <li>batch of K ids: {@code 1 + C + K}.</li>
 *       </ul></li>
 *   <li><b>Non-sargable search.</b> {@code LOWER(name) LIKE '%q%' OR LOWER(description) LIKE '%q%'}: the leading
 *       wildcard and the function on the column rule out every B-tree index -> full table scan per search.</li>
 *   <li><b>Read-write transactions for reads.</b> Plain {@code @Transactional}: Hibernate keeps a snapshot of
 *       every loaded entity and dirty-checks all of them at commit, although nothing is ever modified.</li>
 *   <li><b>No cache</b>: every request goes to MySQL.</li>
 * </ol>
 * The transaction itself is required: open-in-view is disabled, so lazy loading outside a transaction would
 * throw LazyInitializationException.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.product.query-mode", havingValue = "baseline")
@Transactional
public class BaselineProductQueryService implements ProductQueryService {

    public static final String MODE = "baseline";

    private final ProductRepository productRepository;
    private final ProductMapper mapper;

    public BaselineProductQueryService(ProductRepository productRepository, ProductMapper mapper) {
        this.productRepository = productRepository;
        this.mapper = mapper;
        log.warn("Product query mode = BASELINE (naive JPA data access, no cache) - performance experiment only");
    }

    @Override
    public PageResponse<ProductSummaryResponse> list(ProductSearchCriteria criteria) {
        Page<Product> page;
        if (criteria.hasText()) {
            String pattern = "%" + ProductSpecifications.escapeLike(criteria.getQ().toLowerCase(Locale.ROOT)) + "%";
            page = productRepository.searchByLike(pattern, criteria.getCategoryId(), criteria.getMinPrice(),
                    criteria.getMaxPrice(), criteria.toPageRequest());
        } else {
            page = productRepository.findActiveForListing(criteria.getCategoryId(), criteria.getMinPrice(),
                    criteria.getMaxPrice(), criteria.toPageRequest());
        }
        // N+1: thumbnailOf() initializes product.images (1 query per product), toSummary() the category proxy
        return PageResponse.of(page, product -> mapper.toSummary(product, thumbnailOf(product)));
    }

    @Override
    public ProductDetailResponse getById(Long id) {
        Product product = productRepository.findById(id).orElseThrow(() -> ProductNotFound.byId(id));
        return mapper.toDetail(product); // + category, + images, + attributes = 4 statements
    }

    @Override
    public ProductDetailResponse getBySku(String sku) {
        Product product = productRepository.findBySku(sku).orElseThrow(() -> ProductNotFound.bySku(sku));
        return mapper.toDetail(product);
    }

    @Override
    public List<ProductSummaryResponse> getBatch(List<Long> ids) {
        Map<Long, Product> byId = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<ProductSummaryResponse> result = new ArrayList<>(byId.size());
        for (Long id : ids) {
            Product product = byId.get(id);
            if (product != null) {
                result.add(mapper.toSummary(product, thumbnailOf(product)));
            }
        }
        return result;
    }

    @Override
    public String mode() {
        return MODE;
    }

    /** Loads the WHOLE images collection just to pick one URL - one SELECT per product. */
    private String thumbnailOf(Product product) {
        return product.getImages().stream()
                .min(Comparator.comparingInt(ProductImage::getSortOrder).thenComparing(ProductImage::getId))
                .map(ProductImage::getUrl)
                .orElse(null);
    }
}

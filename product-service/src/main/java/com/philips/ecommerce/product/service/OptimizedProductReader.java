package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.ProductImageRepository;
import com.philips.ecommerce.product.repository.ProductRepository;
import com.philips.ecommerce.product.repository.ProductSpecifications;
import com.philips.ecommerce.product.repository.ThumbnailView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MySQL side of the OPTIMIZED read path. Kept in its own bean so that the read-only transaction is opened only
 * on a cache MISS: OptimizedProductQueryService checks Redis first, outside any transaction, and a cache hit
 * never borrows a JDBC connection from the pool.
 * <p>
 * {@code @Transactional(readOnly = true)}: Spring sets Hibernate's FlushMode to MANUAL and marks the session
 * read-only, so loaded entities get no dirty-checking snapshot and nothing is flushed at commit. (Connector/J
 * also sends "SET SESSION TRANSACTION READ ONLY" when the connection is switched to read-only - a cheap round
 * trip that is visible in MySQL's Questions counter but not in Hibernate's statement count.)
 * <p>
 * Expected JDBC statements per cache miss:
 * <ul>
 *   <li>list page: 3 = page select with category LEFT JOIN + count + one thumbnail query for the page.</li>
 *   <li>full-text search: 4 = id page (FULLTEXT) + count + products with category + thumbnails.</li>
 *   <li>detail: 2 = product + category + images in one select, attributes in a second one.</li>
 *   <li>batch: 2 = products with category + thumbnails.</li>
 * </ul>
 * Why not simply "join fetch p.images" for the list? Fetch-joining a COLLECTION multiplies rows (one per image),
 * so SQL LIMIT can no longer cut at product boundaries; Hibernate then logs
 * <i>HHH000104: firstResult/maxResults specified with collection fetch; applying in memory!</i>, loads ALL
 * matching rows and paginates in the JVM - catastrophic on a 50k-row catalog. A to-one join (category) is safe,
 * the collection is loaded with a second, bounded {@code IN (...)} query instead.
 */
@Component
@ConditionalOnProperty(name = "app.product.query-mode", havingValue = "optimized", matchIfMissing = true)
@Transactional(readOnly = true)
public class OptimizedProductReader {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** innodb_ft_min_token_size default: shorter tokens are not in the FULLTEXT index. */
    static final int MIN_TOKEN_LENGTH = 3;
    private static final int MAX_TERMS = 8;
    /**
     * Words of InnoDB's default stopword list that are >= 3 chars. A required stopword (+the*) would never match,
     * so they are dropped from the boolean query.
     */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "about", "are", "com", "for", "from", "how", "that", "the", "this", "was", "what", "when", "where",
            "who", "will", "with", "und", "www"));

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ProductMapper mapper;

    public OptimizedProductReader(ProductRepository productRepository, ProductImageRepository imageRepository,
                                  ProductMapper mapper) {
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.mapper = mapper;
    }

    public PageResponse<ProductSummaryResponse> loadPage(ProductSearchCriteria criteria) {
        if (!criteria.hasText()) {
            return filteredList(criteria, null);
        }
        String booleanQuery = toBooleanModeQuery(criteria.getQ());
        if (booleanQuery != null) {
            return fullTextSearch(criteria, booleanQuery);
        }
        // Only very short words (e.g. "tv"): not in the FULLTEXT index -> indexed name-prefix search instead.
        return filteredList(criteria, ProductSpecifications.nameStartsWith(criteria.getQ()));
    }

    public ProductDetailResponse loadDetail(Long id) {
        Product product = productRepository.findDetailById(id).orElseThrow(() -> ProductNotFound.byId(id));
        return mapper.toDetail(product);
    }

    public ProductDetailResponse loadDetailBySku(String sku) {
        Product product = productRepository.findDetailBySku(sku).orElseThrow(() -> ProductNotFound.bySku(sku));
        return mapper.toDetail(product);
    }

    public List<ProductSummaryResponse> loadBatch(List<Long> ids) {
        return loadSummariesInOrder(ids);
    }

    private PageResponse<ProductSummaryResponse> filteredList(ProductSearchCriteria criteria,
                                                              Specification<Product> extra) {
        Specification<Product> spec = Specification.where(ProductSpecifications.isActive())
                .and(ProductSpecifications.inCategory(criteria.getCategoryId()))
                .and(ProductSpecifications.priceAtLeast(criteria.getMinPrice()))
                .and(ProductSpecifications.priceAtMost(criteria.getMaxPrice()))
                .and(extra);
        Page<Product> page = productRepository.findAll(spec, criteria.toPageRequest()); // category joined
        Map<Long, String> thumbnails = thumbnails(page.getContent().stream()
                .map(Product::getId).collect(Collectors.toList()));
        return PageResponse.of(page, product -> mapper.toSummary(product, thumbnails.get(product.getId())));
    }

    private PageResponse<ProductSummaryResponse> fullTextSearch(ProductSearchCriteria criteria, String booleanQuery) {
        Page<Number> idPage = productRepository.searchIdsFullText(booleanQuery, criteria.getCategoryId(),
                criteria.getMinPrice(), criteria.getMaxPrice(), criteria.toNativePageRequest());
        List<Long> ids = idPage.getContent().stream().map(Number::longValue).collect(Collectors.toList());
        List<ProductSummaryResponse> content = loadSummariesInOrder(ids);
        return new PageResponse<>(content, idPage.getNumber(), idPage.getSize(), idPage.getTotalElements(),
                idPage.getTotalPages(), idPage.isLast());
    }

    /** Products + category in one query, thumbnails in a second; result keeps the order of {@code ids}. */
    private List<ProductSummaryResponse> loadSummariesInOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Product> byId = productRepository.findWithCategoryByIdIn(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, String> thumbnails = thumbnails(byId.keySet());
        List<ProductSummaryResponse> result = new ArrayList<>(byId.size());
        for (Long id : ids) {
            Product product = byId.get(id);
            if (product != null) {
                result.add(mapper.toSummary(product, thumbnails.get(id)));
            }
        }
        return result;
    }

    /** ONE query for all thumbnails of the page; if several images have sortOrder 0, the oldest (lowest id) wins. */
    private Map<Long, String> thumbnails(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ThumbnailView> best = new HashMap<>();
        for (ThumbnailView view : imageRepository.findThumbnails(productIds)) {
            ThumbnailView current = best.get(view.getProductId());
            if (current == null || view.getImageId() < current.getImageId()) {
                best.put(view.getProductId(), view);
            }
        }
        Map<Long, String> urls = new HashMap<>(best.size() * 2);
        for (Map.Entry<Long, ThumbnailView> entry : best.entrySet()) {
            urls.put(entry.getKey(), entry.getValue().getUrl());
        }
        return urls;
    }

    /**
     * Turns free user input into a safe MySQL BOOLEAN MODE expression: "Sonicare  tooth-brush!" ->
     * {@code "+sonicare* +tooth* +brush*"} (every word required, prefix match). Operator characters
     * (+ - < > ( ) ~ * " @) are stripped by splitting on non-letters/digits, so user input can never produce a
     * syntax error or change the query semantics.
     *
     * @return null when no word is long enough to be in the FULLTEXT index
     */
    static String toBooleanModeQuery(String q) {
        if (q == null) {
            return null;
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : NON_WORD.split(q.toLowerCase(Locale.ROOT))) {
            if (token.length() >= MIN_TOKEN_LENGTH && !STOPWORDS.contains(token)) {
                terms.add(token);
            }
            if (terms.size() == MAX_TERMS) {
                break;
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        for (String term : terms) {
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append('+').append(term).append('*');
        }
        return query.toString();
    }
}

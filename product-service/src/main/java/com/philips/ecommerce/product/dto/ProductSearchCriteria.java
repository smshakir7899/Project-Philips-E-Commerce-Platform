package com.philips.ecommerce.product.dto;

import com.philips.ecommerce.common.exception.BadRequestException;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Immutable, validated parameters of {@code GET /api/products}. Built by the controller, consumed by both
 * ProductQueryService implementations so that baseline and optimized receive exactly the same input.
 */
@Getter
@Builder
public class ProductSearchCriteria {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Free-text search; null/blank = no text filter. */
    private final String q;
    private final Long categoryId;
    private final BigDecimal minPrice;
    private final BigDecimal maxPrice;
    private final int page;
    private final int size;
    private final ProductSortField sortField;
    private final Sort.Direction sortDirection;

    /**
     * Parses {@code sort=name,asc} / {@code sort=price} (direction defaults to ASC).
     *
     * @throws BadRequestException INVALID_SORT_FIELD / INVALID_SORT_DIRECTION
     */
    public static ProductSearchCriteria of(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                           int page, int size, String sort) {
        if (page < 0) {
            throw new BadRequestException("INVALID_PAGE_REQUEST", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("INVALID_PAGE_REQUEST", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("INVALID_PRICE_RANGE", "minPrice must be less than or equal to maxPrice");
        }
        String sortSpec = StringUtils.hasText(sort) ? sort : "name,asc";
        String[] parts = sortSpec.split(",");
        ProductSortField field = ProductSortField.fromProperty(parts[0]);
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1 && StringUtils.hasText(parts[1])) {
            try {
                direction = Sort.Direction.fromString(parts[1].trim());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("INVALID_SORT_DIRECTION", "Sort direction must be asc or desc");
            }
        }
        if (parts.length > 2) {
            throw new BadRequestException("INVALID_SORT_FIELD", "Only one sort field is supported, e.g. sort=price,desc");
        }
        return ProductSearchCriteria.builder()
                .q(StringUtils.hasText(q) ? q.trim() : null)
                .categoryId(categoryId)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .page(page)
                .size(size)
                .sortField(field)
                .sortDirection(direction)
                .build();
    }

    public boolean hasText() {
        return q != null;
    }

    /**
     * JPA sort on entity properties. {@code id} is always appended as a tie-breaker: with a non-unique sort key
     * (many products share a price) LIMIT/OFFSET pagination is otherwise non-deterministic and rows can repeat or
     * disappear between pages. InnoDB secondary indexes end with the PK, so the tie-breaker is free.
     */
    public PageRequest toPageRequest() {
        return PageRequest.of(page, size, Sort.by(sortDirection, sortField.getProperty()).and(Sort.by(sortDirection, "id")));
    }

    /** Same as {@link #toPageRequest()} but with SQL column names, for native queries. */
    public PageRequest toNativePageRequest() {
        return PageRequest.of(page, size, Sort.by(sortDirection, sortField.getColumn()).and(Sort.by(sortDirection, "id")));
    }

    /**
     * Canonical string of all parameters that influence the result. Equal queries produce equal strings
     * (case of q, trailing zeros of prices and sort spelling are normalized), so they share one cache entry.
     */
    public String normalizedKey() {
        return "q=" + (q == null ? "" : q.toLowerCase(Locale.ROOT))
                + "|cat=" + (categoryId == null ? "" : categoryId)
                + "|min=" + normalize(minPrice)
                + "|max=" + normalize(maxPrice)
                + "|page=" + page
                + "|size=" + size
                + "|sort=" + sortField.getProperty() + "," + sortDirection.name();
    }

    private static String normalize(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}

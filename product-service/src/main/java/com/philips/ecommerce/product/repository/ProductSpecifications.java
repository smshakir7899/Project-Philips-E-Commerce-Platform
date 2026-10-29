package com.philips.ecommerce.product.repository;

import com.philips.ecommerce.product.entity.Product;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/**
 * Composable predicates for the optimized listing. Unlike the "(:x is null or ...)" JPQL idiom, a Specification
 * only adds the predicates that are actually present, so the generated SQL is exactly
 * {@code WHERE active = ? AND category_id = ? AND price >= ? AND price <= ?} - the shape the composite index
 * (category_id, active, price) is designed for.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> isActive() {
        return (root, query, cb) -> cb.isTrue(root.<Boolean>get("active"));
    }

    /** Compares the FK column directly (root.get("category").get("id") does not join the category table). */
    public static Specification<Product> inCategory(Long categoryId) {
        return categoryId == null ? null
                : (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Product> priceAtLeast(BigDecimal minPrice) {
        return minPrice == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.<BigDecimal>get("price"), minPrice);
    }

    public static Specification<Product> priceAtMost(BigDecimal maxPrice) {
        return maxPrice == null ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.<BigDecimal>get("price"), maxPrice);
    }

    /**
     * Case-insensitive name prefix ({@code name LIKE 'abc%'}): no leading wildcard and no function on the column,
     * so idx_product_active_name can range-scan it. MySQL's default utf8mb4_0900_ai_ci collation is already
     * case-insensitive, so no LOWER() is needed (LOWER(name) would disable the index).
     */
    public static Specification<Product> nameStartsWith(String prefix) {
        return prefix == null ? null
                : (root, query, cb) -> cb.like(root.<String>get("name"), escapeLike(prefix) + "%", '!');
    }

    /** Escapes LIKE wildcards in user input so "50%" searches for the literal text. Escape char: '!'. */
    public static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}

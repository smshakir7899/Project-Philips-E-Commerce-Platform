package com.philips.ecommerce.product.repository;

import com.philips.ecommerce.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import javax.persistence.QueryHint;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Product data access. Methods are grouped by the read path that uses them so the baseline/optimized
 * difference is visible in one place.
 */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    // ------------------------------------------------------------------ writes / shared

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    // ------------------------------------------------------------------ BASELINE read path

    /** Baseline lookup by SKU: returns the bare entity, associations are loaded lazily later (extra queries). */
    Optional<Product> findBySku(String sku);

    /**
     * Baseline listing: entities only, category and images are NOT fetched (they are touched later -> N+1).
     * The "(:x is null or ...)" optional-filter idiom is fine on MySQL with client-side prepared statements:
     * the literal is inlined and the optimizer folds "NULL IS NULL" away, so indexes can still be used.
     */
    @Query(value = "select p from Product p where p.active = true"
            + " and (:categoryId is null or p.category.id = :categoryId)"
            + " and (:minPrice is null or p.price >= :minPrice)"
            + " and (:maxPrice is null or p.price <= :maxPrice)",
            countQuery = "select count(p) from Product p where p.active = true"
                    + " and (:categoryId is null or p.category.id = :categoryId)"
                    + " and (:minPrice is null or p.price >= :minPrice)"
                    + " and (:maxPrice is null or p.price <= :maxPrice)")
    Page<Product> findActiveForListing(@Param("categoryId") Long categoryId,
                                       @Param("minPrice") BigDecimal minPrice,
                                       @Param("maxPrice") BigDecimal maxPrice,
                                       Pageable pageable);

    /**
     * Baseline search: {@code LOWER(col) LIKE '%term%'}. The leading wildcard (and the LOWER() function on the
     * column) make every B-tree index useless, so MySQL reads every active row and evaluates both LIKEs
     * (type=ALL / full index scan in EXPLAIN). Semantics: substring match anywhere in name or description.
     */
    @Query(value = "select p from Product p where p.active = true"
            + " and (lower(p.name) like :pattern escape '!' or lower(p.description) like :pattern escape '!')"
            + " and (:categoryId is null or p.category.id = :categoryId)"
            + " and (:minPrice is null or p.price >= :minPrice)"
            + " and (:maxPrice is null or p.price <= :maxPrice)",
            countQuery = "select count(p) from Product p where p.active = true"
                    + " and (lower(p.name) like :pattern escape '!' or lower(p.description) like :pattern escape '!')"
                    + " and (:categoryId is null or p.category.id = :categoryId)"
                    + " and (:minPrice is null or p.price >= :minPrice)"
                    + " and (:maxPrice is null or p.price <= :maxPrice)")
    Page<Product> searchByLike(@Param("pattern") String pattern,
                               @Param("categoryId") Long categoryId,
                               @Param("minPrice") BigDecimal minPrice,
                               @Param("maxPrice") BigDecimal maxPrice,
                               Pageable pageable);

    // ------------------------------------------------------------------ OPTIMIZED read path

    /**
     * Overrides the JpaSpecificationExecutor method to add an entity graph: the ManyToOne category is fetched
     * with a LEFT JOIN in the SAME select. A to-one join does not multiply rows, so SQL LIMIT/OFFSET pagination
     * stays correct (unlike a collection fetch join, see OptimizedProductReader). The count query Spring Data
     * derives does not use the graph.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    /** Products with their category in one query (used for batch reads and to hydrate search-result ids). */
    @EntityGraph(attributePaths = "category")
    List<Product> findWithCategoryByIdIn(Collection<Long> ids);

    /**
     * Detail: product + category + images in ONE select. Only ONE collection (a "bag", i.e. a List without an
     * index column) may be fetch-joined: fetching images AND attributes together would throw
     * MultipleBagFetchException, and even with Sets it would return images x attributes rows (cartesian product).
     * Attributes are therefore initialized by a second, cheap query when the mapper touches them.
     * <p>
     * The collection join repeats the product row once per image, hence DISTINCT. PASS_DISTINCT_THROUGH=false
     * tells Hibernate to de-duplicate in memory only and NOT to send DISTINCT to MySQL (which would force a
     * needless temporary table / sort).
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.QueryHints.HINT_PASS_DISTINCT_THROUGH, value = "false"))
    @Query("select distinct p from Product p join fetch p.category left join fetch p.images where p.id = :id")
    Optional<Product> findDetailById(@Param("id") Long id);

    @QueryHints(@QueryHint(name = org.hibernate.jpa.QueryHints.HINT_PASS_DISTINCT_THROUGH, value = "false"))
    @Query("select distinct p from Product p join fetch p.category left join fetch p.images where p.sku = :sku")
    Optional<Product> findDetailBySku(@Param("sku") String sku);

    /**
     * Optimized search, step 1 ("ID-first pagination"): MySQL FULLTEXT search in BOOLEAN MODE served by
     * ft_product_search, returning only the ids of one page. Step 2 loads those products with their category.
     * <p>
     * Semantic difference vs the baseline LIKE: FULLTEXT matches whole words / word prefixes ("+airfry*" matches
     * "Airfryer", but "fryer" does NOT match "Airfryer"), ignores stopwords and words shorter than
     * innodb_ft_min_token_size (3), and also searches the brand column. Sort columns come from the whitelisted
     * {@code ProductSortField#getColumn()} (Spring Data appends them as "p.&lt;column&gt;").
     * <p>
     * Native scalar ids come back as BigInteger from Hibernate, hence {@code Page<Number>}.
     */
    @Query(value = "SELECT p.id FROM product p WHERE p.active = TRUE"
            + " AND MATCH(p.name, p.brand, p.description) AGAINST (:booleanQuery IN BOOLEAN MODE)"
            + " AND (:categoryId IS NULL OR p.category_id = :categoryId)"
            + " AND (:minPrice IS NULL OR p.price >= :minPrice)"
            + " AND (:maxPrice IS NULL OR p.price <= :maxPrice)",
            countQuery = "SELECT COUNT(*) FROM product p WHERE p.active = TRUE"
                    + " AND MATCH(p.name, p.brand, p.description) AGAINST (:booleanQuery IN BOOLEAN MODE)"
                    + " AND (:categoryId IS NULL OR p.category_id = :categoryId)"
                    + " AND (:minPrice IS NULL OR p.price >= :minPrice)"
                    + " AND (:maxPrice IS NULL OR p.price <= :maxPrice)",
            nativeQuery = true)
    Page<Number> searchIdsFullText(@Param("booleanQuery") String booleanQuery,
                                   @Param("categoryId") Long categoryId,
                                   @Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice,
                                   Pageable pageable);
}

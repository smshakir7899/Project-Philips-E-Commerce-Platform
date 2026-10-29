package com.philips.ecommerce.product.repository;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.entity.ProductImage;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.service.BaselineProductQueryService;
import com.philips.ecommerce.product.service.OptimizedProductReader;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import javax.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JPA part of the optimization with numbers that do not depend on hardware: the number of JDBC
 * statements Hibernate prepares (Statistics#getPrepareStatementCount) for the same request, baseline vs
 * optimized, on real Hibernate + H2 (MySQL mode). The services are instantiated directly (no cache, no proxies)
 * and run inside the test transaction; the persistence context is cleared before each measurement so nothing
 * is served from the first-level cache.
 * <p>
 * The FULLTEXT search is MySQL-specific and therefore covered by ProductCatalogIT (Testcontainers), not here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductQueryStatementCountTest {

    private static final int CATEGORIES = 3;
    private static final int PRODUCTS_PER_CATEGORY = 10;

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductImageRepository imageRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private BaselineProductQueryService baseline;
    private OptimizedProductReader optimized;
    private final List<Long> productIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProductMapper mapper = new ProductMapper();
        baseline = new BaselineProductQueryService(productRepository, mapper);
        optimized = new OptimizedProductReader(productRepository, imageRepository, mapper);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        int n = 0;
        for (int c = 1; c <= CATEGORIES; c++) {
            Category category = entityManager.persist(new Category("Category " + c, "category-" + c, null));
            for (int p = 0; p < PRODUCTS_PER_CATEGORY; p++) {
                n++;
                Product product = new Product();
                product.setSku(String.format("PH-T-%03d", n));
                product.setName(String.format("Philips Product %03d", n)); // name order != category order
                product.setBrand("Philips");
                product.setDescription("Test product " + n);
                product.setPrice(new BigDecimal(10 + n + ".00"));
                product.setCategory(category);
                product.addAttribute("Colour", "White");
                product.addAttribute("Weight", n + "00 g");
                entityManager.persist(product);
                entityManager.persist(new ProductImage(product, "k/" + n + "/0", "http://img/" + n + "/0.jpg", "image/jpeg", 0));
                entityManager.persist(new ProductImage(product, "k/" + n + "/1", "http://img/" + n + "/1.jpg", "image/jpeg", 1));
                productIds.add(product.getId());
            }
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void listPageBaselineIsNPlusOneOptimizedIsConstant() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of(null, null, null, null, 0, 10, "name,asc");

        PageResponse<ProductSummaryResponse> base = measure(() -> baseline.list(criteria));
        long baselineStatements = lastStatementCount;
        PageResponse<ProductSummaryResponse> opt = measure(() -> optimized.loadPage(criteria));
        long optimizedStatements = lastStatementCount;

        long distinctCategories = base.getContent().stream().map(ProductSummaryResponse::getCategoryId).distinct().count();
        // baseline: page + count + one SELECT per distinct category proxy + one SELECT per product's images
        assertThat(baselineStatements).isEqualTo(2 + distinctCategories + 10);
        // optimized: page (category joined) + count + thumbnails of the whole page
        assertThat(optimizedStatements).isEqualTo(3);
        // identical results - the optimization changes HOW data is loaded, not WHAT is returned
        assertThat(opt).isEqualTo(base);
        assertThat(opt.getContent()).allSatisfy(s -> assertThat(s.getThumbnailUrl()).endsWith("/0.jpg"));
        assertThat(opt.getTotalElements()).isEqualTo(CATEGORIES * PRODUCTS_PER_CATEGORY);
    }

    @Test
    void categoryAndPriceFilteredListReturnsSameRowsWithFewerStatements() {
        Long categoryId = productRepository.findById(productIds.get(12)).get().getCategory().getId();
        ProductSearchCriteria criteria = ProductSearchCriteria.of(null, categoryId, new BigDecimal("20"),
                new BigDecimal("40"), 0, 5, "price,desc");

        PageResponse<ProductSummaryResponse> base = measure(() -> baseline.list(criteria));
        long baselineStatements = lastStatementCount;
        PageResponse<ProductSummaryResponse> opt = measure(() -> optimized.loadPage(criteria));

        assertThat(opt).isEqualTo(base);
        assertThat(opt.getContent()).extracting(ProductSummaryResponse::getPrice)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(lastStatementCount).isEqualTo(3);
        assertThat(baselineStatements).isEqualTo(2 + 1 + 5);
    }

    @Test
    void detailBaselineFourStatementsOptimizedTwo() {
        Long id = productIds.get(4);

        ProductDetailResponse base = measure(() -> baseline.getById(id));
        long baselineStatements = lastStatementCount;
        ProductDetailResponse opt = measure(() -> optimized.loadDetail(id));

        assertThat(baselineStatements).isEqualTo(4); // product, category, images, attributes
        assertThat(lastStatementCount).isEqualTo(2); // product+category+images, attributes
        assertThat(opt).isEqualTo(base);
        assertThat(opt.getImages()).extracting("sortOrder").containsExactly(0, 1);
        assertThat(opt.getAttributes()).hasSize(2);
    }

    @Test
    void batchBaselineGrowsWithIdsOptimizedIsTwoQueries() {
        List<Long> ids = Arrays.asList(productIds.get(0), productIds.get(15), productIds.get(29), productIds.get(1));

        List<ProductSummaryResponse> base = measure(() -> baseline.getBatch(ids));
        long baselineStatements = lastStatementCount;
        List<ProductSummaryResponse> opt = measure(() -> optimized.loadBatch(ids));

        assertThat(baselineStatements).isEqualTo(1 + 3 + 4); // products + 3 categories + 4 image collections
        assertThat(lastStatementCount).isEqualTo(2);
        assertThat(opt).isEqualTo(base);
        assertThat(opt).extracting(ProductSummaryResponse::getId).containsExactlyElementsOf(ids);
    }

    private long lastStatementCount;

    private <T> T measure(Supplier<T> action) {
        entityManager.clear();
        statistics.clear();
        T result = action.get();
        lastStatementCount = statistics.getPrepareStatementCount();
        return result;
    }
}

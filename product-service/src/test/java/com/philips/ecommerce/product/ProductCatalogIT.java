package com.philips.ecommerce.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.product.dto.ProductAttributeRequest;
import com.philips.ecommerce.product.dto.ProductRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end on REAL MySQL 8 + Redis 7 (Testcontainers; run with {@code mvn verify -Pintegration-tests}).
 * Covers what H2 cannot: the Flyway migrations (FULLTEXT index, MySQL types), Hibernate
 * {@code ddl-auto=validate} against that schema, the native FULLTEXT query, and cache-aside through Redis.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProductCatalogIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("philips_product")
            .withUsername("philips")
            .withPassword("philips_pwd");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.redis.timeout", () -> "2s");
        registry.add("app.product.cache.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Flyway flyway;

    @BeforeEach
    void cleanCacheAndCounters() throws Exception {
        mockMvc.perform(delete("/api/products/admin/cache")).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/products/admin/perf/reset")).andExpect(status().isNoContent());
    }

    @Test
    void migrationsApplyAndHibernateValidatesTheSchema() {
        // the context started with ddl-auto=validate, i.e. entities match the Flyway schema
        assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isEqualTo(8);
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = 'product'", String.class);
        assertThat(indexes).contains("ft_product_search", "idx_product_cat_active_price", "idx_product_cat_active_name",
                "idx_product_active_name", "idx_product_active_created", "idx_product_category", "uk_product_sku");
    }

    @Test
    void detailIsCachedAsideAndServedFromRedisOnSecondRead() throws Exception {
        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("PH-SONIC-9900"))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.attributes.length()").value(3));
        Long ttl = redis.getExpire("product:v1:detail:1");
        assertThat(ttl).isBetween(1L, 600L);

        long statementsAfterMiss = hibernateStatements();
        mockMvc.perform(get("/api/products/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.category.slug").value("oral-care"));

        JsonNode cache = perfStats().get("cache");
        assertThat(cache.get("misses").asLong()).isEqualTo(1);
        assertThat(cache.get("hits").asLong()).isEqualTo(1);
        assertThat(statementsAfterMiss).isEqualTo(2); // fetch-join detail + attributes
        assertThat(hibernateStatements()).isEqualTo(statementsAfterMiss); // the hit issued no SQL at all
    }

    @Test
    void updateEvictsDetailAndBumpsListVersionAfterCommit() throws Exception {
        mockMvc.perform(get("/api/products/2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products").param("categoryId", "1")).andExpect(status().isOk());
        assertThat(redis.hasKey("product:v1:detail:2")).isTrue();
        String versionBefore = redis.opsForValue().get("product:v1:list-version");

        ProductRequest change = ProductRequest.builder()
                .sku("PH-SONIC-4300").name("Philips Sonicare ProtectiveClean 4300 Electric Toothbrush")
                .description("Updated description").brand("Philips Sonicare").price(new BigDecimal("54.99"))
                .currency("EUR").categoryId(1L)
                .attributes(Collections.singletonList(new ProductAttributeRequest("Battery life", "Up to 2 weeks")))
                .build();
        mockMvc.perform(put("/api/products/2").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(change)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(54.99))
                .andExpect(jsonPath("$.attributes.length()").value(1));

        assertThat(redis.hasKey("product:v1:detail:2")).isFalse();
        long before = versionBefore == null ? 0 : Long.parseLong(versionBefore);
        assertThat(Long.parseLong(redis.opsForValue().get("product:v1:list-version"))).isEqualTo(before + 1);

        // next reads miss the cache and see the committed change
        mockMvc.perform(get("/api/products/2")).andExpect(jsonPath("$.price").value(54.99));
        mockMvc.perform(get("/api/products").param("categoryId", "1").param("sort", "price,asc"))
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].price").value(54.99));
    }

    @Test
    void fullTextSearchRunsOnMysqlWithFiltersAndSorting() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products").param("q", "Sonicare").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).containsExactly(1L, 3L, 2L); // 329.99, 79.99, 59.99 - sorted by price desc
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("content").get(0).get("thumbnailUrl").asText()).endsWith("front.jpg");

        // prefix semantics + price filter: "airfry" matches "Airfryer", only the cheaper one is <= 150
        mockMvc.perform(get("/api/products").param("q", "airfry").param("maxPrice", "150"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("PH-AIRF-HD9252"));
        // inactive product 20 is never listed, even when it matches
        mockMvc.perform(get("/api/products").param("q", "steriliser"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listingExcludesInactiveButBatchAndDetailIncludeIt() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "100"))
                .andExpect(jsonPath("$.totalElements").value(19));
        mockMvc.perform(get("/api/products/batch").param("ids", "20,19,999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(20))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[1].price").value(5499.00));
        mockMvc.perform(get("/api/products/sku/PH-AVENT-SCF293"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void duplicateSkuIs409() throws Exception {
        ProductRequest duplicate = ProductRequest.builder().sku("PH-SONIC-9900").name("Copy").brand("Philips")
                .price(new BigDecimal("1.00")).categoryId(1L).build();

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    private JsonNode perfStats() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/admin/perf/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryMode").value("optimized"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long hibernateStatements() throws Exception {
        return perfStats().get("hibernate").get("prepareStatementCount").asLong();
    }
}

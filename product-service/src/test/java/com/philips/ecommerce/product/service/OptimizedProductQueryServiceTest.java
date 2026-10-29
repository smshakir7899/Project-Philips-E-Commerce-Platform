package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.product.TestFixtures;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.ProductImageRepository;
import com.philips.ecommerce.product.repository.ProductRepository;
import com.philips.ecommerce.product.repository.ThumbnailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptimizedProductQueryServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductImageRepository imageRepository;
    @Mock
    private ProductCacheService cache;

    private OptimizedProductQueryService service;
    private final Category oralCare = TestFixtures.category(1L, "Oral Care");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // pass-through cache: these tests are about the MySQL access pattern
        lenient().when(cache.getListPage(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        lenient().when(cache.getDetail(any(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        OptimizedProductReader reader = new OptimizedProductReader(productRepository, imageRepository, new ProductMapper());
        service = new OptimizedProductQueryService(reader, cache);
    }

    @Test
    void listLoadsPageWithCategoryAndAllThumbnailsInOneExtraQuery() {
        Product p1 = TestFixtures.product(1L, "PH-1", oralCare);
        Product p2 = TestFixtures.product(2L, "PH-2", oralCare);
        ProductSearchCriteria criteria = ProductSearchCriteria.of(null, 1L, null, null, 0, 20, "price,desc");
        when(productRepository.findAll(ArgumentMatchers.<Specification<Product>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Arrays.asList(p1, p2), criteria.toPageRequest(), 2));
        when(imageRepository.findThumbnails(ArgumentMatchers.<Collection<Long>>any())).thenReturn(Arrays.asList(
                new ThumbnailView(1L, "http://img/1-b.jpg", 11L),
                new ThumbnailView(1L, "http://img/1-a.jpg", 10L), // lower id wins on a sortOrder tie
                new ThumbnailView(2L, "http://img/2.jpg", 20L)));

        PageResponse<ProductSummaryResponse> page = service.list(criteria);

        assertThat(page.getContent()).extracting(ProductSummaryResponse::getThumbnailUrl)
                .containsExactly("http://img/1-a.jpg", "http://img/2.jpg");
        assertThat(page.getContent()).extracting(ProductSummaryResponse::getCategoryName).containsOnly("Oral Care");
        assertThat(page.getTotalElements()).isEqualTo(2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(ArgumentMatchers.<Specification<Product>>any(), pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("price"), Sort.Order.desc("id"));
        verify(imageRepository, times(1)).findThumbnails(ArgumentMatchers.<Collection<Long>>any());
        verify(cache).getListPage(eq(criteria.normalizedKey()), ArgumentMatchers.<Supplier<PageResponse<ProductSummaryResponse>>>any());
    }

    @Test
    void searchUsesFullTextIdPageThenHydratesProductsKeepingThePageOrder() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of("Sonicare tooth-brush!", null, null,
                new BigDecimal("500"), 0, 2, "createdAt,desc");
        when(productRepository.searchIdsFullText(anyString(), isNull(), isNull(), any(BigDecimal.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Number>(Arrays.<Number>asList(BigInteger.valueOf(7), BigInteger.valueOf(3)),
                        criteria.toNativePageRequest(), 5));
        // the IN (...) query returns rows in arbitrary order
        when(productRepository.findWithCategoryByIdIn(Arrays.asList(7L, 3L))).thenReturn(Arrays.asList(
                TestFixtures.product(3L, "PH-3", oralCare), TestFixtures.product(7L, "PH-7", oralCare)));

        PageResponse<ProductSummaryResponse> page = service.list(criteria);

        assertThat(page.getContent()).extracting(ProductSummaryResponse::getId).containsExactly(7L, 3L);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.isLast()).isFalse();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).searchIdsFullText(eq("+sonicare* +tooth* +brush*"), isNull(), isNull(),
                eq(new BigDecimal("500")), pageable.capture());
        // native query -> SQL column names
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("created_at"), Sort.Order.desc("id"));
        verify(productRepository, never()).searchByLike(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void searchWithOnlyShortWordsFallsBackToIndexedPrefixSearch() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of("tv", null, null, null, 0, 20, null);
        when(productRepository.findAll(ArgumentMatchers.<Specification<Product>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<Product>(Collections.<Product>emptyList(), criteria.toPageRequest(), 0));

        PageResponse<ProductSummaryResponse> page = service.list(criteria);

        assertThat(page.getContent()).isEmpty();
        verify(productRepository, never()).searchIdsFullText(anyString(), any(), any(), any(), any(Pageable.class));
        verify(imageRepository, never()).findThumbnails(ArgumentMatchers.<Collection<Long>>any());
    }

    @Test
    void detailUsesFetchJoinQueryAndMapsEverything() {
        Product product = TestFixtures.product(1L, "PH-1", oralCare);
        TestFixtures.image(10L, product, 0);
        product.addAttribute("Battery life", "2 weeks");
        when(productRepository.findDetailById(1L)).thenReturn(Optional.of(product));

        ProductDetailResponse detail = service.getById(1L);

        assertThat(detail.getCategory().getSlug()).isEqualTo("oral-care");
        assertThat(detail.getImages()).hasSize(1);
        assertThat(detail.getAttributes()).extracting("name").containsExactly("Battery life");
        verify(productRepository, never()).findById(any());
    }

    @Test
    void unknownProductIs404() {
        when(productRepository.findDetailById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(42L))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void batchReadsMysqlDirectlyKeepsRequestOrderAndSkipsUnknownIds() {
        Product inactive = TestFixtures.product(20L, "PH-20", oralCare);
        inactive.setActive(false);
        List<Long> ids = Arrays.asList(20L, 999L, 1L);
        when(productRepository.findWithCategoryByIdIn(ids)).thenReturn(Arrays.asList(
                TestFixtures.product(1L, "PH-1", oralCare), inactive));

        List<ProductSummaryResponse> result = service.getBatch(ids);

        assertThat(result).extracting(ProductSummaryResponse::getId).containsExactly(20L, 1L);
        assertThat(result.get(0).isActive()).isFalse();
        verify(cache, never()).getListPage(anyString(), ArgumentMatchers.<Supplier<PageResponse<ProductSummaryResponse>>>any());
        verify(cache, never()).getDetail(any(), ArgumentMatchers.<Supplier<ProductDetailResponse>>any());
    }

    @Test
    void booleanModeQueryStripsOperatorsStopwordsAndShortTokens() {
        assertThat(OptimizedProductReader.toBooleanModeQuery("+air -fryer \"XXL\" (premium)~ @3"))
                .isEqualTo("+air* +fryer* +xxl* +premium*");
        assertThat(OptimizedProductReader.toBooleanModeQuery("the shaver with blades"))
                .isEqualTo("+shaver* +blades*");
        assertThat(OptimizedProductReader.toBooleanModeQuery("tv a")).isNull();
        assertThat(OptimizedProductReader.toBooleanModeQuery("Kaffeemaschine Süß")).isEqualTo("+kaffeemaschine* +süß*");
    }

    @Test
    void reportsOptimizedMode() {
        assertThat(service.mode()).isEqualTo("optimized");
    }
}

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
import com.philips.ecommerce.product.repository.ProductRepository;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaselineProductQueryServiceTest {

    @Mock
    private ProductRepository productRepository;

    private BaselineProductQueryService service;
    private final Category category = TestFixtures.category(2L, "Smart Lighting");

    @BeforeEach
    void setUp() {
        service = new BaselineProductQueryService(productRepository, new ProductMapper());
    }

    @Test
    void listUsesEntityQueryAndDerivesThumbnailFromTheImagesCollection() {
        Product product = TestFixtures.product(4L, "PH-HUE", category);
        TestFixtures.image(41L, product, 1);
        TestFixtures.image(40L, product, 0);
        ProductSearchCriteria criteria = ProductSearchCriteria.of(null, 2L, new BigDecimal("10"), null, 0, 20, "name");
        when(productRepository.findActiveForListing(eq(2L), eq(new BigDecimal("10")), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.singletonList(product), criteria.toPageRequest(), 1));

        PageResponse<ProductSummaryResponse> page = service.list(criteria);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getThumbnailUrl()).endsWith("img-40.jpg");
        assertThat(page.getContent().get(0).getCategoryName()).isEqualTo("Smart Lighting");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveForListing(eq(2L), eq(new BigDecimal("10")), isNull(), pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("name"), Sort.Order.asc("id"));
        verify(productRepository, never()).findAll(ArgumentMatchers.<Specification<Product>>any(), any(Pageable.class));
    }

    @Test
    void searchUsesLeadingWildcardLikeWithEscapedUserInput() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of("Hue_50%", null, null, null, 0, 20, null);
        when(productRepository.searchByLike(anyString(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<Product>(Collections.<Product>emptyList(), criteria.toPageRequest(), 0));

        service.list(criteria);

        verify(productRepository).searchByLike(eq("%hue!_50!%%"), isNull(), isNull(), isNull(), any(Pageable.class));
        verify(productRepository, never()).searchIdsFullText(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void detailUsesPlainFindById() {
        Product product = TestFixtures.product(1L, "PH-1", category);
        product.addAttribute("Socket", "E27");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductDetailResponse detail = service.getById(1L);

        assertThat(detail.getAttributes()).hasSize(1);
        verify(productRepository, never()).findDetailById(any());
    }

    @Test
    void unknownSkuIs404() {
        when(productRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySku("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void batchKeepsRequestOrderAndSkipsUnknownIds() {
        List<Long> ids = Arrays.asList(5L, 404L, 4L);
        when(productRepository.findAllById(ids)).thenReturn(Arrays.asList(
                TestFixtures.product(4L, "PH-4", category), TestFixtures.product(5L, "PH-5", category)));

        List<ProductSummaryResponse> result = service.getBatch(ids);

        assertThat(result).extracting(ProductSummaryResponse::getId).containsExactly(5L, 4L);
        assertThat(result.get(0).getThumbnailUrl()).isNull();
    }

    @Test
    void reportsBaselineMode() {
        assertThat(service.mode()).isEqualTo("baseline");
    }
}

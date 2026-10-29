package com.philips.ecommerce.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.web.GlobalExceptionHandler;
import com.philips.ecommerce.product.TestFixtures;
import com.philips.ecommerce.product.dto.ProductRequest;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSortField;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.service.ProductCommandService;
import com.philips.ecommerce.product.service.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice: only MVC infrastructure + ProductController. common-lib's GlobalExceptionHandler is registered by an
 * auto-configuration that the slice does not load, so it is imported explicitly to test the real error body.
 */
@WebMvcTest(ProductController.class)
@Import(GlobalExceptionHandler.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private ProductQueryService queryService;
    @MockBean
    private ProductCommandService commandService;

    @Test
    void getByIdReturns200WithDetail() throws Exception {
        when(queryService.getById(1L)).thenReturn(TestFixtures.detail(1L));

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("PH-TEST-1"))
                .andExpect(jsonPath("$.price").value(59.99))
                .andExpect(jsonPath("$.category.slug").value("oral-care"))
                .andExpect(jsonPath("$.createdAt").value("2024-01-01T10:00:00Z"));
    }

    @Test
    void unknownProductReturns404WithErrorCode() throws Exception {
        when(queryService.getById(999L)).thenThrow(new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product 999 not found"));

        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/products/999"));
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        ProductRequest invalid = ProductRequest.builder()
                .sku("")
                .name("Philips Airfryer")
                .brand("Philips")
                .price(new BigDecimal("0.001"))
                .currency("eur")
                .build();

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("sku", "price", "currency", "categoryId")));
        verify(commandService, never()).create(any());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        ProductRequest request = ProductRequest.builder().sku("PH-NEW-1").name("Philips New").brand("Philips")
                .price(new BigDecimal("49.99")).categoryId(1L).build();
        when(commandService.create(any(ProductRequest.class))).thenReturn(TestFixtures.detail(21L));

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/products/21")))
                .andExpect(jsonPath("$.id").value(21));
    }

    @Test
    void unsupportedSortFieldReturns400InvalidSortField() throws Exception {
        mockMvc.perform(get("/api/products").param("sort", "color,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_FIELD"));
        verify(queryService, never()).list(any());
    }

    @Test
    void pageSizeAbove100Returns400() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_REQUEST"));
    }

    @Test
    void listPassesNormalizedCriteria() throws Exception {
        when(queryService.list(any())).thenReturn(new PageResponse<>(Collections.singletonList(
                ProductSummaryResponse.builder().id(1L).sku("PH-1").build()), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/products").param("q", "  sonicare ").param("categoryId", "1")
                        .param("minPrice", "10").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("PH-1"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));

        ArgumentCaptor<ProductSearchCriteria> criteria = ArgumentCaptor.forClass(ProductSearchCriteria.class);
        verify(queryService).list(criteria.capture());
        assertThat(criteria.getValue().getQ()).isEqualTo("sonicare");
        assertThat(criteria.getValue().getCategoryId()).isEqualTo(1L);
        assertThat(criteria.getValue().getSortField()).isEqualTo(ProductSortField.PRICE);
        assertThat(criteria.getValue().getSortDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(criteria.getValue().getSize()).isEqualTo(20);
    }

    @Test
    void batchDeduplicatesIdsKeepingOrder() throws Exception {
        when(queryService.getBatch(Arrays.asList(3L, 1L))).thenReturn(Collections.<ProductSummaryResponse>emptyList());

        mockMvc.perform(get("/api/products/batch").param("ids", "3,1,3"))
                .andExpect(status().isOk());

        verify(queryService).getBatch(Arrays.asList(3L, 1L));
    }

    @Test
    void batchWithMoreThan100IdsReturns400() throws Exception {
        List<String> ids = LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.toList());

        mockMvc.perform(get("/api/products/batch").param("ids", String.join(",", ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_IDS"));
    }
}

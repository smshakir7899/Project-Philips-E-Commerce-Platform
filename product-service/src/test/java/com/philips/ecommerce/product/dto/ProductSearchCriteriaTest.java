package com.philips.ecommerce.product.dto;

import com.philips.ecommerce.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchCriteriaTest {

    @Test
    void equivalentQueriesProduceTheSameCacheKey() {
        ProductSearchCriteria a = ProductSearchCriteria.of(" Sonicare ", 1L, new BigDecimal("10.00"), null, 0, 20, "price,DESC");
        ProductSearchCriteria b = ProductSearchCriteria.of("sonicare", 1L, new BigDecimal("10"), null, 0, 20, "price,desc");

        assertThat(a.normalizedKey()).isEqualTo(b.normalizedKey())
                .isEqualTo("q=sonicare|cat=1|min=10|max=|page=0|size=20|sort=price,DESC");
    }

    @Test
    void defaultsToNameAscendingWithIdTieBreaker() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of(null, null, null, null, 2, 10, null);

        assertThat(criteria.toPageRequest().getSort())
                .containsExactly(Sort.Order.asc("name"), Sort.Order.asc("id"));
        assertThat(criteria.toPageRequest().getOffset()).isEqualTo(20);
        assertThat(criteria.hasText()).isFalse();
    }

    @Test
    void nativeSortUsesColumnNames() {
        ProductSearchCriteria criteria = ProductSearchCriteria.of("x", null, null, null, 0, 10, "createdAt,desc");

        assertThat(criteria.toNativePageRequest().getSort())
                .containsExactly(Sort.Order.desc("created_at"), Sort.Order.desc("id"));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, null, null, 0, 20, "description"))
                .isInstanceOf(BadRequestException.class).extracting("code").isEqualTo("INVALID_SORT_FIELD");
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, null, null, 0, 20, "name,up"))
                .isInstanceOf(BadRequestException.class).extracting("code").isEqualTo("INVALID_SORT_DIRECTION");
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, null, null, -1, 20, null))
                .isInstanceOf(BadRequestException.class).extracting("code").isEqualTo("INVALID_PAGE_REQUEST");
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, null, null, 0, 0, null))
                .isInstanceOf(BadRequestException.class).extracting("code").isEqualTo("INVALID_PAGE_REQUEST");
        assertThatThrownBy(() -> ProductSearchCriteria.of(null, null, new BigDecimal("50"), new BigDecimal("10"), 0, 20, null))
                .isInstanceOf(BadRequestException.class).extracting("code").isEqualTo("INVALID_PRICE_RANGE");
    }
}

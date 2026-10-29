package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.product.TestFixtures;
import com.philips.ecommerce.product.dto.ProductAttributeRequest;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductRequest;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.CategoryRepository;
import com.philips.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transaction synchronization is initialized manually to simulate "inside a Spring-managed transaction"
 * without starting Spring: the test can then prove that eviction is only REGISTERED during the method and
 * EXECUTED in the afterCommit callback.
 */
@ExtendWith(MockitoExtension.class)
class ProductCommandServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductCacheService cacheService;

    private ProductCommandService service;
    private final Category category = TestFixtures.category(1L, "Oral Care");

    @BeforeEach
    void setUp() {
        service = new ProductCommandService(productRepository, categoryRepository, new ProductMapper(),
                new ProductCacheEvictor(cacheService));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void duplicateSkuIsRejectedWith409() {
        when(productRepository.existsBySku("PH-SONIC-9900")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("PH-SONIC-9900", 1L)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("DUPLICATE_SKU");
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateDetectedByUniqueConstraintIsAlso409() {
        when(productRepository.existsBySku("PH-NEW")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_product_sku"));

        assertThatThrownBy(() -> service.create(request("PH-NEW", 1L)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("DUPLICATE_SKU");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void missingCategoryIs404() {
        when(productRepository.existsBySku("PH-NEW")).thenReturn(false);
        when(categoryRepository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("PH-NEW", 77L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("CATEGORY_NOT_FOUND");
    }

    @Test
    void createEvictsCacheOnlyAfterCommit() {
        when(productRepository.existsBySku("PH-NEW")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Product product = inv.getArgument(0);
            product.setId(21L); // IDENTITY id assigned by the INSERT
            return product;
        });

        ProductDetailResponse created = service.create(request("PH-NEW", 1L));

        assertThat(created.getId()).isEqualTo(21L);
        assertThat(created.getCurrency()).isEqualTo("EUR"); // defaulted
        assertThat(created.getAttributes()).extracting("name").containsExactly("Colour");
        assertThat(created.getPrice()).isEqualByComparingTo("19.90");
        assertThat(created.getPrice().scale()).isEqualTo(2);
        // still "inside the transaction": nothing evicted yet, but a synchronization is registered
        verify(cacheService, never()).evictProduct(anyLong());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(TransactionSynchronization::afterCommit); // what Spring does on commit

        verify(cacheService).evictProduct(21L);
    }

    @Test
    void rollbackNeverEvicts() {
        Product existing = TestFixtures.product(3L, "PH-3", category);
        when(productRepository.findById(3L)).thenReturn(Optional.of(existing));

        service.delete(3L);
        // Spring calls only afterCompletion(STATUS_ROLLED_BACK) on rollback, never afterCommit
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cacheService, never()).evictProduct(anyLong());
    }

    @Test
    void updateToAnExistingSkuOfAnotherProductIs409() {
        when(productRepository.findById(3L)).thenReturn(Optional.of(TestFixtures.product(3L, "PH-3", category)));
        when(productRepository.existsBySkuAndIdNot("PH-4", 3L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(3L, request("PH-4", 1L)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("DUPLICATE_SKU");
    }

    @Test
    void updateReplacesFieldsAndAttributes() {
        Product existing = TestFixtures.product(3L, "PH-3", category);
        existing.addAttribute("Old", "value");
        Category lighting = TestFixtures.category(2L, "Smart Lighting");
        when(productRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(lighting));
        when(productRepository.saveAndFlush(existing)).thenReturn(existing);

        ProductDetailResponse updated = service.update(3L, request("PH-3", 2L));

        assertThat(updated.getCategory().getName()).isEqualTo("Smart Lighting");
        assertThat(updated.getAttributes()).extracting("name").containsExactly("Colour");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void deleteIsSoftAndEvictsAfterCommit() {
        Product existing = TestFixtures.product(3L, "PH-3", category);
        when(productRepository.findById(3L)).thenReturn(Optional.of(existing));

        service.delete(3L);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(existing.isActive()).isFalse();
        verify(productRepository, never()).delete(any());
        verify(cacheService).evictProduct(3L);
    }

    @Test
    void deleteOfUnknownProductIs404() {
        when(productRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("PRODUCT_NOT_FOUND");
    }

    private static ProductRequest request(String sku, Long categoryId) {
        return ProductRequest.builder()
                .sku(sku)
                .name("Philips Test Product")
                .description("desc")
                .brand("Philips")
                .price(new BigDecimal("19.9"))
                .categoryId(categoryId)
                .attributes(Arrays.asList(new ProductAttributeRequest("Colour", "White")))
                .build();
    }
}

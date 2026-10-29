package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.product.TestFixtures;
import com.philips.ecommerce.product.dto.ProductImageResponse;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.entity.ProductImage;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.ProductImageRepository;
import com.philips.ecommerce.product.repository.ProductRepository;
import com.philips.ecommerce.product.service.storage.ProductImageStorage;
import com.philips.ecommerce.product.service.storage.StoredImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductImageRepository imageRepository;
    @Mock
    private ProductImageStorage storage;
    @Mock
    private ProductCacheService cacheService;

    private ProductImageService service;
    private final Product product = TestFixtures.product(5L, "PH-5", TestFixtures.category(1L, "Oral Care"));

    @BeforeEach
    void setUp() {
        service = new ProductImageService(productRepository, imageRepository, storage, new ProductMapper(),
                new ProductCacheEvictor(cacheService), new NoOpTransactionManager());
    }

    @Test
    void uploadStoresObjectThenRowAndEvictsAfterCommit() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG);
        when(productRepository.existsById(5L)).thenReturn(true);
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(storage.store(5L, "image/png", PNG)).thenReturn(new StoredImage("products/5/x.png", "http://img/x.png", "image/png", PNG.length));
        when(imageRepository.findMaxSortOrder(5L)).thenReturn(-1);
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> {
            ProductImage image = inv.getArgument(0);
            image.setId(100L);
            return image;
        });

        ProductImageResponse response = service.upload(5L, file);

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getSortOrder()).isZero(); // first image becomes the thumbnail
        verify(cacheService).evictProduct(5L);
        verify(storage, never()).delete(any());
    }

    @Test
    void databaseFailureAfterUploadDeletesTheObject() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG);
        when(productRepository.existsById(5L)).thenReturn(true);
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(storage.store(5L, "image/png", PNG)).thenReturn(new StoredImage("products/5/x.png", "http://img/x.png", "image/png", PNG.length));
        when(imageRepository.findMaxSortOrder(5L)).thenReturn(0);
        when(imageRepository.save(any(ProductImage.class))).thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> service.upload(5L, file)).isInstanceOf(QueryTimeoutException.class);

        verify(storage).delete("products/5/x.png"); // compensation
        verify(cacheService, never()).evictProduct(anyLong());
    }

    @Test
    void uploadForUnknownProductNeverTouchesStorage() {
        when(productRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.upload(9L, new MockMultipartFile("file", PNG)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(storage, never()).store(anyLong(), any(), any());
    }

    @Test
    void deletingTheThumbnailPromotesTheNextImageAndToleratesStorageFailure() {
        ProductImage thumbnail = TestFixtures.image(1L, product, 0);
        ProductImage next = TestFixtures.image(2L, product, 3);
        when(imageRepository.findByIdAndProductId(1L, 5L)).thenReturn(Optional.of(thumbnail));
        when(imageRepository.findFirstByProductIdOrderBySortOrderAscIdAsc(5L)).thenReturn(Optional.of(next));
        doThrow(new ServiceUnavailableException("STORAGE_UNAVAILABLE", "down")).when(storage).delete(eq(thumbnail.getS3Key()));

        service.delete(5L, 1L);

        assertThat(next.getSortOrder()).isZero();
        verify(imageRepository).delete(thumbnail);
        verify(cacheService).evictProduct(5L);
    }

    /** Minimal transaction manager: lets TransactionTemplate run callbacks + synchronizations without a database. */
    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

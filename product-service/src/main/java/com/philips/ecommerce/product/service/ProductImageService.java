package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.product.dto.PresignedUrlResponse;
import com.philips.ecommerce.product.dto.ProductImageResponse;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.entity.ProductImage;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.ProductImageRepository;
import com.philips.ecommerce.product.repository.ProductRepository;
import com.philips.ecommerce.product.service.storage.ProductImageStorage;
import com.philips.ecommerce.product.service.storage.StoredImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Coordinates two resources that cannot share a transaction: S3 (object) and MySQL (metadata row).
 * <p>
 * Upload order: validate -> upload to S3 (OUTSIDE any DB transaction, so a slow upload never holds a JDBC
 * connection or row locks) -> short DB transaction inserting the row. If the DB step fails, the uploaded object
 * is deleted again (compensating action), so no orphan object is left behind.
 * <p>
 * Delete order: DB row first (commit), then the object (best effort). If the object delete fails we only leak
 * an unreferenced object (cheap, can be swept later), never a row pointing to a missing object.
 * Transactions are demarcated with a {@link TransactionTemplate} because the remote call must sit between them.
 */
@Slf4j
@Service
public class ProductImageService {

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ProductImageStorage storage;
    private final ProductMapper mapper;
    private final ProductCacheEvictor cacheEvictor;
    private final TransactionTemplate transactionTemplate;

    public ProductImageService(ProductRepository productRepository, ProductImageRepository imageRepository,
                               ProductImageStorage storage, ProductMapper mapper, ProductCacheEvictor cacheEvictor,
                               PlatformTransactionManager transactionManager) {
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
        this.mapper = mapper;
        this.cacheEvictor = cacheEvictor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ProductImageResponse upload(Long productId, MultipartFile file) {
        if (!productRepository.existsById(productId)) {
            throw ProductNotFound.byId(productId);
        }
        final StoredImage stored = storage.store(productId, file.getContentType(), readBytes(file));
        try {
            ProductImageResponse response = transactionTemplate.execute(status -> {
                Product product = productRepository.findById(productId).orElseThrow(() -> ProductNotFound.byId(productId));
                // First image gets sortOrder 0 = thumbnail. Concurrent uploads may pick the same number; ties are
                // broken by id everywhere, so this is harmless.
                int sortOrder = imageRepository.findMaxSortOrder(productId) + 1;
                ProductImage image = imageRepository.save(new ProductImage(product, stored.getKey(), stored.getUrl(),
                        stored.getContentType(), sortOrder));
                cacheEvictor.evictAfterCommit(productId);
                return mapper.toImage(image);
            });
            log.info("Product {} got image {} ({})", productId, response.getId(), stored.getKey());
            return response;
        } catch (RuntimeException ex) {
            compensate(stored.getKey(), ex);
            throw ex;
        }
    }

    public void delete(Long productId, Long imageId) {
        String key = transactionTemplate.execute(status -> {
            ProductImage image = imageRepository.findByIdAndProductId(imageId, productId)
                    .orElseThrow(() -> imageNotFound(productId, imageId));
            boolean wasThumbnail = image.getSortOrder() == ProductImage.THUMBNAIL_SORT_ORDER;
            imageRepository.delete(image);
            imageRepository.flush();
            if (wasThumbnail) {
                // promote the next image so listings keep showing a thumbnail
                imageRepository.findFirstByProductIdOrderBySortOrderAscIdAsc(productId)
                        .ifPresent(next -> next.setSortOrder(ProductImage.THUMBNAIL_SORT_ORDER));
            }
            cacheEvictor.evictAfterCommit(productId);
            return image.getS3Key();
        });
        try {
            storage.delete(key);
        } catch (ServiceUnavailableException ex) {
            log.warn("Image {} row deleted but S3 object {} could not be removed (orphan object): {}",
                    imageId, key, ex.getMessage());
        }
    }

    public PresignedUrlResponse presignedUrl(Long productId, Long imageId) {
        ProductImage image = imageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> imageNotFound(productId, imageId));
        return storage.presignGet(image.getS3Key());
    }

    private void compensate(String key, RuntimeException cause) {
        log.warn("Persisting image metadata failed ({}), deleting uploaded object {}", cause.getMessage(), key);
        try {
            storage.delete(key);
        } catch (RuntimeException deleteFailure) {
            cause.addSuppressed(deleteFailure);
            log.error("Compensation failed, orphan S3 object {} must be cleaned up", key, deleteFailure);
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes(); // bounded by spring.servlet.multipart.max-file-size (5MB)
        } catch (IOException ex) {
            throw new BadRequestException("INVALID_FILE", "The uploaded file could not be read");
        }
    }

    private static ResourceNotFoundException imageNotFound(Long productId, Long imageId) {
        return new ResourceNotFoundException("IMAGE_NOT_FOUND", "Image " + imageId + " of product " + productId + " not found");
    }
}

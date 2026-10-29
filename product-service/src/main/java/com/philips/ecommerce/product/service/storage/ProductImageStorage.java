package com.philips.ecommerce.product.service.storage;

import com.philips.ecommerce.product.dto.PresignedUrlResponse;

/**
 * Binary storage for product images. An interface so the service layer does not depend on the AWS SDK and tests
 * can substitute it; the only production implementation is {@link S3ProductImageStorage} (AWS S3 or MinIO).
 */
public interface ProductImageStorage {

    /**
     * Validates and uploads an image.
     *
     * @throws com.philips.ecommerce.common.exception.BadRequestException         UNSUPPORTED_IMAGE_TYPE / EMPTY_FILE
     * @throws com.philips.ecommerce.common.exception.BusinessException           FILE_TOO_LARGE (413)
     * @throws com.philips.ecommerce.common.exception.ServiceUnavailableException STORAGE_UNAVAILABLE
     */
    StoredImage store(Long productId, String contentType, byte[] content);

    /** @throws com.philips.ecommerce.common.exception.ServiceUnavailableException STORAGE_UNAVAILABLE */
    void delete(String key);

    /** Time-limited GET URL (works for private buckets). */
    PresignedUrlResponse presignGet(String key);
}

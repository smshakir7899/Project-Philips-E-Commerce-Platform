package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.exception.ResourceNotFoundException;

/** Factory for the error codes of this service (keeps codes and messages consistent). */
final class ProductNotFound {

    static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

    private ProductNotFound() {
    }

    static ResourceNotFoundException byId(Long id) {
        return new ResourceNotFoundException(PRODUCT_NOT_FOUND, "Product " + id + " not found");
    }

    static ResourceNotFoundException bySku(String sku) {
        return new ResourceNotFoundException(PRODUCT_NOT_FOUND, "Product with sku " + sku + " not found");
    }
}

package com.philips.ecommerce.product.service.storage;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Result of a successful upload. */
@Getter
@AllArgsConstructor
public class StoredImage {
    /** Object key, e.g. products/7/2b1c...e4.png */
    private final String key;
    /** Public URL (public-base-url + "/" + key). */
    private final String url;
    private final String contentType;
    private final long sizeBytes;
}

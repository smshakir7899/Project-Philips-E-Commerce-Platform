package com.philips.ecommerce.product.repository;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DTO projection filled by a JPQL constructor expression ("select new ..."): only the three needed columns are
 * read, no ProductImage entities are instantiated or registered in the persistence context.
 */
@Getter
@AllArgsConstructor
public class ThumbnailView {
    private final Long productId;
    private final String url;
    private final Long imageId;
}

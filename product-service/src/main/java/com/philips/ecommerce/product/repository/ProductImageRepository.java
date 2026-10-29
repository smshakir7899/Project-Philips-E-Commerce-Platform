package com.philips.ecommerce.product.repository;

import com.philips.ecommerce.product.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /**
     * Thumbnails (sortOrder 0) for a whole result page in ONE query, served by
     * idx_product_image_product_sort (product_id, sort_order). This replaces N lazy collection loads.
     */
    @Query("select new com.philips.ecommerce.product.repository.ThumbnailView(i.product.id, i.url, i.id)"
            + " from ProductImage i where i.product.id in :productIds and i.sortOrder = 0")
    List<ThumbnailView> findThumbnails(@Param("productIds") Collection<Long> productIds);

    Optional<ProductImage> findByIdAndProductId(Long id, Long productId);

    @Query("select coalesce(max(i.sortOrder), -1) from ProductImage i where i.product.id = :productId")
    int findMaxSortOrder(@Param("productId") Long productId);

    Optional<ProductImage> findFirstByProductIdOrderBySortOrderAscIdAsc(Long productId);
}

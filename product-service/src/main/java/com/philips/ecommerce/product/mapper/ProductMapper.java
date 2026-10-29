package com.philips.ecommerce.product.mapper;

import com.philips.ecommerce.product.dto.CategoryResponse;
import com.philips.ecommerce.product.dto.ProductAttributeResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductImageResponse;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.entity.ProductAttribute;
import com.philips.ecommerce.product.entity.ProductImage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Hand-written entity -> DTO mapping (no MapStruct, so every association access is explicit).
 * <p>
 * IMPORTANT for the performance experiment: which getters are called here decides which lazy associations
 * get initialized. {@link #toSummary(Product, String)} takes the thumbnail URL as a parameter so that the
 * caller decides HOW it is obtained (one batched query in the optimized path vs. touching
 * {@code product.getImages()} per product in the baseline path).
 * Must be called inside an open persistence context (transaction), because open-in-view is disabled.
 */
@Component
public class ProductMapper {

    public CategoryResponse toCategory(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .build();
    }

    /** Touches product.getCategory().getName(): initializes the category proxy unless it was fetched already. */
    public ProductSummaryResponse toSummary(Product product, String thumbnailUrl) {
        Category category = product.getCategory();
        return ProductSummaryResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .brand(product.getBrand())
                .price(product.getPrice())
                .currency(product.getCurrency())
                .categoryId(category.getId())
                .categoryName(category.getName())
                .active(product.isActive())
                .thumbnailUrl(thumbnailUrl)
                .build();
    }

    /** Touches category, images and attributes. */
    public ProductDetailResponse toDetail(Product product) {
        List<ProductImageResponse> images = product.getImages().stream()
                .map(this::toImage)
                .collect(Collectors.toList());
        List<ProductAttributeResponse> attributes = product.getAttributes().stream()
                .map(this::toAttribute)
                .collect(Collectors.toList());
        return ProductDetailResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .brand(product.getBrand())
                .price(product.getPrice())
                .currency(product.getCurrency())
                .active(product.isActive())
                .category(toCategory(product.getCategory()))
                .images(images)
                .attributes(attributes)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    public ProductImageResponse toImage(ProductImage image) {
        return ProductImageResponse.builder()
                .id(image.getId())
                .url(image.getUrl())
                .contentType(image.getContentType())
                .sortOrder(image.getSortOrder())
                .build();
    }

    public ProductAttributeResponse toAttribute(ProductAttribute attribute) {
        return new ProductAttributeResponse(attribute.getName(), attribute.getValue());
    }
}

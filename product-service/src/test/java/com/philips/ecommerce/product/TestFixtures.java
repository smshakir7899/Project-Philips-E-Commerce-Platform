package com.philips.ecommerce.product;

import com.philips.ecommerce.product.dto.CategoryResponse;
import com.philips.ecommerce.product.dto.ProductAttributeResponse;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductImageResponse;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.entity.ProductImage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;

/** Small object mothers shared by the unit tests. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Category category(Long id, String name) {
        Category category = new Category(name, name.toLowerCase().replace(' ', '-'), name + " products");
        category.setId(id);
        return category;
    }

    public static Product product(Long id, String sku, Category category) {
        Product product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName("Product " + sku);
        product.setDescription("Description of " + sku);
        product.setBrand("Philips");
        product.setPrice(new BigDecimal("99.99"));
        product.setCurrency("EUR");
        product.setCategory(category);
        product.setActive(true);
        product.setCreatedAt(Instant.parse("2024-01-01T10:00:00Z"));
        product.setUpdatedAt(Instant.parse("2024-01-01T10:00:00Z"));
        product.setVersion(0L);
        return product;
    }

    public static ProductImage image(Long id, Product product, int sortOrder) {
        ProductImage image = new ProductImage(product, "products/" + product.getId() + "/img-" + id + ".jpg",
                "http://localhost:9000/philips-product-images/products/" + product.getId() + "/img-" + id + ".jpg",
                "image/jpeg", sortOrder);
        image.setId(id);
        product.getImages().add(image);
        return image;
    }

    public static ProductDetailResponse detail(Long id) {
        return ProductDetailResponse.builder()
                .id(id)
                .sku("PH-TEST-" + id)
                .name("Philips Test " + id)
                .description("Test product")
                .brand("Philips")
                .price(new BigDecimal("59.99"))
                .currency("EUR")
                .active(true)
                .category(new CategoryResponse(1L, "Oral Care", "oral-care", "Toothbrushes"))
                .images(Collections.singletonList(new ProductImageResponse(10L, "http://img/1.jpg", "image/jpeg", 0)))
                .attributes(Collections.singletonList(new ProductAttributeResponse("Battery", "2 weeks")))
                .createdAt(Instant.parse("2024-01-01T10:00:00Z"))
                .updatedAt(Instant.parse("2024-01-02T10:00:00Z"))
                .build();
    }
}

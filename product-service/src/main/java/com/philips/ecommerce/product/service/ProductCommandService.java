package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.product.dto.ProductAttributeRequest;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductRequest;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.entity.Product;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.CategoryRepository;
import com.philips.ecommerce.product.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.RoundingMode;

/**
 * Product writes. Each public method is one transaction; the cache is invalidated only after it commits
 * ({@link ProductCacheEvictor}).
 */
@Slf4j
@Service
public class ProductCommandService {

    static final String DUPLICATE_SKU = "DUPLICATE_SKU";
    static final String CATEGORY_NOT_FOUND = "CATEGORY_NOT_FOUND";
    private static final String DEFAULT_CURRENCY = "EUR";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper mapper;
    private final ProductCacheEvictor cacheEvictor;

    public ProductCommandService(ProductRepository productRepository, CategoryRepository categoryRepository,
                                 ProductMapper mapper, ProductCacheEvictor cacheEvictor) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.mapper = mapper;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * @throws ConflictException         DUPLICATE_SKU
     * @throws ResourceNotFoundException CATEGORY_NOT_FOUND
     */
    @Transactional
    public ProductDetailResponse create(ProductRequest request) {
        // Fast, friendly check. Not sufficient alone (two concurrent creates can both pass it), hence the
        // unique constraint + the DataIntegrityViolationException translation in saveChecked().
        if (productRepository.existsBySku(request.getSku())) {
            throw duplicateSku(request.getSku());
        }
        Category category = loadCategory(request.getCategoryId());
        Product product = new Product();
        apply(product, request, category);
        Product saved = saveChecked(product, request.getSku());
        cacheEvictor.evictAfterCommit(saved.getId()); // new product changes list pages -> bump list version
        log.info("Created product {} ({})", saved.getId(), saved.getSku());
        return mapper.toDetail(saved);
    }

    /**
     * Full replacement of the editable fields, attributes included.
     *
     * @throws ResourceNotFoundException PRODUCT_NOT_FOUND / CATEGORY_NOT_FOUND
     * @throws ConflictException         DUPLICATE_SKU
     */
    @Transactional
    public ProductDetailResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id).orElseThrow(() -> ProductNotFound.byId(id));
        if (!product.getSku().equals(request.getSku()) && productRepository.existsBySkuAndIdNot(request.getSku(), id)) {
            throw duplicateSku(request.getSku());
        }
        Category category = product.getCategory().getId().equals(request.getCategoryId())
                ? product.getCategory()
                : loadCategory(request.getCategoryId());
        apply(product, request, category);
        Product saved = saveChecked(product, request.getSku());
        cacheEvictor.evictAfterCommit(id);
        log.info("Updated product {} ({})", id, saved.getSku());
        return mapper.toDetail(saved);
    }

    /**
     * Soft delete: the row stays (orders reference the product id and SKU) but it disappears from listings.
     * Idempotent: deleting an inactive product again is a no-op that still returns 204.
     */
    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findById(id).orElseThrow(() -> ProductNotFound.byId(id));
        if (product.isActive()) {
            product.setActive(false); // dirty checking issues the UPDATE at commit
            log.info("Deactivated product {} ({})", id, product.getSku());
        }
        cacheEvictor.evictAfterCommit(id);
    }

    private void apply(Product product, ProductRequest request, Category category) {
        product.setSku(request.getSku().trim());
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription());
        product.setBrand(request.getBrand().trim());
        // @Digits(fraction = 2) guarantees at most 2 decimals, so normalizing the scale never rounds.
        product.setPrice(request.getPrice().setScale(2, RoundingMode.UNNECESSARY));
        product.setCurrency(StringUtils.hasText(request.getCurrency()) ? request.getCurrency() : DEFAULT_CURRENCY);
        product.setCategory(category);
        product.clearAttributes();
        if (request.getAttributes() != null) {
            for (ProductAttributeRequest attribute : request.getAttributes()) {
                product.addAttribute(attribute.getName().trim(), attribute.getValue().trim());
            }
        }
    }

    /**
     * saveAndFlush forces the INSERT/UPDATE now, inside this method, so a unique-key violation from a concurrent
     * request surfaces here and can be translated into the domain error instead of a generic 409 at commit.
     */
    private Product saveChecked(Product product, String sku) {
        try {
            return productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException ex) {
            // The only unique constraint on product is uk_product_sku; the category FK was verified above.
            throw duplicateSku(sku);
        }
    }

    private Category loadCategory(Long categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(() ->
                new ResourceNotFoundException(CATEGORY_NOT_FOUND, "Category " + categoryId + " not found"));
    }

    private ConflictException duplicateSku(String sku) {
        return new ConflictException(DUPLICATE_SKU, "A product with sku " + sku + " already exists");
    }
}

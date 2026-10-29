package com.philips.ecommerce.product.service;

import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.product.dto.CategoryRequest;
import com.philips.ecommerce.product.dto.CategoryResponse;
import com.philips.ecommerce.product.entity.Category;
import com.philips.ecommerce.product.mapper.ProductMapper;
import com.philips.ecommerce.product.repository.CategoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductMapper mapper;

    public CategoryService(CategoryRepository categoryRepository, ProductMapper mapper) {
        this.categoryRepository = categoryRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return categoryRepository.findAllByOrderByNameAsc().stream().map(mapper::toCategory).collect(Collectors.toList());
    }

    /** @throws ResourceNotFoundException CATEGORY_NOT_FOUND */
    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        return categoryRepository.findById(id).map(mapper::toCategory).orElseThrow(() ->
                new ResourceNotFoundException(ProductCommandService.CATEGORY_NOT_FOUND, "Category " + id + " not found"));
    }

    /** @throws ConflictException DUPLICATE_CATEGORY_SLUG */
    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsBySlug(request.getSlug())) {
            throw duplicateSlug(request.getSlug());
        }
        try {
            Category saved = categoryRepository.saveAndFlush(
                    new Category(request.getName().trim(), request.getSlug(), request.getDescription()));
            return mapper.toCategory(saved);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateSlug(request.getSlug()); // concurrent create with the same slug
        }
    }

    private ConflictException duplicateSlug(String slug) {
        return new ConflictException("DUPLICATE_CATEGORY_SLUG", "A category with slug " + slug + " already exists");
    }
}

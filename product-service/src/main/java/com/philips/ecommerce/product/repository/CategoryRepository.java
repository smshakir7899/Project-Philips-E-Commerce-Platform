package com.philips.ecommerce.product.repository;

import com.philips.ecommerce.product.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    boolean existsBySlug(String slug);

    List<Category> findAllByOrderByNameAsc();
}

package com.philips.ecommerce.product.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Product category (e.g. "Oral Care"). Small, rarely changing reference data.
 * <p>
 * Entities use {@code @Getter/@Setter} instead of Lombok {@code @Data}: a generated equals/hashCode/toString that
 * touches lazy associations triggers extra queries or LazyInitializationException, and a mutable-field hashCode
 * breaks HashSet membership after the id is assigned on persist.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** URL-friendly unique key, e.g. "oral-care" (unique constraint uk_category_slug). */
    @Column(name = "slug", nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    public Category(String name, String slug, String description) {
        this.name = name;
        this.slug = slug;
        this.description = description;
    }
}

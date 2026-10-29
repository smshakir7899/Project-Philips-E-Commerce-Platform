package com.philips.ecommerce.product.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalog product. Every association is LAZY on purpose: what to load is decided per use case by the query
 * (entity graph / fetch join / dedicated query), never globally by the mapping. The baseline query service
 * shows what happens when lazy associations are simply touched in a loop (N+1).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product")
public class Product {

    /**
     * IDENTITY = MySQL AUTO_INCREMENT. Trade-off worth knowing: Hibernate must execute the INSERT immediately to
     * learn the id, so JDBC insert batching is disabled for this entity. Fine for a catalog with low write volume.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku", nullable = false, length = 64, unique = true)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** TEXT column; part of the FULLTEXT index ft_product_search(name, brand, description). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "brand", nullable = false, length = 100)
    private String brand;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** ISO-4217 code, CHAR(3). columnDefinition keeps ddl-auto=validate happy (VARCHAR vs CHAR). */
    @Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
    private String currency = "EUR";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** false = soft-deleted / discontinued: hidden from listings, still resolvable by id (order history, batch). */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking: concurrent updates fail with 409 CONCURRENT_MODIFICATION instead of lost updates. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Images are managed through ProductImageService (each row has a matching S3 object), therefore no cascade:
     * removing a row must be coordinated with deleting the object.
     */
    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ProductImage> images = new ArrayList<>();

    /**
     * Attributes are pure value-like children: cascade + orphanRemoval means "replace the list" is just
     * clear() + add(). {@code @BatchSize} lets Hibernate initialize the attributes of up to 50 products
     * with one {@code IN (...)} query if several products' attributes are accessed in one session.
     */
    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    private List<ProductAttribute> attributes = new ArrayList<>();

    /** Keeps both sides of the bidirectional association in sync (the owning side is ProductAttribute.product). */
    public void addAttribute(String attributeName, String attributeValue) {
        ProductAttribute attribute = new ProductAttribute(this, attributeName, attributeValue);
        attributes.add(attribute);
    }

    public void clearAttributes() {
        // With orphanRemoval=true, removing from the collection deletes the rows at flush time.
        attributes.clear();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

package com.philips.ecommerce.product.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/** Image metadata; the binary lives in S3/MinIO under {@link #s3Key}. sortOrder 0 = thumbnail. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_image")
public class ProductImage {

    public static final int THUMBNAIL_SORT_ORDER = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Object key inside the bucket, e.g. products/1/3f2a....png */
    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public ProductImage(Product product, String s3Key, String url, String contentType, int sortOrder) {
        this.product = product;
        this.s3Key = s3Key;
        this.url = url;
        this.contentType = contentType;
        this.sortOrder = sortOrder;
    }
}

package com.philips.ecommerce.product.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

/**
 * Binds {@code app.storage.s3.*}. Note that there are deliberately NO credential properties here: credentials
 * are resolved by the AWS SDK default provider chain (see {@link S3Config}).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.storage.s3")
public class StorageProperties {

    @NotBlank
    private String bucket = "philips-product-images";

    @NotBlank
    private String region = "eu-west-1";

    /** S3-compatible endpoint override (MinIO locally). Empty means "use the real AWS S3 regional endpoint". */
    private String endpoint = "";

    /**
     * Endpoint used only to SIGN presigned URLs. A SigV4 signature covers the Host header, so the URL must be
     * signed for the host the BROWSER will call. In Docker the service reaches MinIO as http://minio:9000 but
     * the browser reaches it as http://localhost:9000. Empty means "same as endpoint" (always the case on AWS).
     */
    private String presignEndpoint = "";

    /** MinIO needs path-style URLs (http://host:9000/bucket/key); AWS prefers virtual-hosted style. */
    private boolean pathStyleAccess = false;

    /** Base URL used to build the public image URL stored in product_image.url. */
    @NotBlank
    private String publicBaseUrl = "http://localhost:9000/philips-product-images";

    @Min(1)
    private long presignDurationMinutes = 15;
}

package com.philips.ecommerce.product.service.storage;

import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.product.config.StorageProperties;
import com.philips.ecommerce.product.dto.PresignedUrlResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AWS SDK v2 implementation. Works against real S3 (EC2 instance role credentials) and MinIO (endpoint override +
 * path-style access + env-var credentials) without code changes - see S3Config.
 */
@Slf4j
@Component
public class S3ProductImageStorage implements ProductImageStorage {

    /** Mirrors spring.servlet.multipart.max-file-size; enforced again here (defence in depth). */
    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> EXTENSIONS;

    static {
        Map<String, String> extensions = new HashMap<>();
        extensions.put("image/jpeg", "jpg");
        extensions.put("image/png", "png");
        extensions.put("image/webp", "webp");
        EXTENSIONS = Collections.unmodifiableMap(extensions);
    }

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3ProductImageStorage(S3Client s3Client, S3Presigner presigner, StorageProperties properties) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public StoredImage store(Long productId, String contentType, byte[] content) {
        String type = normalize(contentType);
        String extension = EXTENSIONS.get(type);
        if (extension == null) {
            throw new BadRequestException("UNSUPPORTED_IMAGE_TYPE",
                    "Unsupported content type '" + contentType + "'. Allowed: image/jpeg, image/png, image/webp");
        }
        if (content == null || content.length == 0) {
            throw new BadRequestException("EMPTY_FILE", "The uploaded file is empty");
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "Image exceeds the maximum size of " + MAX_SIZE_BYTES + " bytes");
        }
        // The Content-Type header is chosen by the client and can lie: check the file signature as well.
        if (!matchesSignature(type, content)) {
            throw new BadRequestException("UNSUPPORTED_IMAGE_TYPE", "File content does not match " + type);
        }
        // Random UUID: no collisions, no user-controlled path segments, no guessable names.
        String key = "products/" + productId + "/" + UUID.randomUUID() + "." + extension;
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(type)
                .contentLength((long) content.length)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException ex) {
            throw unavailable("upload " + key, ex);
        }
        log.info("Stored image s3://{}/{} ({} bytes)", properties.getBucket(), key, content.length);
        return new StoredImage(key, publicUrl(key), type, content.length);
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            log.info("Deleted image s3://{}/{}", properties.getBucket(), key);
        } catch (SdkException ex) {
            throw unavailable("delete " + key, ex);
        }
    }

    @Override
    public PresignedUrlResponse presignGet(String key) {
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(properties.getPresignDurationMinutes()))
                    .getObjectRequest(GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build())
                    .build();
            PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
            return new PresignedUrlResponse(presigned.url().toString(), presigned.expiration());
        } catch (SdkException ex) {
            // e.g. no credentials could be resolved by the default provider chain
            throw unavailable("presign " + key, ex);
        }
    }

    String publicUrl(String key) {
        String base = properties.getPublicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + key;
    }

    private static String normalize(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';'); // "image/png; charset=binary"
        String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /** Magic numbers: JPEG FF D8 FF, PNG 89 'P' 'N' 'G', WEBP "RIFF" ???? "WEBP". */
    static boolean matchesSignature(String type, byte[] c) {
        switch (type) {
            case "image/jpeg":
                return c.length >= 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF;
            case "image/png":
                return c.length >= 4 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G';
            case "image/webp":
                return c.length >= 12 && c[0] == 'R' && c[1] == 'I' && c[2] == 'F' && c[3] == 'F'
                        && c[8] == 'W' && c[9] == 'E' && c[10] == 'B' && c[11] == 'P';
            default:
                return false;
        }
    }

    private ServiceUnavailableException unavailable(String operation, SdkException ex) {
        log.warn("S3 {} failed: {}", operation, ex.getMessage());
        return new ServiceUnavailableException("STORAGE_UNAVAILABLE", "Image storage is currently unavailable", ex);
    }
}

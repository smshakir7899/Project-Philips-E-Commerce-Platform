package com.philips.ecommerce.product.service.storage;

import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.product.config.StorageProperties;
import com.philips.ecommerce.product.dto.PresignedUrlResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ProductImageStorageTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

    @Mock
    private S3Client s3Client;

    /** Presigning is pure local crypto, so a REAL presigner with dummy static credentials is used. */
    private S3Presigner presigner;
    private S3ProductImageStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("philips-product-images");
        properties.setRegion("eu-west-1");
        properties.setEndpoint("http://localhost:9000");
        properties.setPathStyleAccess(true);
        properties.setPublicBaseUrl("http://localhost:9000/philips-product-images/");
        properties.setPresignDurationMinutes(15);
        presigner = S3Presigner.builder()
                .region(Region.EU_WEST_1)
                .endpointOverride(URI.create("http://localhost:9000"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .build();
        storage = new S3ProductImageStorage(s3Client, presigner, properties);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void rejectsUnsupportedContentTypeWithoutCallingS3() {
        assertThatThrownBy(() -> storage.store(1L, "image/gif", new byte[]{'G', 'I', 'F', '8'}))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("UNSUPPORTED_IMAGE_TYPE");
        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsContentThatDoesNotMatchTheDeclaredType() {
        assertThatThrownBy(() -> storage.store(1L, "image/png", JPEG))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("UNSUPPORTED_IMAGE_TYPE");
        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> storage.store(1L, "image/png", new byte[0]))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("EMPTY_FILE");
        byte[] huge = new byte[(int) S3ProductImageStorage.MAX_SIZE_BYTES + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        assertThatThrownBy(() -> storage.store(1L, "image/jpeg", huge))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("FILE_TOO_LARGE");
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                });
        verifyNoInteractions(s3Client);
    }

    @Test
    void uploadsWithRandomKeyUnderTheProductPrefix() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());

        StoredImage stored = storage.store(7L, "image/png; charset=binary", PNG);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("philips-product-images");
        assertThat(request.getValue().key()).matches("products/7/[0-9a-f\\-]{36}\\.png");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().contentLength()).isEqualTo(PNG.length);
        assertThat(stored.getKey()).isEqualTo(request.getValue().key());
        assertThat(stored.getUrl()).isEqualTo("http://localhost:9000/philips-product-images/" + stored.getKey());
    }

    @Test
    void sdkFailureBecomes503StorageUnavailable() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Connection refused"));

        assertThatThrownBy(() -> storage.store(7L, "image/jpeg", JPEG))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting("code").isEqualTo("STORAGE_UNAVAILABLE");
    }

    @Test
    void deleteFailureBecomes503() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("timeout"));

        assertThatThrownBy(() -> storage.delete("products/1/a.png"))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting("code").isEqualTo("STORAGE_UNAVAILABLE");
    }

    @Test
    void presignedUrlIsPathStyleSignedAndExpiresAfterConfiguredDuration() {
        PresignedUrlResponse response = storage.presignGet("products/1/a.png");

        assertThat(response.getUrl())
                .startsWith("http://localhost:9000/philips-product-images/products/1/a.png?")
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=900");
        assertThat(response.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(15)), within(30, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void signatureCheck() {
        assertThat(S3ProductImageStorage.matchesSignature("image/webp",
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})).isTrue();
        assertThat(S3ProductImageStorage.matchesSignature("image/webp", PNG)).isFalse();
        assertThat(S3ProductImageStorage.matchesSignature("image/jpeg", JPEG)).isTrue();
    }
}

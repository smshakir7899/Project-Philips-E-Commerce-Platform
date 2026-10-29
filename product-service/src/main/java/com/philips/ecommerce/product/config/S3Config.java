package com.philips.ecommerce.product.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * AWS SDK v2 clients for product images.
 * <p>
 * <b>No credentials are hardcoded or configured here.</b> {@link DefaultCredentialsProvider} walks the standard
 * chain: Java system properties, environment variables ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY},
 * which docker compose sets to the MinIO credentials locally), web identity token, the shared credentials file,
 * container credentials and finally the EC2 instance profile (IAM role) - which is what production on EC2 uses,
 * with the env vars left unset. Credentials are resolved lazily on the first request, so the application starts
 * even when none are available (e.g. in unit tests).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class S3Config {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(s3Configuration(properties));
        if (StringUtils.hasText(properties.getEndpoint())) {
            // Only for S3-compatible stores (MinIO). For real AWS the SDK derives the regional endpoint itself.
            builder.endpointOverride(URI.create(properties.getEndpoint()));
            log.info("S3 client uses endpoint override {} (pathStyle={})", properties.getEndpoint(),
                    properties.isPathStyleAccess());
        }
        return builder.build();
    }

    /**
     * Presigning is a purely local signing operation (no network call). It signs for the PUBLIC endpoint when one
     * is configured (see {@link StorageProperties#getPresignEndpoint()}), otherwise for the client endpoint.
     */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(StorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(s3Configuration(properties));
        String endpoint = StringUtils.hasText(properties.getPresignEndpoint())
                ? properties.getPresignEndpoint() : properties.getEndpoint();
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    private S3Configuration s3Configuration(StorageProperties properties) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isPathStyleAccess())
                .build();
    }
}

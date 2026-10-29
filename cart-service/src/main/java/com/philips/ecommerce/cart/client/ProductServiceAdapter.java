package com.philips.ecommerce.cart.client;

import com.philips.ecommerce.cart.client.dto.ProductDto;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.feign.RemoteServiceException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer around {@link ProductClient}: resilience + translation of remote failures into this
 * service's error codes.
 *
 * <p>Execution order (Resilience4j default aspect order): Retry( CircuitBreaker( this method ) ).
 * <ul>
 *   <li>The method body translates Feign failures: remote 404 -> {@link ResourceNotFoundException}
 *       PRODUCT_NOT_FOUND; other remote 4xx -> rethrown unchanged; 5xx / timeout / connection refused
 *       ({@link RetryableException}) -> {@link ServiceUnavailableException} PRODUCT_SERVICE_UNAVAILABLE.</li>
 *   <li>The circuit breaker records ServiceUnavailableException as a failure but IGNORES the business 4xx
 *       (config ignore-exceptions): the product service answered correctly, it is not unhealthy.</li>
 *   <li>Retry only retries ServiceUnavailableException (config retry-exceptions). A 404 stays a 404 however
 *       often it is asked, and an open breaker must fail fast.</li>
 *   <li>When the breaker is open it throws CallNotPermittedException before this method runs, so it cannot be
 *       translated in the body; the Retry fallback (outermost layer) converts it into a 503.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductServiceAdapter {

    static final String RESILIENCE_INSTANCE = "productService";
    static final String UNAVAILABLE_CODE = "PRODUCT_SERVICE_UNAVAILABLE";

    private final ProductClient productClient;

    @Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "circuitOpen")
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public ProductDto getProduct(Long productId) {
        try {
            return productClient.getProduct(productId);
        } catch (RemoteServiceException e) {
            if (e.isNotFound()) {
                throw new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product " + productId + " does not exist")
                        .withDetail("productId", productId);
            }
            if (e.isClientError()) {
                throw e;
            }
            log.warn("product-service failed for product {}: HTTP {} {}", productId, e.getRemoteStatus(), e.getCode());
            throw new ServiceUnavailableException(UNAVAILABLE_CODE,
                    "product-service is currently unavailable, please retry later", e);
        } catch (RetryableException e) {
            log.warn("product-service unreachable for product {}: {}", productId, e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE_CODE,
                    "product-service is currently unavailable, please retry later", e);
        }
    }

    /**
     * Fallback of the outer @Retry. Resilience4j picks a fallback by the exception parameter type, so this one only
     * handles an open circuit; any other exception propagates unchanged.
     */
    @SuppressWarnings("unused")
    private ProductDto circuitOpen(Long productId, CallNotPermittedException e) {
        log.warn("Circuit breaker {} is open, not calling product-service for product {}", RESILIENCE_INSTANCE,
                productId);
        throw new ServiceUnavailableException(UNAVAILABLE_CODE,
                "product-service is currently unavailable (circuit open), please retry later", e);
    }
}

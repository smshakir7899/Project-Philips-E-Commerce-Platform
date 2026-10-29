package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.ProductDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resilience wrapper around {@link ProductClient} (see {@link CartServiceAdapter} for the pattern). */
@Component
public class ProductServiceAdapter {

    static final String INSTANCE = "product-service";

    private final ProductClient productClient;

    public ProductServiceAdapter(ProductClient productClient) {
        this.productClient = productClient;
    }

    @Retry(name = INSTANCE, fallbackMethod = "getProductsFallback")
    @CircuitBreaker(name = INSTANCE)
    public List<ProductDto> getProducts(List<Long> ids) {
        return productClient.getProducts(ids);
    }

    @SuppressWarnings("unused")
    private List<ProductDto> getProductsFallback(List<Long> ids, Throwable failure) {
        throw RemoteFailures.translate(failure, "PRODUCT_SERVICE_UNAVAILABLE", INSTANCE);
    }
}

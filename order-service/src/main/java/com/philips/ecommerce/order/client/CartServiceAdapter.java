package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.CartDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

/**
 * Resilience wrapper around {@link CartClient}. The annotations live on this Spring bean (not on the Feign
 * interface) so the Resilience4j aspects apply through a normal Spring AOP proxy.
 * <p>
 * Aspect order (Resilience4j default): Retry( CircuitBreaker( call ) ) - each retry attempt is recorded by the
 * breaker, and an open breaker fails fast with CallNotPermittedException, which is not retried.
 * The fallback is attached to the OUTER aspect (Retry) so it only runs once retries are exhausted.
 * GET is naturally idempotent, so retrying is safe.
 */
@Component
public class CartServiceAdapter {

    static final String INSTANCE = "cart-service";

    private final CartClient cartClient;

    public CartServiceAdapter(CartClient cartClient) {
        this.cartClient = cartClient;
    }

    @Retry(name = INSTANCE, fallbackMethod = "getCartFallback")
    @CircuitBreaker(name = INSTANCE)
    public CartDto getCart(String customerId) {
        return cartClient.getCart(customerId);
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private CartDto getCartFallback(String customerId, Throwable failure) {
        throw RemoteFailures.translate(failure, "CART_SERVICE_UNAVAILABLE", INSTANCE);
    }
}

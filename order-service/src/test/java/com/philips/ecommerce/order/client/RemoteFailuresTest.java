package com.philips.ecommerce.order.client;

import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.feign.RemoteServiceException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteFailuresTest {

    private final RemoteCallFailurePredicate predicate = new RemoteCallFailurePredicate();

    private static RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.POST, "http://payment-service/api/payments",
                Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, null, request);
    }

    private static RemoteServiceException remote(int status, String code) {
        HttpStatus local = status >= 500 && status != 503 ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(status);
        return new RemoteServiceException(local, code, "remote said no", status, "Client#call()");
    }

    @Test
    void businessErrorsAreRethrownUnchangedAndAreNotCircuitFailures() {
        RemoteServiceException conflict = remote(409, "INSUFFICIENT_STOCK");

        assertThat(RemoteFailures.translate(conflict, "INVENTORY_SERVICE_UNAVAILABLE", "inventory-service"))
                .isSameAs(conflict);
        assertThat(predicate.test(conflict)).isFalse();
    }

    @Test
    void timeoutBecomesServiceUnavailable() {
        RuntimeException result = RemoteFailures.translate(timeout(), "PAYMENT_SERVICE_UNAVAILABLE",
                "payment-service");

        assertThat(result).isInstanceOf(ServiceUnavailableException.class);
        assertThat(((ServiceUnavailableException) result).getCode()).isEqualTo("PAYMENT_SERVICE_UNAVAILABLE");
        assertThat(predicate.test(timeout())).isTrue();
    }

    @Test
    void remote5xxBecomesServiceUnavailableAndCountsAsFailure() {
        RemoteServiceException gatewayError = remote(503, "PAYMENT_GATEWAY_ERROR");

        RuntimeException result = RemoteFailures.translate(gatewayError, "PAYMENT_SERVICE_UNAVAILABLE",
                "payment-service");

        assertThat(((ServiceUnavailableException) result).getCode()).isEqualTo("PAYMENT_SERVICE_UNAVAILABLE");
        assertThat(result.getCause()).isSameAs(gatewayError);
        assertThat(predicate.test(gatewayError)).isTrue();
    }

    @Test
    void openCircuitBecomesServiceUnavailable() {
        CallNotPermittedException open =
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("cart-service"));

        RuntimeException result = RemoteFailures.translate(open, "CART_SERVICE_UNAVAILABLE", "cart-service");

        assertThat(((ServiceUnavailableException) result).getCode()).isEqualTo("CART_SERVICE_UNAVAILABLE");
        assertThat(result.getMessage()).contains("circuit breaker is open");
    }
}

package com.philips.ecommerce.order.client;

import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.feign.RemoteServiceException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * Translates what is left after Resilience4j gave up into the exception contract of the adapters:
 * <ul>
 *   <li>business errors (RemoteServiceException with 4xx) are rethrown UNCHANGED - the caller decides
 *       (e.g. 409 INSUFFICIENT_STOCK cancels the order);</li>
 *   <li>everything that means "service unhealthy" - Feign timeout/connection error ({@link RetryableException}),
 *       open circuit ({@link CallNotPermittedException}), remote 5xx, undecodable response - becomes
 *       {@link ServiceUnavailableException} with {@code <SERVICE>_SERVICE_UNAVAILABLE} (HTTP 503).</li>
 * </ul>
 */
public final class RemoteFailures {

    private RemoteFailures() {
    }

    public static RuntimeException translate(Throwable failure, String unavailableCode, String serviceName) {
        if (failure instanceof RemoteServiceException && ((RemoteServiceException) failure).isClientError()) {
            return (RemoteServiceException) failure;
        }
        String reason;
        if (failure instanceof CallNotPermittedException) {
            reason = "circuit breaker is open";
        } else if (failure instanceof RetryableException) {
            reason = "timeout or connection failure";
        } else if (failure instanceof RemoteServiceException) {
            reason = "HTTP " + ((RemoteServiceException) failure).getRemoteStatus();
        } else {
            reason = failure.getClass().getSimpleName();
        }
        return new ServiceUnavailableException(unavailableCode,
                serviceName + " is unavailable (" + reason + ")", failure);
    }
}
